package com.hz.water;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;


import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时扫码: 相机预览 + 白色取景框 + 逐帧自动识别.
 *
 * 为什么不用 WebView 里的 getUserMedia + jsQR:
 *   模拟器与部分真机的 WebView GPU 进程在做逐帧 drawImage + getImageData 时
 *   会触发 SIGTRAP(Chrome_InProcGp / libmonochrome_64.so), 表现为"点扫码就闪退".
 *   所以预览与解码全部走原生, WebView 一点都不碰.
 *
 * 也用旧 android.hardware.Camera 而不是 CameraX: 本工程是 javac + d8 手工构建,
 * 没有 androidx 依赖, CameraX 拉不进来.
 *
 * 约束: 本文件不用任何匿名内部类(javac 21 生成的 NestHost 属性会让
 * build-tools 34 内置的 D8 8.2.2 抛 NPE), 全部静态嵌套类.
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback {

    static final String TAG = "HzWaterScan";
    static final String EXTRA_TEXT = "text";

    FrameLayout root;
    SurfaceView surface;
    OverlayView overlay;
    TextView hint;
    Camera cam;
    int previewW = 640;
    int previewH = 480;
    int rotationDeg = 90;

    final AtomicBoolean busy = new AtomicBoolean(false);
    final AtomicBoolean done = new AtomicBoolean(false);
    ExecutorService pool;


    @Override
    protected void onCreate(Bundle st) {
        super.onCreate(st);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        pool = Executors.newSingleThreadExecutor();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        this.root = root;

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 预览画面比例修正监听: 由 layoutPreview() 按"旋转后的预览宽高比"重排 SurfaceView
        root.addOnLayoutChangeListener(new RelayoutCb(this));

        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("把码放进框里, 会自动识别");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(15);
        hint.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = 160;
        root.addView(hint, hp);

        setContentView(root);
        surface.getHolder().addCallback(this);
    }

    /* ---------- SurfaceHolder.Callback ---------- */

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        openCamera(h);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {
        if (cam != null) {
            try {
                Camera.Parameters p = cam.getParameters();
                p.setPreviewSize(previewW, previewH);
                p.setFocusMode(pickFocus(p));
                cam.setParameters(p);
                cam.setDisplayOrientation(rotationDeg);
                cam.startPreview();
                overlay.setFrameByPreview(previewW, previewH);
                layoutPreview();
            } catch (Exception e) {
                Log.e(TAG, "surfaceChanged failed", e);
                finishErr("相机启动失败");
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        release();
    }

    /* ---------- 相机 ---------- */

    void openCamera(SurfaceHolder h) {
        try {
            cam = Camera.open();
            if (cam == null) { finishErr("没有可用的相机"); return; }

            Camera.CameraInfo ci = new Camera.CameraInfo();
            Camera.getCameraInfo(0, ci);
            int disp = getWindowManager().getDefaultDisplay().getRotation();
            int deg;
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                deg = (360 - (ci.orientation + rotationToDeg(disp)) % 360) % 360;
            } else {
                deg = (ci.orientation - rotationToDeg(disp) + 360) % 360;
            }
            rotationDeg = deg;

            Camera.Parameters p = cam.getParameters();
            pickPreviewSize(p);
            p.setPreviewSize(previewW, previewH);
            p.setFocusMode(pickFocus(p));
            cam.setParameters(p);
            cam.setDisplayOrientation(rotationDeg);
            cam.setPreviewDisplay(h);
            cam.setPreviewCallback(new PreviewCb(this));
            cam.startPreview();
            layoutPreview();
        } catch (Exception e) {
            Log.e(TAG, "open camera failed", e);
            finishErr("打不开相机, 检查权限");
        }
    }

    private static int rotationToDeg(int r) {
        switch (r) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    /**
     * 让预览画面保持相机原始宽高比, 不再被整屏拉伸.
     *
     * 拉伸的原因: 预览数据是横向 buffer(如 960x720), 先被 setDisplayOrientation 旋转 90 度,
     * 屏幕上正确的宽高比是 previewH:previewW(竖着的那份), 而 SurfaceView 之前是
     * MATCH_PARENT 铺满整屏(约 9:20). 两者比例不等, 系统只能把预览非等比缩放填满
     * SurfaceView, 于是画面在某一方向上被拉长 -- 这就是用户看到的"上下拉伸".
     *
     * 修法: 按旋转后的宽高比算出一个尽量大的居中矩形, 改 SurfaceView 的 LayoutParams;
     * 相机输出尺寸不变, 只是不再强行填满屏幕, 多出来的地方留黑边(黑底).
     * 90/270 度时用 (h,w), 0/180 度时用 (w,h).
     */
    void layoutPreview() {
        if (root == null || surface == null) return;
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw <= 0 || rh <= 0) return;
        if (previewW <= 0 || previewH <= 0) return;

        boolean turned = (rotationDeg % 180) != 0;
        /* 旋转后看到的宽 : 高 */
        int vw = turned ? previewH : previewW;
        int vh = turned ? previewW : previewH;
        if (vw <= 0 || vh <= 0) return;

        float scale = Math.min((float) rw / vw, (float) rh / vh);
        int w = Math.round(vw * scale);
        int h = Math.round(vh * scale);
        /* 不要超过容器, 也不要退化成 0 */
        if (w > rw) w = rw;
        if (h > rh) h = rh;
        if (w <= 0 || h <= 0) return;

        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
            if (f.width == w && f.height == h) return;   // 已经对了, 别反复 requestLayout
            f.width = w;
            f.height = h;
            f.gravity = android.view.Gravity.CENTER;
            surface.setLayoutParams(f);
        } else {
            surface.setLayoutParams(new FrameLayout.LayoutParams(w, h,
                    android.view.Gravity.CENTER));
        }
        Log.i(TAG, "preview layout " + w + "x" + h + " (buffer " + previewW + "x" + previewH
                + ", rot " + rotationDeg + ", view " + rw + "x" + rh + ")");
    }

    /** 挑一个 720p 以内的预览尺寸: 再大解码就慢得没法逐帧 */
    private void pickPreviewSize(Camera.Parameters p) {
        List<Camera.Size> all = null;
        try { all = p.getSupportedPreviewSizes(); } catch (Exception e) { }
        if (all == null || all.isEmpty()) return;
        Camera.Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Camera.Size s : all) {
            int longSide = Math.max(s.width, s.height);
            int shortSide = Math.min(s.width, s.height);
            if (longSide > 1280 || shortSide < 240) continue;
            int score = Math.abs(longSide - 960) + Math.abs(shortSide - 720);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        if (best == null) best = all.get(0);
        previewW = best.width;
        previewH = best.height;
    }

    private static String pickFocus(Camera.Parameters p) {
        List<String> modes = null;
        try { modes = p.getSupportedFocusModes(); } catch (Exception e) { }
        if (modes == null) return Camera.Parameters.FOCUS_MODE_AUTO;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
        return Camera.Parameters.FOCUS_MODE_AUTO;
    }

    void release() {
        if (cam != null) {
            try { cam.setPreviewCallback(null); } catch (Exception e) { }
            try { cam.stopPreview(); } catch (Exception e) { }
            try { cam.release(); } catch (Exception e) { }
            cam = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
        if (pool != null) pool.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(code, e);
    }

    void finishErr(String msg) {
        if (done.getAndSet(true)) return;
        Intent i = new Intent();
        i.putExtra("err", msg);
        setResult(RESULT_CANCELED, i);
        finish();
    }

    void finishOk(String text) {
        if (!done.compareAndSet(false, true)) return;
        try { cam.stopPreview(); } catch (Exception e) { }
        Intent i = new Intent();
        i.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, i);
        finish();
    }

    /* ---------- 逐帧回调 ---------- */

    /*
     * 布局变化回调: 屏幕旋转/系统栏变化后重新按预览比例排一次 SurfaceView.
     * 必须是 static 嵌套类(理由同下方 PreviewCb 注释).
     */
    static class RelayoutCb implements View.OnLayoutChangeListener {
        private final ScanActivity act;

        RelayoutCb(ScanActivity a) { act = a; }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            if (r - l != or - ol || b - t != ob - ot) act.layoutPreview();
        }
    }

    /*
     * PreviewCb 与 DecodeJob 都必须是 static 嵌套类.
     * 非静态内部类会让 javac 21 生成 NestHost/NestMembers 属性, build-tools 34 内置的
     * D8 8.2.2 读这两个属性时抛 NPE("Cannot invoke String.length() because <parameter1> is null"),
     * 报错定位在 ScanActivity$DecodeJob.class, 整包编译失败.
     */
    static class PreviewCb implements Camera.PreviewCallback {
        private final ScanActivity act;

        PreviewCb(ScanActivity a) { act = a; }

        @Override
        public void onPreviewFrame(byte[] data, Camera c) {
            if (data == null || act.done.get()) return;
            /* 上一帧还没解完就丢掉这一帧, 免得堆任务 */
            if (!act.busy.compareAndSet(false, true)) return;
            try {
                act.pool.execute(new DecodeJob(act, data, act.previewW, act.previewH));
            } catch (Exception e) {
                act.busy.set(false);
            }
        }
    }

    static class DecodeJob implements Runnable {
        private final ScanActivity act;
        private final byte[] data;
        private final int w, h;

        DecodeJob(ScanActivity a, byte[] d, int pw, int ph) {
            act = a; data = d; w = pw; h = ph;
        }

        @Override
        public void run() {
            String txt = null;
            try {
                txt = QrDecode.frame(data, w, h, act.rotationDeg);
            } catch (Throwable t) {
                Log.w(TAG, "decode failed", t);
            } finally {
                act.busy.set(false);
            }
            if (txt != null && txt.length() > 0) {
                act.runOnUiThread(new OkTask(act, txt));
            }
        }
    }

    /** 解出结果后回主线程收尾. 单独成类, 不用匿名内部类(D8 8.2.2 会 NPE). */
    static class OkTask implements Runnable {
        private final ScanActivity act;
        private final String text;

        OkTask(ScanActivity a, String t) { act = a; text = t; }

        @Override
        public void run() { act.finishOk(text); }
    }

    /** 打开相机失败/权限缺失时回主线程收尾 */
    static class ErrTask implements Runnable {
        private final ScanActivity act;
        private final String msg;

        ErrTask(ScanActivity a, String m) { act = a; msg = m; }

        @Override
        public void run() { act.finishErr(msg); }
    }

    /* ---------- 取景框 ---------- */

    static class OverlayView extends View {
        private final Paint mask = new Paint();
        private final Paint line = new Paint();
        private final RectF box = new RectF();
        private float scale = 1f;

        OverlayView(android.content.Context c) {
            super(c);
            mask.setColor(0x99000000);
            line.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4f);
            line.setAntiAlias(true);
        }

        /** 预览是横向的, 屏幕上看到的是旋转后的; 方框按屏幕中心给固定比例, 不做透视换算 */
        void setFrameByPreview(int pw, int ph) {
            scale = pw <= 0 ? 1f : ((float) ph) / pw;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            float side = Math.min(w, h) * 0.68f;
            float left = (w - side) / 2f;
            float top = (h - side) / 2f - h * 0.04f;
            box.set(left, top, left + side, top + side);

            // 四块遮罩, 中间留空(不用 xfermode, 免得要开软件层)
            c.drawRect(0, 0, w, box.top, mask);
            c.drawRect(0, box.bottom, w, h, mask);
            c.drawRect(0, box.top, box.left, box.bottom, mask);
            c.drawRect(box.right, box.top, w, box.bottom, mask);

            c.drawRect(box, line);

            // 四角加粗, 让框更醒目
            float arm = side * 0.16f;
            line.setStrokeWidth(7f);
            c.drawLine(box.left, box.top, box.left + arm, box.top, line);
            c.drawLine(box.left, box.top, box.left, box.top + arm, line);
            c.drawLine(box.right, box.top, box.right - arm, box.top, line);
            c.drawLine(box.right, box.top, box.right, box.top + arm, line);
            c.drawLine(box.left, box.bottom, box.left + arm, box.bottom, line);
            c.drawLine(box.left, box.bottom, box.left, box.bottom - arm, line);
            c.drawLine(box.right, box.bottom, box.right - arm, box.bottom, line);
            c.drawLine(box.right, box.bottom, box.right, box.bottom - arm, line);
            line.setStrokeWidth(4f);
        }
    }
}
