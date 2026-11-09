package com.hz.water;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * 闹钟声音的前台服务.
 *
 * 为什么声音必须放在 Service 里, 不能放在 Receiver 或只靠 Activity:
 *  1. BroadcastReceiver.onReceive 只给 10 秒左右就返回, 放铃声会被系统掐断;
 *  2. 后台启动 Activity 在 Android 10+ 被严格限制, Android 14 上日志原文是
 *     `Background activity launch blocked! ... resultIfPiCreatorAllowsBal: BAL_BLOCK`
 *     + `Abort background activity starts from <uid>`, 因此界面可能根本弹不出来;
 *  3. 前台服务是"后台可启动 + 不受 Doze 冻结"的唯一稳定通道, 铃声与震动放在这里,
 *     即使界面被拦, 用户照样能听到、能被震动叫醒.
 *
 * 界面(AlarmActivity)只负责显示, 不负责发声; 用户在界面上点关闭 / 稍后, 直接 stopService.
 *
 * 起停顺序是有讲究的(照抄 DeskClock 的 startAlarm / stopCurrentAlarm):
 *   起: wakelock -> startForeground -> 音频焦点 -> 放音 -> 震动
 *   停: 停铃声 -> 停震动 -> 放弃焦点 -> stopForeground -> 放 wakelock
 * 顺序颠倒会出两种真实故障: 先放音后 startForeground 会被系统当成后台放音掐掉;
 * 先 release wakelock 再停铃声, 中间那几毫秒 CPU 可能已经睡了.
 *
 * 本文件不用匿名内部类(javac 21 的 NestHost 会让 D8 8.2.2 抛 NPE).
 */
public class AlarmService extends Service {

    static final String ACTION_START = "com.hz.water.ALARM_START";
    static final String ACTION_STOP = "com.hz.water.ALARM_STOP";

    static final String EX_TITLE = "title";
    static final String EX_TEXT = "text";
    static final String EX_TAG = "tag";

    /** 前台服务通知 id, 与 NotificationRelay.ID_ALARM 区分开 */
    static final int ID_FG = 8802;

    /** 渐强: 每步间隔与总时长上限 */
    static final long FADE_STEP_MS = 50L;
    static final float FADE_STEP = 0.05f;

    static volatile boolean running = false;
    static volatile String curTag = "";

    Ringtone ring;
    Vibrator vib;
    PowerManager.WakeLock wl;
    /* 声明成 Object 而不是 AudioFocusRequest: 后者是 API 26 才有的类, 写成字段类型
       会让 API 23-25 的机器在类校验阶段就抛 NoClassDefFoundError / VerifyError,
       连响都响不了. 用 Object 存引用, 用到的地方再强转, 老系统上这条路径不执行. */
    Object focusReq;
    Handler fadeH;
    FadeStep fadeStep;
    FocusListener focusLis;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String act = i.getAction();
        if (ACTION_STOP.equals(act)) {
            Log.i("HzWater", "alarm service stop: " + curTag);
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = i.getStringExtra(EX_TITLE);
        String text = i.getStringExtra(EX_TEXT);
        String tag = i.getStringExtra(EX_TAG);
        if (tag == null) tag = "";
        curTag = tag;
        running = true;

        /* 顺序 1: CPU 先醒着, 后面几步才不会被 Doze 掐在半路 */
        holdWake();
        /* 顺序 2: 先转前台. 反过来的话, Android 12+ 会把放音判成后台播放直接静音 */
        startForeground(ID_FG, AlarmService.buildNotification(this, title, text, tag));
        /* 顺序 3: 抢闹钟音频焦点, 让音乐/视频让路(GAIN_TRANSIENT, 不是 GAIN) */
        requestFocus();
        /* 顺序 4: 放音(渐强) */
        playSound();
        /* 顺序 5: 震动 */
        vibrate();
        Log.i("HzWater", "alarm service playing: " + title + " / " + text + " tag=" + tag);
        return START_STICKY;
    }

    /** 前台服务的常驻通知, 与 NotificationRelay 的闹钟通知同一形态 */
    static Notification buildNotification(Context c, String title, String text, String tag) {
        NotificationRelay.ensureChannel(c);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, NotificationRelay.CH_ID);
        } else {
            b = new Notification.Builder(c);
        }
        b.setContentTitle(title == null ? "洗衣完成" : title)
                .setContentText(text == null ? "" : text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(AlarmReceiver.buildUiIntent(c, title, text, tag));
        return b.build();
    }

    /* ---------- 音频焦点 ---------- */

    void requestFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            focusLis = new FocusListener(this);
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = Focus26.request(am, focusLis);
            } else {
                int r = am.requestAudioFocus(focusLis, AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                Log.i("HzWater", "alarm audio focus(legacy): " + r);
            }
        } catch (Throwable t) {
            Log.w("HzWater", "alarm focus failed: " + t);
        }
    }

    void abandonFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq != null) Focus26.abandon(am, focusReq);
            } else {
                if (focusLis != null) am.abandonAudioFocus(focusLis);
            }
        } catch (Throwable t) {
            Log.w("HzWater", "alarm abandon focus failed: " + t);
        }
        focusReq = null;
        focusLis = null;
    }

    /**
     * API 26+ 的音频焦点请求, 单独关在静态嵌套类里.
     *
     * 为什么不能直接写在 requestFocus 里: AudioFocusRequest 这个类本身是 API 26
     * 才有的. 只要它出现在常被调用的方法的常量池里, 老系统(23-25)在类校验阶段
     * 就可能抛 VerifyError / NoClassDefFoundError, 结果是闹钟彻底不响 —— 而不是
     * 安静地退化成老写法. 关进独立的类, 老系统压根不加载它, 问题就不存在.
     */
    static class Focus26 {
        static Object request(AudioManager am, AudioManager.OnAudioFocusChangeListener lis) {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFocusRequest req = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(aa)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(lis)
                    .build();
            int r = am.requestAudioFocus(req);
            Log.i("HzWater", "alarm audio focus(26+): " + r);
            return req;
        }

        static void abandon(AudioManager am, Object req) {
            try {
                am.abandonAudioFocusRequest((AudioFocusRequest) req);
            } catch (Throwable t) {
                Log.w("HzWater", "abandon focus(26+) failed: " + t);
            }
        }
    }

    /**
     * 焦点变化回调.
     *
     * 被别的应用抢走(比如来了电话)时不能自己把闹钟关掉 -- 闹钟是用户明确要求
     * "到点一定要响"的东西, 丢焦点只是短暂让路, 回来还要接着响. 这里只记日志.
     */
    static class FocusListener implements AudioManager.OnAudioFocusChangeListener {
        final AlarmService svc;

        FocusListener(AlarmService s) {
            svc = s;
        }

        @Override
        public void onAudioFocusChange(int f) {
            Log.i("HzWater", "alarm audio focus change: " + f);
        }
    }

    /* ---------- 放音 ---------- */

    private void playSound() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ring = RingtoneManager.getRingtone(this, u);
            if (ring == null) return;
            if (Build.VERSION.SDK_INT >= 21) {
                ring.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
            /* 刻意不调 setStreamVolume(STREAM_ALARM, ...): 那会把系统的闹钟音量档
               永久改掉, 用户下次开别的闹钟会莫名其妙变响. 要"肯定听得见"就用
               相对系数从 0.2 爬到 1.0 -- Ringtone.setVolume 只影响这一路播放. */
            try {
                ring.setVolume(0.2f);
            } catch (Throwable ignored) {
            }
            ring.play();
            startFade();
        } catch (Throwable t) {
            Log.w("HzWater", "alarm sound failed: " + t);
        }
    }

    /** 渐强: 每 50ms 往上爬一点, 免得一上来最大声把人从床上弹起来 */
    void startFade() {
        if (ring == null) return;
        try {
            if (fadeH == null) fadeH = new Handler(Looper.getMainLooper());
            if (fadeStep == null) fadeStep = new FadeStep(this);
            fadeH.postDelayed(fadeStep, FADE_STEP_MS);
        } catch (Throwable t) {
            Log.w("HzWater", "alarm fade failed: " + t);
        }
    }

    static class FadeStep implements Runnable {
        final AlarmService svc;
        float v = 0.2f;

        FadeStep(AlarmService s) {
            svc = s;
        }

        @Override
        public void run() {
            if (!svc.running || svc.ring == null) return;
            v += FADE_STEP;
            if (v > 1f) v = 1f;
            try {
                svc.ring.setVolume(v);
            } catch (Throwable ignored) {
            }
            if (v < 1f && svc.fadeH != null) {
                svc.fadeH.postDelayed(this, FADE_STEP_MS);
            }
        }
    }

    /* ---------- 震动 ---------- */

    private void vibrate() {
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            long[] pat = new long[]{0, 800, 600, 800, 600, 800, 2000};
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                vib.vibrate(VibrationEffect.createWaveform(pat, 0), aa);
            } else {
                vib.vibrate(pat, 0);
            }
        } catch (Throwable t) {
            Log.w("HzWater", "alarm vibrate failed: " + t);
        }
    }

    /* ---------- wakelock ---------- */

    /**
     * 只要 CPU 醒着就够了, 不点屏.
     *
     * 旧代码用的是 SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, 那会把屏幕
     * 强行点亮 -- 半夜到点直接把整块屏照在脸上, 而真正该亮屏的是 AlarmActivity
     * (它有 FLAG_KEEP_SCREEN_ON 与全屏通知). 服务这边 PARTIAL 就够.
     */
    private void holdWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hz:alarmsvc");
            wl.setReferenceCounted(false);
            wl.acquire(10 * 60 * 1000L);
        } catch (Throwable t) {
            Log.w("HzWater", "alarm wakelock failed: " + t);
        }
    }

    private void stopAll() {
        running = false;
        try {
            if (fadeH != null && fadeStep != null) fadeH.removeCallbacks(fadeStep);
        } catch (Throwable ignored) {
        }
        fadeH = null;
        fadeStep = null;
        try {
            if (ring != null && ring.isPlaying()) ring.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (vib != null) vib.cancel();
        } catch (Throwable ignored) {
        }
        abandonFocus();
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignored) {
        }
        ring = null;
        vib = null;
        wl = null;
    }

    @Override
    public void onDestroy() {
        stopAll();
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) {
        }
        Log.i("HzWater", "alarm service destroyed");
        super.onDestroy();
    }
}
