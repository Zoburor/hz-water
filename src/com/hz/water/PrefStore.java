package com.hz.water;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 页面状态的第二份存储(原生 SharedPreferences).
 *
 * 为什么需要它:
 *  WebView 的 localStorage 落在 /data/data/com.hz.water 内, 一旦 adb uninstall
 *  或用户"清除数据", 整个目录被删, 登录态(手机号 + token)与协议同意态一起消失.
 *  账号是验证码登录, 每日发码次数按手机号计数, 丢一次登录态就要用户再花一次额度,
 *  所以必须有一份不随 WebView 数据目录一起消失的副本.
 *
 * 存什么: 只镜像页面里那几个"丢了要用户付代价"的 key, 不做通用 KV.
 *   hz_acc_v5     登录态(手机号 + token + userId)
 *   hz_terms_v6   协议同意态(丢了要重新勾一遍)
 *   hz_alarms_v6  页面侧提醒列表(原生另有 hz_alarms 供开机重放, 这里是给界面显示用)
 *
 * 注意: SharedPreferences 本身仍在 /data/data 下, 对 uninstall 同样不免疫.
 *  它挡的是"WebView 数据被单独清掉"与"页面 localStorage 写失败"这两类;
 *  真正防 uninstall 要靠导出文件, 见 AccDump 的说明.
 *  但实测 MIUI 上 adb install -r 不做数据迁移, uninstall 才删 -- 因此策略是
 *  一律 install -r, 不 uninstall; 这份副本用来兜住 WebView 层偶发丢数据.
 */
final class PrefStore {

    static final String SP_NAME = "hz_mirror";

    /** 允许镜像的 key 白名单; 不在名单里的写入直接忽略, 避免把日志等垃圾复制一份 */
    static final String[] KEYS = new String[]{
            "hz_acc_v5",
            "hz_terms_v6",
            "hz_alarms_v6"
    };

    static boolean allowed(String key) {
        if (key == null) return false;
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return true;
        }
        return false;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    static void put(Context c, String key, String val) {
        if (!allowed(key)) return;
        try {
            sp(c).edit().putString(key, val == null ? "" : val).apply();
        } catch (Throwable e) {
            Log.i("HzWater", "pref put failed: " + key + " " + e);
        }
    }

    /** 没有副本时返回空串(不是 null, 方便 JS 直接判长度) */
    static String get(Context c, String key) {
        if (!allowed(key)) return "";
        try {
            return sp(c).getString(key, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 一次性把所有副本交出去, 形如 {"hz_acc_v5":"{...}","hz_terms_v6":"{...}"}.
     * key 存在但值为空串时也照样输出, 让页面能区分"没有副本"与"副本是空".
     */
    static String all(Context c) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < KEYS.length; i++) {
            String k = KEYS[i];
            String v = get(c, k);
            if (i > 0) sb.append(',');
            sb.append(quote(k)).append(':').append(quote(v));
        }
        sb.append('}');
        return sb.toString();
    }

    /** 只有页面真的写过才覆盖; 用 remove 语义显式清空, 避免用空串误删 */
    static void clear(Context c, String key) {
        if (!allowed(key)) return;
        try {
            sp(c).edit().remove(key).apply();
        } catch (Throwable e) {
        }
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
