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

    /**
     * 这台机器上"全屏通知"到底还能不能用.
     *
     * Android 14(API 34) 起, USE_FULL_SCREEN_INTENT 对非闹钟/非通话类应用默认不授予:
     * 装上去就有一个 appop 是 ignore, 发出去的通知带 FSI_REQUESTED_BUT_DENIED,
     * 全屏界面根本不弹. 系统级/预装应用不受此限, 所以我们抄不到 DeskClock 的做法
     * (它全文没有 canUseFullScreenIntent 调用, 因为它不需要).
     *
     * 注意 API 34 之前一律返回 true: 低版本没有这个限制, 也不该因为查不到而报错.
     */
    static boolean canFullScreen(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            return nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            /* 查不到就当不能用, 让页面去引导授权; 这比谎报 true 更好 --
               谎报 true 的结果是到点什么都不弹, 用户以为闹钟坏了 */
            return false;
        }
    }

    /**
     * 跳到"允许全屏通知"设置页.
     *
     * 用字面量 "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", 不用
     * Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT 常量: 常量是 API 34 才有的,
     * 我们的 build 用 android-34 之外的工具链时编不过, 而字面量在所有版本上都能编,
     * 到低版本只是 startActivity 抛 ActivityNotFound 后走兜底而已.
     *
     * @return 描述结果的 JSON, 页面据此决定要不要提示用户手动找
     */
    static String openFullScreenSettings(Context c) {
        if (Build.VERSION.SDK_INT < 34) {
            return "{\"ok\":false,\"sdk\":" + Build.VERSION.SDK_INT
                    + ",\"err\":\"这个系统版本没有全屏通知开关\"}";
        }
        if (canFullScreen(c)) {
            return "{\"ok\":true,\"already\":true}";
        }
        try {
            Intent i = new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT");
            i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            /* 不做 resolveActivity 预判: targetSdk 30+ 的包可见性会把它判成 null,
               连能用的机型也当成打不开. 直接 start, 失败在 catch 里兜. */
            c.startActivity(i);
            return "{\"ok\":true,\"opened\":true}";
        } catch (Throwable t) {
            try {
                Intent i2 = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i2.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i2);
                return "{\"ok\":true,\"opened\":\"notification-settings\"}";
            } catch (Throwable t2) {
                return "{\"ok\":false,\"err\":" + PrefStore.quote("打不开设置页: " + t2) + "}";
            }
        }
    }
}
