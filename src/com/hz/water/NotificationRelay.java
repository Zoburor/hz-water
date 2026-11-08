package com.hz.water;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 通知发送的静态出口, 供 AlarmReceiver 在非 Activity 上下文里用.
 *
 * 闹钟类通知必须是"全屏意图"(setFullScreenIntent) + CATEGORY_ALARM + HIGH 重要级,
 * 这样锁屏或熄屏时系统才会直接把 AlarmActivity 顶到前台.
 * 只发普通通知的话, 屏幕不亮, 也没有任何界面, 用户体感就是"闹钟没响".
 */
final class NotificationRelay {

    static final String CH_ID = "hz_alarm";
    static final String CH_NAME = "洗衣完成提醒";
    static final int ID_ALARM = 8801;

    private NotificationRelay() {
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("洗衣机洗完之后提醒");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 600, 800, 600, 800});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    /** 不带闹钟身份的普通提醒 */
    static void post(Context c, String title, String text) {
        post(c, title, text, "");
    }

    /**
     * 发一条闹钟级通知.
     *
     * @param tag 闹钟标识, 全屏界面与通知绑到同一个 tag
     */
    static void post(Context c, String title, String text, String tag) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(c);

        // 全屏意图: 拉起 AlarmActivity(响铃 + 震动 + 亮屏 + 界面).
        // 复用 AlarmReceiver.buildUiIntent, 这样点通知与到点自动弹走的是同一条
        // 带后台启动 opt-in 的 PendingIntent. 小米机型上
        // USE_FULL_SCREEN_INTENT 的 appop 被强制成 ignore(通知 flags 里出现
        // FSI_REQUESTED_BUT_DENIED), 全屏意图实际不会生效, 界面靠 AlarmReceiver 主动 send 拉起.
        PendingIntent fullPi = AlarmReceiver.buildUiIntent(c, title, text, tag);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, CH_ID);
        } else {
            b = new Notification.Builder(c);
        }
        b.setContentTitle(title == null ? "洗衣完成" : title)
                .setContentText(text == null ? "" : text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .setContentIntent(fullPi)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            b.setFullScreenIntent(fullPi, true);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            b.setTimeoutAfter(30 * 60 * 1000L);
        }
        nm.notify(ID_ALARM, b.build());
    }
}
