package com.hz.water;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * 闹钟到点的接收器.
 *
 * 到点做三件事, 按可靠性排序:
 * 1. 唤醒屏幕(短 WakeLock);
 * 2. 拉起 AlarmActivity. 这里**不能直接 startActivity**: targetSdk 34 在 Android 14 上会被
 *    后台启动限制拦掉, 系统日志原文是
 *    `Background activity launch blocked! ... resultIfPiCreatorAllowsBal: BAL_BLOCK`
 *    + `Abort background activity starts from <uid>`. 正确做法是通过一个由本应用创建、
 *    并显式 opt-in 了后台启动的 PendingIntent 去 send() -- 日志里
 *    `balAllowedByPiSender: BSP.ALLOW_BAL` 只有在 `isPendingIntent: true` 时才成立;
 * 3. 再补一条闹钟级通知兜底 -- 万一系统仍拦下界面, 至少还有声响与入口.
 *
 * 亮屏/响铃/震动/界面都交给 AlarmActivity 自己做 -- 在 Receiver 里放铃声会随进程回收被掐断.
 */
public class AlarmReceiver extends BroadcastReceiver {

    /** 请求码固定, 与 NotificationRelay 的全屏意图区分开 */
    private static final int RC_UI = 91;

    @Override
    public void onReceive(Context c, Intent i) {
        String title = i.getStringExtra("title");
        String text = i.getStringExtra("text");
        String tag = i.getStringExtra("tag");
        Log.i("HzWater", "alarm fired: " + title + " / " + text + " tag=" + tag);
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "hz:alarm");
                wl.acquire(8000L);
                wl.release();
            }
        } catch (Exception ignored) {
        }

        boolean svc = startSoundService(c, title, text, tag);
        Log.i("HzWater", "alarm sound service: " + (svc ? "started" : "start failed"));

        boolean shown = popUi(c, title, text, tag);
        Log.i("HzWater", "alarm ui: " + (shown ? "pendingIntent.send ok" : "send failed"));
        NotificationRelay.post(c, title, text, tag);
    }

    /**
     * 拉起前台服务负责响铃与震动.
     *
     * 这是真正保证"叫得醒"的那条路: Receiver 的 onReceive 只有约 10 秒生命期,
     * 在这里放铃声会被系统掐断; 而后台启动 Activity 又被 BAL 拦死
     * (实测 MIUI 上 `resultIfPiCreatorAllowsBal: BAL_BLOCK`, 即使 PendingIntent 显式 opt-in 也无效).
     * 前台服务是后台可启动、不被 Doze 冻结的稳定通道, 界面弹不出来也照样响.
     */
    static boolean startSoundService(Context c, String title, String text, String tag) {
        Intent sv = new Intent(c, AlarmService.class);
        sv.setAction(AlarmService.ACTION_START);
        sv.putExtra(AlarmService.EX_TITLE, title);
        sv.putExtra(AlarmService.EX_TEXT, text);
        sv.putExtra(AlarmService.EX_TAG, tag);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(sv);
            else c.startService(sv);
            return true;
        } catch (Throwable t) {
            // 实测: 用 am broadcast 手动触发时, 应用此前被 force-stop 过, 系统会回
            // `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed
            //  due to mAllowStartForeground false`. 真实闹钟(AlarmManager.setAlarmClock)
            // 触发的广播在 Android 12+ 属于 FGS 启动豁免场景, 但不同 ROM 实测不一致,
            // 所以这里必须有兜底, 不能让"叫不醒"重新出现.
            Log.w("HzWater", "alarm service start failed, fallback to receiver-side sound: " + t);
            return false;
        }
    }

    /**
     * 用 opt-in 过后台启动的 PendingIntent 拉起全屏闹钟界面.
     *
     * ActivityOptions.setPendingIntentBackgroundActivityStartMode 是 API 33 新增的显式开关,
     * 不加它时系统按 `balRequireOptInByPendingIntentCreator: true` 一律 BAL_BLOCK.
     */
    static PendingIntent buildUiIntent(Context c, String title, String text, String tag) {
        Intent ui = new Intent(c, AlarmActivity.class);
        ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        ui.putExtra(AlarmActivity.EX_TITLE, title);
        ui.putExtra(AlarmActivity.EX_TEXT, text);
        ui.putExtra(AlarmActivity.EX_TAG, tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                return PendingIntent.getActivity(c, RC_UI, ui, flags, opts.toBundle());
            } catch (Throwable t) {
                Log.w("HzWater", "bal opt-in unavailable: " + t);
            }
        }
        return PendingIntent.getActivity(c, RC_UI, ui, flags);
    }

    private boolean popUi(Context c, String title, String text, String tag) {
        try {
            buildUiIntent(c, title, text, tag).send();
            return true;
        } catch (Throwable t) {
            Log.w("HzWater", "alarm ui send failed: " + t);
        }
        try {
            c.startActivity(new Intent(c, AlarmActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AlarmActivity.EX_TITLE, title)
                    .putExtra(AlarmActivity.EX_TEXT, text)
                    .putExtra(AlarmActivity.EX_TAG, tag));
            return true;
        } catch (Throwable t2) {
            Log.w("HzWater", "alarm ui startActivity failed: " + t2);
            return false;
        }
    }
}
