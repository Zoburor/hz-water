package com.hz.water;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
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

    static volatile boolean running = false;
    static volatile String curTag = "";

    Ringtone ring;
    Vibrator vib;
    PowerManager.WakeLock wl;

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

        startForeground(ID_FG, AlarmService.buildNotification(this, title, text, tag));
        playSound();
        vibrate();
        holdWake();
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

    private void playSound() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
                int want = Math.max(cur, max * 3 / 4);
                if (want > cur) am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
            }
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
            ring.play();
        } catch (Throwable t) {
            Log.w("HzWater", "alarm sound failed: " + t);
        }
    }

    private void vibrate() {
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            long[] pat = new long[]{0, 800, 600, 800, 600, 800, 2000};
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pat, 0));
            } else {
                vib.vibrate(pat, 0);
            }
        } catch (Throwable t) {
            Log.w("HzWater", "alarm vibrate failed: " + t);
        }
    }

    private void holdWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "hz:alarmsvc");
            wl.acquire(10 * 60 * 1000L);
        } catch (Throwable t) {
            Log.w("HzWater", "alarm wakelock failed: " + t);
        }
    }

    private void stopAll() {
        running = false;
        try {
            if (ring != null && ring.isPlaying()) ring.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (vib != null) vib.cancel();
        } catch (Throwable ignored) {
        }
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
