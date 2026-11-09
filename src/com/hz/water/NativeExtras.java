package com.hz.water;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.Locale;

/**
 * 原生扩展能力: 定位, 系统闹钟, 通知, 权限协商, 安装未知应用授权.
 *
 * 约束与 MainActivity 相同: 全部静态嵌套类, 不用匿名内部类
 * (javac 21 的 NestHost 属性会让 build-tools 34 的 D8 8.2.2 抛 NPE).
 *
 * 闹钟两条通道, 分工如下(用户要求: 系统闹钟优先, 因为系统闹钟不会被后台清理):
 *
 *  通道一 系统时钟 App(SET_ALARM -> openSystemAlarm):
 *    闹钟由厂商时钟 App 持有, 到点由系统服务叫醒它的界面与铃声, 与本应用进程是否活着无关,
 *    这是"后台被清也不影响响铃"的唯一可靠路径. 缺点: 各品牌实参支持不一(有的认 EXTRA_MESSAGE
 *    但不认 EXTRA_SKIP_UI, 有的包可见性没配就查不到), 所以要先探测再挑包, 并且要报真实结果.
 *
 *  通道二 本应用 AlarmManager(setAlarmClock -> setAlarm):
 *    1. setAlarmClock 是系统闹钟级 API, 会在状态栏常驻闹钟图标, 锁屏也显示, 与自带闹钟同档;
 *    2. 受 Doze 与厂商冻结影响比 setExactAndAllowWhileIdle 小, 但进程被杀后仍可能不响;
 *    3. 作为系统时钟通道不可用(或用户拒绝弹界面)时的兜底.
 *
 * 两条通道由页面按"系统时钟是否登记成功"决定, 原生只负责把每条通道的结果如实返回.
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
        /* 冷启动时(尤其刚开机)广播默认排在后台队列, 到点可能晚上几十秒才派发;
           FOREGROUND 让它走前台队列. 照抄 DeskClock 的 AlarmManagerStateChangeScheduler. */
        fire.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
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
        /* FLAG_NO_CREATE: 只取回已经存在的那一个. 用 UPDATE_CURRENT 会顺手造一个
           新的空 PendingIntent 再 cancel 掉, 等于什么都没取消, 而真正的闹钟还在. */
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(act, rc, fire, flags);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
        alarmForget(act, tag);
        return "{\"ok\":true}";
    }

    /* ---------- 闹钟持久化与开机重放 ---------- */

    static final String SP_ALARM = "hz_alarms";
    static final String SP_KEY = "list";

    /** 已过点但在这么多毫秒内的, 仍然补响一次; 更久的不补(免得半夜被白天的旧提醒吓一跳) */
    static final long ALARM_FIRE_BUFFER = 60 * 1000L;

    /**
     * 闹钟记录的 SharedPreferences.
     *
     * 必须放在 device-protected storage, 不能用普通的 credential-protected:
     * BootReceiver 是 directBootAware 的, 直接开机(用户还没解锁)时收到
     * LOCKED_BOOT_COMPLETED 就去读这份记录; 那一刻 credential-protected 存储
     * 是加锁的, 读出来是空的, 重放 0 条 -- 等于开机到解锁这段时间闹钟没登记.
     * 闹钟记录里只有时间与标题, 不含敏感信息, 放设备级存储没有额外风险.
     */
    static SharedPreferences spAlarm(Context c) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                SharedPreferences dps = c.createDeviceProtectedStorageContext()
                        .getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
                /* 老版本把记录存在 credential-protected 里, 升级后第一次访问搬过来 */
                if (dps.getString(SP_KEY, "").length() == 0) {
                    SharedPreferences old = c.getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
                    String v = old.getString(SP_KEY, "");
                    if (v.length() > 0) {
                        dps.edit().putString(SP_KEY, v).apply();
                        old.edit().remove(SP_KEY).apply();
                        Log.i("HzWater", "alarm store migrated to device-protected");
                    }
                }
                return dps;
            } catch (Throwable ignored) {
            }
        }
        return c.getSharedPreferences(SP_ALARM, Context.MODE_PRIVATE);
    }

    /** 记一条待重放的闹钟, 形如 "at|tag|title|text" 多行拼接; 同 tag 覆盖 */
    static void alarmRemember(Context c, String tag, long at, String title, String text) {
        try {
            SharedPreferences sp = spAlarm(c);
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
            SharedPreferences sp = spAlarm(c);
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
     * 时区变了之后对已存闹钟的处理.
     *
     * 结论先说: **不重算 epoch, 只做体检与日志**. 网上很多"时区变更要重算"的经验
     * 来自那些把闹钟存成"日历的某天 7 点"的 App(DeskClock 就是这么存的, 它存
     * Calendar), 那类记录在换时区后必须重新解释"7 点"是哪一刻. 我们这里不适用:
     *
     *   本应用的提醒全部来自账单的"还剩多少分钟 / 完成时刻", 设的时候就已经把
     *   它换算成绝对时刻(epoch)存下来了. 洗衣完成是一个物理事件, 2 小时后就是
     *   2 小时后, 跟手机改成哪个时区没有关系. 这时候要是照着墙上时间去"重算",
     *   反而会把提醒挪到错误的时刻 -- 比如飞一趟跨时区航班, 提醒会因为时区前后
     *   跳 4 小时, 而衣服其实还是原来那个时间洗好.
     *
     * 所以这里只返回"当前还有几条待响", 供 BootReceiver 打一行日志核对; 真正的
     * 重新登记由随后调用的 replayAlarms() 完成(时区变更不影响 RTC 闹钟的触发时刻,
     * 但重放一次能兜住某些 ROM 在时区切换时清空 AlarmManager 的毛病).
     */
    static int fixAlarmsForTimezone(Context c) {
        int pending = 0;
        try {
            String old = spAlarm(c).getString(SP_KEY, "");
            long now = System.currentTimeMillis();
            if (old.length() == 0) return 0;
            String[] lines = old.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.length() == 0) continue;
                int p1 = ln.indexOf('|');
                if (p1 <= 0) continue;
                try {
                    long at = Long.parseLong(ln.substring(0, p1));
                    if (at > now - ALARM_FIRE_BUFFER) pending++;
                } catch (Exception ignored) {
                }
            }
            Log.i("HzWater", "timezone now " + java.util.TimeZone.getDefault().getID()
                    + ", pending alarms " + pending + " (epoch 记录不重算)");
        } catch (Throwable t) {
            Log.w("HzWater", "fixAlarmsForTimezone failed: " + t);
        }
        return pending;
    }

    /**
     * 开机后把还没到点的闹钟重新登记一遍, 返回成功条数.
     * 已过期的不补响 -- 补响只会让用户在错误的时刻被吓一跳.
     */
    static int replayAlarms(Context c) {
        int n = 0;
        try {
            SharedPreferences sp = spAlarm(c);
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
                /* 刚过点一点点(比如重启花了一分钟)的仍然补响, 更早的丢掉:
                   半夜被白天旧提醒吓一跳比漏响更糟, 但差几十秒就静默更像 bug. */
                if (at <= now - ALARM_FIRE_BUFFER) continue;
                long at2 = at < now ? now + 3000L : at;
                String r = setAlarm(c, at2, f[2], f[3], f[1]);
                if (r.indexOf("\"ok\":true") >= 0) n++;
                keep.append(ln).append('\n');
            }
            sp.edit().putString(SP_KEY, keep.toString()).apply();
        } catch (Exception ignored) {
        }
        return n;
    }

    /**
     * 往手机自带的时钟 App 里登记一条闹钟(标准 SET_ALARM Intent).
     *
     * 为什么这条通道优先: 系统时钟 App 由厂商自己保活, 到点由系统服务唤醒,
     * 不受本应用被后台清理的影响. 本应用自己的 AlarmManager 通道一旦被厂商
     * 杀掉进程就可能不响, 所以两条通道都要走, 系统时钟优先.
     *
     * 各品牌差异都在这一层处理:
     *   1) Android 11+ 包可见性: 必须先查 AndroidManifest 里的 <queries>
     *      (SET_ALARM intent + 各厂商时钟包名), 否则 resolveActivity 恒 null,
     *      表现就是"这个手机没有时钟 App" -- 这是过去误判成"各品牌不支持"的真因.
     *   2) 参数支持程度: 有的时钟只认 EXTRA_HOUR/EXTRA_MINUTES, 有的还认
     *      EXTRA_MESSAGE / EXTRA_DAYS / EXTRA_SKIP_UI. 我们只塞通用的时分与提示语,
     *      不塞 EXTRA_DAYS(一旦塞了会被当成"每周重复", 单次闹钟变成天天响).
     *   3) 静默程度: EXTRA_SKIP_UI=true 时多数原厂时钟会直接建好不弹界面,
     *      少数会忽略该 flag 仍然弹界面. 先试 true, 抛异常(少数老 ROM 不认)
     *      再退 false, 让用户自己点一下保存.
     *
     * 返回值给页面判断用:
     *   ok / silent(是否请求了静默) / pkg(命中哪个时钟) / label / sdk / err
     */
    static String openSystemAlarm(MainActivity act, int hour, int minute, String message) {
        int hh = ((hour % 24) + 24) % 24;
        int mm = ((minute % 60) + 60) % 60;
        String msg = (message == null || message.length() == 0) ? "洗衣完成" : message;

        ResolveInfo ri = pickAlarmApp(act);
        if (ri == null) {
            /* 一个都没命中: 包可见性没配, 或者这台机器真的没有时钟 App */
            return "{\"ok\":false,\"silent\":false,\"none\":true"
                    + ",\"err\":\"这台手机没有响应系统闹钟的时钟 App\"}";
        }

        String pkg = ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        String label = "";
        try {
            label = String.valueOf(ri.loadLabel(act.getPackageManager()));
        } catch (Exception ignored) {
        }

        /* 先试静默, 不认 SKIP_UI 的老 ROM 会抛异常, 再退到弹界面 */
        Exception first = null;
        try {
            fireAlarmIntent(act, hh, mm, msg, true);
            return "{\"ok\":true,\"silent\":true,\"pkg\":" + PrefStore.quote(pkg)
                    + ",\"label\":" + PrefStore.quote(label)
                    + ",\"hour\":" + hh + ",\"minute\":" + mm + "}";
        } catch (Exception e) {
            first = e;
            Log.w(TAG, "silent SET_ALARM failed on " + pkg + ": " + e);
        }
        try {
            fireAlarmIntent(act, hh, mm, msg, false);
            return "{\"ok\":true,\"silent\":false,\"pkg\":" + PrefStore.quote(pkg)
                    + ",\"label\":" + PrefStore.quote(label)
                    + ",\"hour\":" + hh + ",\"minute\":" + mm + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"silent\":false,\"pkg\":" + PrefStore.quote(pkg)
                    + ",\"err\":" + PrefStore.quote("系统时钟拒绝接收: " + String.valueOf(e))
                    + ",\"first\":" + PrefStore.quote(String.valueOf(first)) + "}";
        }
    }

    /** 真正发 SET_ALARM; skipUi=true 表示请求时钟 App 静默建好不弹界面 */
    private static void fireAlarmIntent(MainActivity act, int hour, int minute,
                                       String msg, boolean skipUi) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, hour);
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        i.putExtra(AlarmClock.EXTRA_MESSAGE, msg);
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }

    /**
     * 找一台能接 SET_ALARM 的时钟 App.
     * queryIntentActivities 比 resolveActivity 好: 能拿到全部候选, 有的机器上
     * 默认的 resolveActivity 会落到某个不认参数的壳上, 我们可以按包名优先挑原厂时钟.
     */
    static ResolveInfo pickAlarmApp(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent probe = new Intent(AlarmClock.ACTION_SET_ALARM);
        probe.addCategory(Intent.CATEGORY_DEFAULT);
        List<ResolveInfo> all = null;
        try {
            all = pm.queryIntentActivities(probe, 0);
        } catch (Exception e) {
            Log.w(TAG, "queryIntentActivities(SET_ALARM) failed: " + e);
        }
        if (all == null || all.isEmpty()) return null;

        /* 原厂时钟优先: 它们对本 Intent 的参数支持最完整, 静默登记成功率最高 */
        String[] prefer = new String[]{
                "com.miui.clock",
                "com.android.deskclock",
                "com.huawei.deskclock",
                "com.coloros.alarmclock",
                "com.oplus.alarmclock",
                "com.vivo.alarmclock",
                "com.bbk.clock",
                "com.sec.android.app.clockpackage",
                "com.meizu.flyme.clock",
                "com.google.android.deskclock"
        };
        for (int p = 0; p < prefer.length; p++) {
            for (int i = 0; i < all.size(); i++) {
                ResolveInfo ri = all.get(i);
                if (ri.activityInfo != null && prefer[p].equals(ri.activityInfo.packageName)) {
                    return ri;
                }
            }
        }
        return all.get(0);
    }

    /** 页面诊断用: 报告这台机器上到底有哪些 App 能接系统闹钟 */
    static String alarmAppsJson(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent probe = new Intent(AlarmClock.ACTION_SET_ALARM);
        probe.addCategory(Intent.CATEGORY_DEFAULT);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"apps\":[");
        int n = 0;
        try {
            List<ResolveInfo> all = pm.queryIntentActivities(probe, 0);
            if (all != null) {
                for (int i = 0; i < all.size() && n < 20; i++) {
                    ResolveInfo ri = all.get(i);
                    if (ri.activityInfo == null) continue;
                    String label = "";
                    try {
                        label = String.valueOf(ri.loadLabel(pm));
                    } catch (Exception ignored) {
                    }
                    if (n > 0) sb.append(',');
                    sb.append("{\"pkg\":").append(PrefStore.quote(ri.activityInfo.packageName))
                            .append(",\"label\":").append(PrefStore.quote(label)).append('}');
                    n++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "alarmAppsJson failed: " + e);
        }
        sb.append("],\"count\":").append(n).append('}');
        return sb.toString();
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
    /* ---------- 厂商自启动白名单 ---------- */

    /**
     * 各厂商"自启动 / 后台运行"设置页的 ComponentName 表.
     *
     * 逐字取自 judemanutd/AutoStarter 的 AutoStartPermissionHelper.kt (该库就是
     * 专门做这件事的开源项目). 光靠 Intent action 找不到这些页面 -- 它们是各厂
     * 自己加的私有 Activity, 只有写出全类名才打得开.
     *
     * 魅族故意不填: AutoStarter 全文没有 meizu, 本机又无法访问 GitHub 去核对别的
     * 清单库, 社区流传的 com.meizu.safe / SmartBGActivity 无从验证. 硬编码一个
     * 没验证过的组合, 在用户机上只会得到一个空白页或崩溃, 不如老实走应用详情页
     * 兜底并在页面上写清楚要手动点哪里.
     */
    private static final String[][] AUTO_START_PAGES = {
            {"xiaomi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"redmi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"poco", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"huawei", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"huawei", "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"honor", "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"oppo", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"oppo", "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"oppo", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"realme", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"vivo", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"vivo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"vivo", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"iqoo", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"samsung", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"samsung", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"samsung", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"},
            {"oneplus", "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
    };

    static String autoStartBrand() {
        String m = Build.MANUFACTURER;
        return m == null ? "" : m.toLowerCase(Locale.US);
    }

    /**
     * 打开本机厂商的自启动白名单页.
     *
     * 探测方式刻意不用 resolveActivity: targetSdk 30+ 的包可见性会把没在 <queries>
     * 里声明过的包判成不存在, 连能打开的机型也会被当成"没有这一页". 所以直接
     * startActivity, 打不开就 catch 掉试下一条, 全都不行再退应用详情页.
     *
     * @return 给页面看的 JSON: ok / opened(brand 或 details) / brand / tried
     */
    static String openAutoStartPage(Context c) {
        String brand = autoStartBrand();
        int tried = 0;
        for (int i = 0; i < AUTO_START_PAGES.length; i++) {
            if (!AUTO_START_PAGES[i][0].equals(brand)) continue;
            tried++;
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(AUTO_START_PAGES[i][1], AUTO_START_PAGES[i][2]));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
                Log.i("HzWater", "autostart page opened: " + AUTO_START_PAGES[i][1]
                        + "/" + AUTO_START_PAGES[i][2]);
                return "{\"ok\":true,\"opened\":\"brand\",\"brand\":" + PrefStore.quote(brand)
                        + ",\"tried\":" + tried + "}";
            } catch (Throwable t) {
                Log.w("HzWater", "autostart try failed: " + AUTO_START_PAGES[i][1]
                        + "/" + AUTO_START_PAGES[i][2] + " -> " + t);
            }
        }
        /* 该品牌没有表项(比如魅族), 或者表里每一条都打不开: 退到应用详情页.
           那里一定有"自启动/后台运行/省电策略"的入口, 用户自己能找到. */
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(android.net.Uri.parse("package:" + c.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
            return "{\"ok\":true,\"opened\":\"details\",\"brand\":" + PrefStore.quote(brand)
                    + ",\"tried\":" + tried + "}";
        } catch (Throwable t2) {
            return "{\"ok\":false,\"brand\":" + PrefStore.quote(brand)
                    + ",\"tried\":" + tried
                    + ",\"err\":" + PrefStore.quote(String.valueOf(t2)) + "}";
        }
    }

    /**
     * 全屏通知与精确闹钟这两个"到点能不能弹出来"的开关状态, 供页面一次性展示.
     *
     * 分开报的原因: 这两件事在不同 ROM 上坏的地方不一样. 精确闹钟在国产 ROM 上
     * 基本都放行, 但全屏通知在 Android 14+ 被系统统一收紧; 反过来小米这类ROM
     * 即使 USE_FULL_SCREEN_INTENT 的 appop 被强制 ignore, 精确闹钟照样能用.
     * 页面上分开显示, 用户才知道该去点哪一个.
     */
    static String alarmHealth(Context c) {
        boolean fsi;
        try {
            fsi = NotificationRelay.canFullScreen(c);
        } catch (Throwable t) {
            fsi = false;
        }
        boolean exact;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                exact = false;
            } else if (Build.VERSION.SDK_INT >= 31) {
                exact = am.canScheduleExactAlarms();
            } else {
                exact = true;
            }
        } catch (Throwable t) {
            exact = false;
        }
        String brand = autoStartBrand();
        return "{\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"fullScreen\":" + fsi
                + ",\"exactAlarm\":" + exact
                + ",\"brand\":" + PrefStore.quote(brand)
                + ",\"autostartKnown\":" + (hasAutoStartPage(brand) ? "true" : "false")
                + "}";
    }

    static boolean hasAutoStartPage(String brand) {
        if (brand == null) return false;
        for (int i = 0; i < AUTO_START_PAGES.length; i++) {
            if (AUTO_START_PAGES[i][0].equals(brand)) return true;
        }
        return false;
    }
}
