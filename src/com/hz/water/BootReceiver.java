package com.hz.water;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * 开机/换时间/换时区/权限变更后, 把闹钟重新登记一遍.
 *
 * AlarmManager 里的闹钟全部存在内存里, 关机/重启就没了. 用户设的"洗衣完成提醒"
 * 如果正好赶上手机重启, 就等于白设. 这里在开机之后重放一遍.
 *
 * 为什么必须 directBootAware + LOCKED_BOOT_COMPLETED(照抄 DeskClock 的 AlarmInitReceiver):
 *   直接开机(用户还没输密码解锁)时, 系统先发 LOCKED_BOOT_COMPLETED, 解锁后才发
 *   BOOT_COMPLETED. 只监听后者的话, 从开机到解锁这段时间闹钟根本没登记;
 *   用户要是恰好在这段时间里到点(比如凌晨重启、早上 7 点该响), 就彻底不响.
 *   代价是闹钟记录必须存在 device-protected storage 里, 否则那个阶段读不到 --
 *   见 NativeExtras.spAlarm().
 *
 * 分裂处理:
 *   TIMEZONE_CHANGED 要重算墙上时间(我们存的是 epoch, 时区一变换了时区的人看到的
 *   "7 点"就不再是那个 7 点), DeskClock 存 Calendar 所以能合并处理, 我们不能.
 *
 * 本文件不用匿名内部类(javac 21 的 NestHost 会让 D8 8.2.2 抛 NPE).
 */
public class BootReceiver extends BroadcastReceiver {

    static final String ACTION_EXACT_ALARM_GRANTED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;

        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean tz = Intent.ACTION_TIMEZONE_CHANGED.equals(a);
        boolean tset = Intent.ACTION_TIME_CHANGED.equals(a);
        boolean repl = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        boolean grant = ACTION_EXACT_ALARM_GRANTED.equals(a);

        if (!boot && !tz && !tset && !repl && !grant) return;

        Log.i("HzWater", "boot-ish receive: " + a);

        /* onReceive 只有 10 秒左右, 重放要读盘 + 多条 setAlarm, 必须 goAsync 挪到后台.
           goAsync 期间系统会替我们保活, 但保活不等于给 CPU, 所以还要自己拿 partial wakelock. */
        final PendingResult pr = goAsync();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl;
        if (pm == null) {
            wl = null;
        } else {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hz:bootreplay");
            wl.setReferenceCounted(false);
            try {
                wl.acquire(60 * 1000L);
            } catch (Throwable t) {
                Log.w("HzWater", "boot wakelock failed: " + t);
            }
        }
        final Context app = c.getApplicationContext();
        final boolean doTz = tz;
        final boolean doGrant = grant;
        /* 重活不能留在广播线程上, 起一个普通线程做完再 finish */
        Thread t = new Thread(new ReplayTask(app, pr, wl, doTz, doGrant));
        t.setName("hz-boot-replay");
        t.start();
    }

    /**
     * 后台重放任务.
     *
     * 时区分支: 先按新时区把已存的 epoch 重算成"同一个墙上时刻"再重放;
     * 其余分支直接重放(setAlarm 内部会判断 at 是否已过, 60 秒内的补响).
     */
    static class ReplayTask implements Runnable {
        final Context ctx;
        final BroadcastReceiver.PendingResult pr;
        final PowerManager.WakeLock wl;
        final boolean fixTz;
        final boolean upgradeExact;

        ReplayTask(Context c, BroadcastReceiver.PendingResult p,
                   PowerManager.WakeLock w, boolean tz, boolean grant) {
            ctx = c;
            pr = p;
            wl = w;
            fixTz = tz;
            upgradeExact = grant;
        }

        @Override
        public void run() {
            int n = 0;
            try {
                if (fixTz) {
                    int f = NativeExtras.fixAlarmsForTimezone(ctx);
                    Log.i("HzWater", "boot: 时区变更, 重算 " + f + " 条");
                }
                n = NativeExtras.replayAlarms(ctx);
                Log.i("HzWater", "boot: 重放闹钟 " + n + " 条"
                        + (upgradeExact ? " (精确闹钟权限已授, 已升级)" : ""));
            } catch (Throwable t) {
                Log.w("HzWater", "boot replay failed: " + t);
            } finally {
                try {
                    if (wl != null && wl.isHeld()) wl.release();
                } catch (Throwable ignored) {
                }
                try {
                    if (pr != null) pr.finish();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
