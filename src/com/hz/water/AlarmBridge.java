package com.hz.water;

/**
 * 闹钟与页面之间的事件管道.
 *
 * 闹钟界面可能在前台也可能在锁屏上被点掉, 此时页面是冻结的, 没法直接回调 JS.
 * 所以这里只记一条"最后一次闹钟操作", 页面下次唤醒时用 HzNative.alarmEvent() 取走.
 *
 * 只保留最后一条: 闹钟是瞬时事件, 攒队列没有意义.
 */
final class AlarmBridge {

    private AlarmBridge() {
    }

    /** 形如 "off:<tag>" 或 "snooze:<tag>"; 空串表示没有待取事件 */
    static volatile String pending = "";

    static void removed(Object ctx, String tag) {
        pending = "off:" + (tag == null ? "" : tag);
    }

    static void snoozed(Object ctx, String tag) {
        pending = "snooze:" + (tag == null ? "" : tag);
    }

    /** 取走事件, 取完即清 */
    static String take() {
        String p = pending;
        pending = "";
        return p == null ? "" : p;
    }
}
