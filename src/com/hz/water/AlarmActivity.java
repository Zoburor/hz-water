package com.hz.water;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用内置的全屏闹钟界面.
 *
 * 职责划分(实测踩过坑之后定下的):
 *  界面只负责"显示"-- 大号时间 / 标题 / 正文 / 两个按钮;
 *  响铃 / 震动 / 亮屏 WakeLock 全部在 AlarmService 里做.
 *
 * 为什么声音不放这里:
 *  这台机器(MIUI, Android 14)上 `cmd appops get com.hz.water USE_FULL_SCREEN_INTENT`
 *  的 Uid mode 恒为 ignore, dumpsys notification 里我方通知带
 *  `FSI_REQUESTED_BUT_DENIED` 且 `fullscreenIntent=null`; 后台 startActivity 又被
 *  `Background activity launch blocked! ... resultIfPiCreatorAllowsBal: BAL_BLOCK` 拦死.
 *  也就是说这个界面可能根本不出现 -- 界面若同时负责发声, 用户就什么都听不到.
 *  所以发声交给前台服务, 界面能弹出来就是锦上添花.
 *
 * 仍保留的三条硬约束:
 *  1. setShowWhenLocked + setTurnScreenOn(API 27+) 才能盖在锁屏上;
 *  2. 界面自己持 FLAG_KEEP_SCREEN_ON, 显示期间不熄屏;
 *  3. 用户点关闭 / 稍后 / 返回键, 都必须把服务停掉, 否则铃声会一直响.
 *
 * 本文件不用匿名内部类(javac 21 的 NestHost 会让 D8 8.2.2 抛 NPE).
 */
public class AlarmActivity extends Activity {

    static final String EX_TITLE = "title";
    static final String EX_TEXT = "text";
    static final String EX_TAG = "tag";
    /** 5 分钟后再响的毫秒数 */
    static final long SNOOZE_MS = 5 * 60 * 1000L;

    boolean stopped = false;
    final Handler ui = new Handler(Looper.getMainLooper());
    TextView clock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        String title = it.getStringExtra(EX_TITLE);
        String text = it.getStringExtra(EX_TEXT);
        final String tag = it.getStringExtra(EX_TAG);
        if (title == null || title.length() == 0) title = "洗衣完成";

        /* 音量键调"闹钟"音量, 不是铃声音量. 少了这一行, 用户在这个界面按音量键
           会觉得"怎么调不小", 因为调的是另一个流, 而闹钟音量在响的那个流没动. */
        setVolumeControlStream(AudioManager.STREAM_ALARM);

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(0xFF0B0F14);
            w.setNavigationBarColor(0xFF0B0F14);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        root.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(32));

        TextView belt = new TextView(this);
        belt.setText("闹钟");
        belt.setTextColor(0xFF8B949E);
        belt.setTextSize(15);
        belt.setGravity(Gravity.CENTER);
        col.addView(belt, lpMatchWrap());

        clock = new TextView(this);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setTextColor(0xFFFFFFFF);
        clock.setGravity(Gravity.CENTER);
        col.addView(clock, lpMatchWrap());

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(0xFFFFFFFF);
        t1.setTextSize(26);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setGravity(Gravity.CENTER);
        t1.setPadding(0, dp(18), 0, 0);
        col.addView(t1, lpMatchWrap());

        TextView t2 = new TextView(this);
        t2.setText(text == null ? "" : text);
        t2.setTextColor(0xFFB9C4CF);
        t2.setTextSize(16);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, dp(10), 0, 0);
        col.addView(t2, lpMatchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(56), 0, 0);

        Button snooze = new Button(this);
        snooze.setText("5 分钟后再响");
        snooze.setTextSize(16);
        row.addView(snooze, lpWeight(dp(56)));

        Button off = new Button(this);
        off.setText("关闭");
        off.setTextSize(16);
        off.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(off, lpWeight(dp(56)));

        col.addView(row, lpMatchWrap());

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        TextView.OnClickListener noop = new ClickNoop();
        off.setOnClickListener(new ClickOff(this, tag));
        snooze.setOnClickListener(new ClickSnooze(this, tag, title, text));
        belt.setOnClickListener(noop);

        ensureService(title, text, tag);
        ui.post(new ClockTick(this));
    }

    LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams lpWeight(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(6), 0, dp(6), 0);
        p.height = h;
        return p;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 确保前台服务在响.
     *
     * 正常情况下 AlarmReceiver 已经把它拉起来了(服务先于界面启动), 这里只做兜底:
     * 若用户直接点通知进来, 或服务因低内存被杀, 界面自己也能把声音补上.
     * 用 AlarmService.running 判重, 避免服务已在响时再 start 一次造成两路铃声叠加.
     */
    void ensureService(String title, String text, String tag) {
        if (AlarmService.running) return;
        try {
            Intent sv = new Intent(this, AlarmService.class);
            sv.setAction(AlarmService.ACTION_START);
            sv.putExtra(AlarmService.EX_TITLE, title);
            sv.putExtra(AlarmService.EX_TEXT, text);
            sv.putExtra(AlarmService.EX_TAG, tag);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(sv);
            else startService(sv);
        } catch (Throwable t) {
            android.util.Log.w("HzWater", "alarm activity: service start failed: " + t);
        }
    }

    /** 停掉发声服务. 界面的一切退出路径都要走到这里. */
    void stopService() {
        try {
            Intent sv = new Intent(this, AlarmService.class);
            sv.setAction(AlarmService.ACTION_STOP);
            startService(sv);
        } catch (Throwable t) {
            android.util.Log.w("HzWater", "alarm activity: service stop failed: " + t);
        }
    }

    void stopAll() {
        if (stopped) return;
        stopped = true;
        stopService();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 返回键等于"稍后再响", 不静默吞掉
        ClickSnooze s = new ClickSnooze(this, getIntent().getStringExtra(EX_TAG),
                getIntent().getStringExtra(EX_TITLE), getIntent().getStringExtra(EX_TEXT));
        s.onClick(null);
    }

    /* ---------------- 静态嵌套类 ---------------- */

    static class ClockTick implements Runnable {
        final AlarmActivity act;

        ClockTick(AlarmActivity a) {
            act = a;
        }

        @Override
        public void run() {
            if (act.isFinishing()) return;
            if (act.clock != null) {
                act.clock.setText(new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                        .format(new Date()));
            }
            act.ui.postDelayed(this, 1000L);
        }
    }

    static class ClickNoop implements View.OnClickListener {
        @Override
        public void onClick(View v) {
        }
    }

    static class ClickOff implements View.OnClickListener {
        final AlarmActivity act;
        final String tag;

        ClickOff(AlarmActivity a, String t) {
            act = a;
            tag = t;
        }

        @Override
        public void onClick(View v) {
            act.stopAll();
            if (tag != null) NativeExtras.cancelAlarm(act, tag);
            AlarmBridge.removed(act, tag);
            act.finish();
        }
    }

    static class ClickSnooze implements View.OnClickListener {
        final AlarmActivity act;
        final String tag;
        final String title;
        final String text;

        ClickSnooze(AlarmActivity a, String t, String ti, String tx) {
            act = a;
            tag = t;
            title = ti;
            text = tx;
        }

        @Override
        public void onClick(View v) {
            act.stopAll();
            NativeExtras.setAlarm(act, System.currentTimeMillis() + SNOOZE_MS,
                    title == null ? "洗衣完成" : title,
                    "5 分钟后再响", tag == null ? "snooze" : tag);
            AlarmBridge.snoozed(act, tag);
            act.finish();
        }
    }
}
