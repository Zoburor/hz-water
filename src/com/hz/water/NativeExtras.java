package com.hz.water;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生扩展能力: 定位, 系统闹钟, 通知, 权限协商, 安装未知应用授权.
 *
 * 约束与 MainActivity 相同: 全部静态嵌套类, 不用匿名内部类
 * (javac 21 的 NestHost 属性会让 build-tools 34 的 D8 8.2.2 抛 NPE).
 *
 * 闹钟为什么用 AlarmManager.setAlarmClock:
 *  1. 这是系统闹钟级 API, 会在状态栏常驻闹钟图标, 锁屏也会显示, 与自带闹钟同一档;
 *  2. 不受省电策略冻结(Doze), setExactAndAllowWhileIdle 仍可能被厂商冻结;
 *  3. 各品牌 ROM 对 SET_ALARM 这个 Intent 的实参支持程度不一, 但 setAlarmClock 是框架层,
 *     一致性最好. 因此主路径走 setAlarmClock, 同时也保留 openSystemAlarm 直接拉起自带时钟 App.
 */
final class NativeExtras {

    static final String TAG = "HzWater";
    static final String CH_ID = "hz_alarm";
    static final String CH_NAME = "洗衣完成提醒";
    static final int NOTI_ID = 8801;

    private NativeExtras() {
    }

    /* ==================== 定位 ==================== */

    /** 当前是否已拿到定位权限 */
    static boolean hasLocationPerm(Context c) {
        boolean fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    /** 取一次定位: 有缓存且不太旧就直接用, 否则单次请求, 8 秒超时 */
    /* ---------- 支付宝原生支付 ---------- */

    static void aliPay(final MainActivity act, final String reqId, final String orderInfo) {
        if (orderInfo == null || orderInfo.length() == 0) {
            act.deliver(reqId, "{\"ok\":false,\"err\":\"orderInfo empty\"}");
            return;
        }
        /* PayTask 必须主线程构造(内部 new Handler 绑当前 Looper), payV2 阻塞段留子线程. */
        final java.util.concurrent.atomic.AtomicReference<com.alipay.sdk.app.PayTask> ref =
                new java.util.concurrent.atomic.AtomicReference<com.alipay.sdk.app.PayTask>(null);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.alipay.sdk.app.EnvUtils.setEnv(com.alipay.sdk.app.EnvUtils.EnvEnum.ONLINE);
                    /* 手工 aapt2 链没有生成 com.alipay.sdk.R, 用 app 同名资源回填字段值. */
                    com.alipay.sdk.R.string.alipay_standard_sdk_loading =
                            act.getResources().getIdentifier("alipay_standard_sdk_loading", "string", act.getPackageName());
                    com.alipay.sdk.R.string.alipay_standard_sdk_loading_go_auth =
                            act.getResources().getIdentifier("alipay_standard_sdk_loading_go_auth", "string", act.getPackageName());
                    com.alipay.sdk.R.string.alipay_standard_sdk_loading_go_pay =
                            act.getResources().getIdentifier("alipay_standard_sdk_loading_go_pay", "string", act.getPackageName());
                    com.alipay.sdk.R.string.alipay_standard_sdk_loading_pay =
                            act.getResources().getIdentifier("alipay_standard_sdk_loading_pay", "string", act.getPackageName());
                    ref.set(new com.alipay.sdk.app.PayTask(act));
                } catch (Throwable t) {
                    android.util.Log.d("HzWater", "PayTask init fail: " + t);
                }
                ready.countDown();
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.util.Log.d("HzWater", "aliPay thread start, orderInfo len=" + orderInfo.length());
                    ready.await();
                    com.alipay.sdk.app.PayTask task = ref.get();
                    if (task == null) {
                        act.deliver(reqId, "{\"ok\":false,\"err\":\"PayTask init failed\"}");
                        return;
                    }
                    final java.util.Map<String, String> r =
                            task.payV2(orderInfo, false); /* true(自弹 loading)与外部线程嵌套会卡 */
                    android.util.Log.d("HzWater", "aliPay payV2 returned");
                    String status = r.get("resultStatus");
                    String result = r.get("result");
                    String memo = r.get("memo");
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"ok\":").append("9000".equals(status)).append(",");
                    sb.append("\"status\":\"").append(status == null ? "" : status).append("\",");
                    sb.append("\"memo\":\"").append(memo == null ? "" : memo.replace("\"", "'")).append("\"}");
                    act.deliver(reqId, sb.toString());
                    android.util.Log.d("HzWater", "alipay result: " + result);
                } catch (Throwable t) {
                    android.util.Log.d("HzWater", "aliPay exception: " + t);
                    act.deliver(reqId, "{\"ok\":false,\"err\":\"pay exception\"}");
                }
            }
        }).start();
    }

    static void locate(MainActivity act, String reqId) {
        if (!hasLocationPerm(act)) {
            act.deliver(reqId, "{\"ok\":false,\"needPerm\":\"location\",\"err\":\"没有定位权限\"}");
            return;
        }
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            act.deliver(reqId, "{\"ok\":false,\"err\":\"系统没有定位服务\"}");
            return;
        }
        Location best = null;
        long now = System.currentTimeMillis();
        String[] provs = new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (int i = 0; i < provs.length; i++) {
            Location l = null;
            try {
                l = lm.getLastKnownLocation(provs[i]);
            } catch (Exception ignored) {
            }
            if (l == null) continue;
            // 缓存超过 10 分钟就不用, 找店要实时位置
            if (now - l.getTime() > 10 * 60 * 1000L) continue;
            if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
        }
        if (best != null) {
            act.deliver(reqId, locJson(best, true));
            return;
        }
        LocListener lis = new LocListener(act, reqId, lm);
        Handler h = new Handler(Looper.getMainLooper());
        h.post(new LocStart(act, lm, lis));
        h.postDelayed(new LocTimeout(act, reqId, lm, lis), 8000);
    }

    static String locJson(Location l, boolean cached) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true");
        sb.append(",\"lat\":").append(l.getLatitude());
        sb.append(",\"lng\":").append(l.getLongitude());
        sb.append(",\"acc\":").append(l.hasAccuracy() ? l.getAccuracy() : -1f);
        sb.append(",\"provider\":\"").append(l.getProvider() == null ? "" : l.getProvider()).append("\"");
        sb.append(",\"cached\":").append(cached);
        sb.append(",\"t\":").append(l.getTime());
        sb.append("}");
        return sb.toString();
    }

    static class LocStart implements Runnable {
        final MainActivity act;
        final LocationManager lm;
        final LocListener lis;

        LocStart(MainActivity a, LocationManager m, LocListener l) {
            act = a;
            lm = m;
            lis = l;
        }

        @Override
        public void run() {
            String[] provs = new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER};
            for (int i = 0; i < provs.length; i++) {
                try {
                    if (!lm.isProviderEnabled(provs[i])) continue;
                    lm.requestLocationUpdates(provs[i], 0L, 0f, lis, Looper.getMainLooper());
                } catch (Exception e) {
                    Log.w(TAG, "requestLocationUpdates " + provs[i], e);
                }
            }
        }
    }

    static class LocListener implements LocationListener {
        final MainActivity act;
        final String reqId;
        final LocationManager lm;
        boolean done;

        LocListener(MainActivity a, String id, LocationManager m) {
            act = a;
            reqId = id;
            lm = m;
        }

        @Override
        public void onLocationChanged(Location l) {
            if (done || l == null) return;
            done = true;
            stop();
            act.deliver(reqId, locJson(l, false));
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle b) {
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
        }

        void stop() {
            try {
                lm.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    static class LocTimeout implements Runnable {
        final MainActivity act;
        final String reqId;
        final LocationManager lm;
        final LocListener lis;

        LocTimeout(MainActivity a, String id, LocationManager m, LocListener l) {
            act = a;
            reqId = id;
            lm = m;
            lis = l;
        }

        @Override
        public void run() {
            if (lis.done) return;
            lis.done = true;
            lis.stop();
            act.deliver(reqId, "{\"ok\":false,\"err\":\"定位超时, 请确认已开定位并允许本应用\"}");
        }
    }

    /* ==================== 闹钟 ==================== */

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /**
     * 设定一个系统级闹钟.
     *
     * @param triggerMs 触发时刻(毫秒时间戳), 必须 > 现在
     * @param title     闹钟标题(锁屏显示)
     * @param text      通知正文
     * @param tag       同一 tag 重复设置会覆盖
     * @return 描述结果的 JSON
     */
    static String setAlarm(Context act, long triggerMs, String title, String text, String tag) {
        long now = System.currentTimeMillis();
        if (triggerMs <= now) return "{\"ok\":false,\"err\":\"时间已过\"}";
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return "{\"ok\":false,\"err\":\"系统没有闹钟服务\"}";

        ensureChannel(act);

        int rc = tag == null ? 1 : Math.abs(tag.hashCode() % 100000);
        Intent fire = new Intent(act, AlarmReceiver.class);
        fire.setAction("com.hz.water.ALARM");
        fire.putExtra("title", title == null ? "洗衣完成" : title);
        fire.putExtra("text", text == null ? "" : text);
        fire.putExtra("tag", tag == null ? "" : tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(act, rc, fire, flags);

        Intent show = new Intent(act, MainActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(act, rc + 1, show, flags);

        String mode;
        try {
            if (Build.VERSION.SDK_INT >= 21 && canExact(act)) {
                // 系统闹钟级: 状态栏显示闹钟图标, 锁屏可见, 不被 Doze 冻结
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
                mode = "alarmClock";
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
                mode = "exact";
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi);
                mode = "inexact";
            }
        } catch (SecurityException e) {
            // Android 12+ 未授予"闹钟和提醒"权限
            return "{\"ok\":false,\"needExactAlarm\":true,\"err\":\"需要允许本应用设定闹钟与提醒\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + e.getClass().getSimpleName() + "\"}";
        }
        /* 落盘: 关机重启后 AlarmManager 里的闹钟会全部丢失, 必须有一份能重放的记录 */
        alarmRemember(act, tag, triggerMs, title, text);
        return "{\"ok\":true,\"mode\":\"" + mode + "\",\"at\":" + triggerMs + "}";
    }

    /** 取消某个 tag 的闹钟 */
    static String cancelAlarm(Context act, String tag) {
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return "{\"ok\":false,\"err\":\"系统没有闹钟服务\"}";
        int rc = tag == null ? 1 : Math.abs(tag.hashCode() % 100000);
        Intent fire = new Intent(act, AlarmReceiver.class);
        fire.setAction("com.hz.water.ALARM");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(act, rc, fire, flags);
        am.cancel(pi);
        pi.cancel();
        alarmForget(act, tag);
        return "{\"ok\":true}";
    }

    /* ---------- 闹钟持久化与开机重放 ---------- */

    static final String SP_ALARM = "hz_alarms";
    static final String SP_KEY = "list";

    /** 记一条待重放的闹钟, 形如 "at|tag|title|text" 多行拼接; 同 tag 覆盖 */
    static void alarmRemember(Context c, String tag, long at, String title, String text) {
        try {
            SharedPreferences sp = c.getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
            String old = sp.getString(SP_KEY, "");
            StringBuilder sb = new StringBuilder();
            String key = tag == null ? "" : tag;
            if (old.length() > 0) {
                String[] lines = old.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String ln = lines[i];
                    if (ln.length() == 0) continue;
                    int p1 = ln.indexOf('|');
                    int p2 = ln.indexOf('|', p1 + 1);
                    if (p1 < 0 || p2 < 0) continue;
                    if (ln.substring(p1 + 1, p2).equals(key)) continue;
                    sb.append(ln).append('\n');
                }
            }
            sb.append(at).append('|').append(key).append('|')
              .append(oneLine(title)).append('|').append(oneLine(text)).append('\n');
            sp.edit().putString(SP_KEY, sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void alarmForget(Context c, String tag) {
        try {
            SharedPreferences sp = c.getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
            String old = sp.getString(SP_KEY, "");
            if (old.length() == 0) return;
            StringBuilder sb = new StringBuilder();
            String key = tag == null ? "" : tag;
            String[] lines = old.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.length() == 0) continue;
                int p1 = ln.indexOf('|');
                int p2 = ln.indexOf('|', p1 + 1);
                if (p1 < 0 || p2 < 0) continue;
                if (ln.substring(p1 + 1, p2).equals(key)) continue;
                sb.append(ln).append('\n');
            }
            sp.edit().putString(SP_KEY, sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static String oneLine(String v) {
        if (v == null) return "";
        return v.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * 开机后把还没到点的闹钟重新登记一遍, 返回成功条数.
     * 已过期的不补响 -- 补响只会让用户在错误的时刻被吓一跳.
     */
    static int replayAlarms(Context c) {
        int n = 0;
        try {
            SharedPreferences sp = c.getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
            String old = sp.getString(SP_KEY, "");
            if (old.length() == 0) return 0;
            long now = System.currentTimeMillis();
            StringBuilder keep = new StringBuilder();
            String[] lines = old.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.length() == 0) continue;
                String[] f = ln.split("\\|", -1);
                if (f.length < 4) continue;
                long at;
                try {
                    at = Long.parseLong(f[0]);
                } catch (Exception e) {
                    continue;
                }
                if (at <= now) continue;
                String r = setAlarm(c, at, f[2], f[3], f[1]);
                if (r.indexOf("\"ok\":true") >= 0) n++;
                keep.append(ln).append('\n');
            }
            sp.edit().putString(SP_KEY, keep.toString()).apply();
        } catch (Exception ignored) {
        }
        return n;
    }

    /**
     * 直接往手机自带的时钟 App 里塞一条闹钟(标准 SET_ALARM Intent).
     * 小米/华为/OPPO/vivo/三星 都实现了这个 Intent; 失败时返回 fallback=true,
     * 由页面改用 setAlarmClock 那条路.
     */
    static String openSystemAlarm(MainActivity act, int hour, int minute, String message) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, message == null ? "洗衣完成" : message);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"fallback\":true,\"err\":\"系统时钟没有响应, 已改用应用内闹钟\"}";
        }
    }

    /** 打开系统"闹钟和提醒"授权页(Android 12+) */
    static String requestExactAlarm(MainActivity act) {
        if (Build.VERSION.SDK_INT < 31) return "{\"ok\":true,\"already\":true}";
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"打不开授权页\"}";
        }
    }

    /** 打开系统"安装未知应用"授权页(装更新包用) */
    static String requestInstallPerm(MainActivity act) {
        if (Build.VERSION.SDK_INT < 26) return "{\"ok\":true,\"already\":true}";
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"打不开授权页\"}";
        }
    }

    static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /* ==================== 通知 ==================== */

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("洗衣机洗完之后提醒");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /** 立刻发一条通知(闹钟触发时由 AlarmReceiver 调用) */
    static void notify(MainActivity act, String title, String text) {
        ensureChannel(act);
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(act, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(act, 77, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(act, CH_ID);
        } else {
            b = new Notification.Builder(act);
        }
        b.setContentTitle(title == null ? "洗衣完成" : title)
                .setContentText(text == null ? "" : text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        nm.notify(NOTI_ID, b.build());
    }

    /** 权限现状, 供页面画"初始化权限"清单 */
    static String permState(Context c) {
        boolean cam = c.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean loc = hasLocationPerm(c);
        boolean noti = true;
        if (Build.VERSION.SDK_INT >= 33) {
            noti = c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        boolean exact = canExact(c);
        boolean inst = canInstall(c);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true");
        sb.append(",\"camera\":").append(cam);
        sb.append(",\"location\":").append(loc);
        sb.append(",\"notification\":").append(noti);
        sb.append(",\"exactAlarm\":").append(exact);
        sb.append(",\"install\":").append(inst);
        sb.append(",\"sdk\":").append(Build.VERSION.SDK_INT);
        sb.append("}");
        return sb.toString();
    }

    /** 一次性请求运行时权限, 只请求还没给的 */
    static void requestPerms(MainActivity act) {
        List<String> want = new ArrayList<String>();
        if (act.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.CAMERA);
        }
        if (!hasLocationPerm(act)) {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION);
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        if (want.isEmpty()) return;
        act.requestPermissions(want.toArray(new String[0]), MainActivity.REQ_PERMS);
    }
}
