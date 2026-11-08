package com.hz.water;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后把闹钟重新登记一遍.
 *
 * AlarmManager 里的闹钟全部存在内存里, 关机/重启就没了. 用户设的"洗衣完成提醒"
 * 如果正好赶上手机重启, 就等于白设. 这里在 RECEIVE_BOOT_COMPLETED 之后重放一遍.
 *
 * 只重放还没过期的; 已经过期的直接丢弃, 不补响.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        int n = NativeExtras.replayAlarms(c);
        Log.i("HzWater", "boot: 重放闹钟 " + n + " 条");
    }
}
