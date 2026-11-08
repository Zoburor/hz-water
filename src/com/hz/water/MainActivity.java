package com.hz.water;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 极简宿主: 胖乖(企鹅/恒曌/唯米) 取水 + U净洗衣 二合一.
 *
 * 注意: 本文件不用任何匿名内部类(javac 21 生成的 NestHost 属性会让
 * build-tools 34 内置的 D8 8.2.2 抛 NPE), 全部静态嵌套类.
 *
 * 做六件事:
 *  1. loadDataWithBaseURL("https://localhost/") 注入 assets/index.html;
 *  2. 扫码走原生实时预览(ScanActivity: 相机 + 白框 + ZXing 逐帧识别), 不碰 WebView 图形管线
 *     ---- 模拟器上 getUserMedia + jsQR 会触发 SIGTRAP(Chrome_InProcGp) 闪退;
 *  3. HzNative.http 原生 HTTP 桥: u净要 x-app-code 等自定义头, 浏览器会走
 *     预检且服务端不放行, 只能由原生发;
 *  4. 定位 / 系统闹钟 / 通知 / 权限, 由 NativeExtras 承担;
 *  5. 更新: 下载 APK 到 cache/update 再交给系统的包安装器(不自更新, 不静默安装);
 *  6. 屏幕适配: WebView 不覆盖状态栏与导航栏, 横向与平板由页面 CSS 自适应.
 */
public class MainActivity extends Activity {

    static final int REQ_CAMERA = 1001;
    static final int REQ_FILE = 1002;
    static final int REQ_PERMS = 1003;
    /** 实时扫码页(相机预览 + 白框 + 自动识别) */
    static final int REQ_SCAN = 1004;
    static final String TAG = "HzWater";

    WebView web;
    SplashLayer splash;
    boolean splashGone = false;
    long splashAt = 0;
    boolean splashPending = false;
    PermissionRequest pendingCam;
    ValueCallback<Uri[]> pendingFile;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(SplashLayer.BG_BOT);
            w.setNavigationBarColor(SplashLayer.BG_BOT);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SplashLayer.BG_BOT);
        // 让内容区落在状态栏与导航栏之内, 避免"铺满全屏"盖住系统栏
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        web.setBackgroundColor(SplashLayer.BG_BOT);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 启动层盖在 WebView 上, 页面首帧就绪后淡出
        splash = new SplashLayer(this);
        splashAt = SystemClock.uptimeMillis();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " HzWater/1.6");
        // 视口按屏幕宽度自适应, 不锁缩放, 横屏也不放大
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChrome(this));
        web.addJavascriptInterface(new Bridge(this), "HzNative");

        web.loadUrl("about:blank");
        loadApp();

        if (Build.VERSION.SDK_INT >= 23) {
            web.post(new PermKick(this));
        }
    }

    /** 启动后跑一次权限自检, 只弹缺的那几项 */
    static class PermKick implements Runnable {
        final MainActivity act;

        PermKick(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            NativeExtras.requestPerms(act);
        }
    }

    /** 用 https://localhost/ 作基址注入, 制造 secure context */
    /** 启动层最短展示时长. 页面是本地 HTML, 几十毫秒就 ready, 不留一下开屏等于不存在. */
    static final long SPLASH_MIN_MS = 1150;

    /**
     * 系统栏配色跟随页面主题.
     * 浅色: 白底 + 深色图标; 深色: 深底 + 浅色图标.
     * 用 SystemUiVisibility 而不是 setSystemBarsAppearance, 因为后者要 API 30.
     */
    void applyBarTheme(boolean light) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(light ? 0xFFFFFFFF : SplashLayer.BG_BOT);
            w.setNavigationBarColor(light ? 0xFFF6F8FA : SplashLayer.BG_BOT);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View dv = w.getDecorView();
            int vis = dv.getSystemUiVisibility();
            if (light) {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                if (light) {
                    vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            dv.setSystemUiVisibility(vis);
        }
        // WebView 内的滚动条与系统栏一致
        if (web != null && Build.VERSION.SDK_INT >= 29) {
            web.setVerticalScrollBarEnabled(!light);
        }
    }

    /** 页面首帧好了以后淡出启动层. 幂等. */
    void hideSplash() {
        if (splashGone || splash == null) {
            return;
        }
        long wait = SPLASH_MIN_MS - (SystemClock.uptimeMillis() - splashAt);
        if (wait > 0) {
            if (!splashPending) {
                splashPending = true;
                splash.postDelayed(new SplashKill(this), wait);
            }
            return;
        }
        splashGone = true;
        splash.animate().alpha(0f).setDuration(260).withEndAction(new SplashGone(this)).start();
    }

    void loadApp() {
        String html = readAsset("index.html");
        if (html == null) {
            Toast.makeText(this, "内置页面缺失", Toast.LENGTH_LONG).show();
            return;
        }
        web.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
    }

    String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "readAsset " + name, e);
            return null;
        }
    }

    /** 把结果回传给页面的回调表 window.__hzCb */
    void deliver(String reqId, String jsonPayload) {
        if (web == null) return;
        String id = reqId == null ? "null" : JSONObject.quote(reqId);
        String js = "(function(){try{var f=window.__hzCb&&window.__hzCb[" + id + "];"
                + "if(f){delete window.__hzCb[" + id + "];f(" + jsonPayload + ");}}catch(e){}})();";
        runOnUiThread(new EvalTask(this, js));
    }

    /** 附带一个成功标志的短回包 */
    void deliverOk(String reqId, String extra) {
        deliver(reqId, "{\"ok\":true" + (extra == null ? "" : "," + extra) + "}");
    }

    void deliverErr(String reqId, String err, String extra) {
        String q;
        try {
            q = JSONObject.quote(err == null ? "unknown" : err);
        } catch (Exception e) {
            q = "\"unknown\"";
        }
        deliver(reqId, "{\"ok\":false,\"err\":" + q + (extra == null ? "" : "," + extra) + "}");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        if (req == REQ_CAMERA) {
            boolean okCam = perms.length > 0 && grant.length > 0
                    && grant[0] == PackageManager.PERMISSION_GRANTED;
            if (okCam && pendingCam == null) {
                /* 是"扫码"这条路要的权限, 给到就直接进扫码页 */
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            }
        }
        if (req == REQ_CAMERA && pendingCam != null) {
            boolean ok = grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) pendingCam.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCam.deny();
            pendingCam = null;
        }
        if (req == REQ_PERMS && web != null) {
            // 权限页读的是实时状态, 结果一到就通知页面重画
            web.post(new EvalTask(this,
                    "(function(){try{window.__permChanged&&window.__permChanged();}catch(e){}})();"));
        }
        super.onRequestPermissionsResult(req, perms, grant);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_SCAN) {
            String txt = (res == RESULT_OK && data != null)
                    ? data.getStringExtra(ScanActivity.EXTRA_TEXT) : null;
            if (txt == null || txt.length() == 0) {
                String msg = (data != null && data.getStringExtra("err") != null)
                        ? data.getStringExtra("err") : "已取消";
                deliverErr("qr", msg, null);
            } else {
                deliver("qr", "{\"ok\":true,\"text\":" + JSONObject.quote(txt) + "}");
            }
            return;
        }
        if (req == NativeQr.REQ_CAPTURE || req == NativeQr.REQ_GALLERY) {
            String txt = NativeQr.handleResult(this, req, res, data);
            if (txt == null) {
                String msg = (res == RESULT_OK) ? "图片里没识别到二维码" : "已取消";
                deliverErr("qr", msg, null);
            } else {
                deliver("qr", "{\"ok\":true,\"text\":" + JSONObject.quote(txt) + "}");
            }
            return;
        }
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
            if (pendingFile != null) {
                pendingFile.onReceiveValue(out);
                pendingFile = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /** 页面栈深度, 由 JS 通过 Bridge.setDepth 同步过来 */
    int pageDepth = 0;

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (pageDepth > 0 && web != null) {
                web.evaluateJavascript("(function(){try{window.__back&&window.__back();}catch(x){}})();", null);
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------- 静态嵌套类(替代匿名类) ----------

    static class EvalTask implements Runnable {
        final MainActivity act;
        final String js;

        EvalTask(MainActivity a, String j) {
            act = a;
            js = j;
        }

        @Override
        public void run() {
            if (act.web != null) act.web.evaluateJavascript(js, null);
        }
    }

    /** JS 桥: 原生扫码 + 原生 HTTP + 定位/闹钟/权限/更新 */
    static class Bridge {
        final MainActivity act;

        Bridge(MainActivity a) {
            act = a;
        }

        /** 页面把自己的主题告诉原生, 原生据此决定状态栏/导航栏是深底白字还是浅底黑字. */
        @JavascriptInterface
        public void theme(final String mode) {
            act.runOnUiThread(new ThemeTask(act, mode));
        }

        @JavascriptInterface
        public void ready() {
            act.runOnUiThread(new SplashKill(act));
        }

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        @JavascriptInterface
        public String platform() {
            return "android-native-qr/1.6";
        }

        /** 机型与运行环境.
         *  页面"检查更新"要按 ABI 选包, WebView 的 UA 里没有 ABI, 必须由原生给. */
        @JavascriptInterface
        public String deviceInfo() {
            try {
                String abi = "";
                if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null
                        && Build.SUPPORTED_ABIS.length > 0) {
                    abi = Build.SUPPORTED_ABIS[0];
                } else {
                    abi = Build.CPU_ABI == null ? "" : Build.CPU_ABI;
                }
                StringBuilder all = new StringBuilder();
                if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null) {
                    for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                        if (i > 0) all.append(',');
                        all.append(Build.SUPPORTED_ABIS[i]);
                    }
                } else if (Build.CPU_ABI != null) {
                    all.append(Build.CPU_ABI);
                }
                boolean emu = "goldfish".equals(Build.HARDWARE)
                        || "ranchu".equals(Build.HARDWARE)
                        || Build.FINGERPRINT == null
                        || Build.FINGERPRINT.contains("generic")
                        || Build.MODEL.contains("Emulator")
                        || Build.MODEL.contains("Android SDK built for");
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ok", true);
                o.put("model", safe(Build.MODEL));
                o.put("brand", safe(Build.BRAND));
                o.put("manufacturer", safe(Build.MANUFACTURER));
                o.put("device", safe(Build.DEVICE));
                o.put("product", safe(Build.PRODUCT));
                o.put("abi", abi);
                o.put("abis", all.toString());
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", safe(Build.VERSION.RELEASE));
                o.put("emulator", emu);
                return o.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"err\":\"deviceInfo failed\"}";
            }
        }

        private static String safe(String v) {
            return v == null ? "" : v;
        }

        @JavascriptInterface
        public void scanQr() {
            act.runOnUiThread(new ScanTask(act));
        }

        @JavascriptInterface
        public void pickQr() {
            act.runOnUiThread(new PickTask(act));
        }

        @JavascriptInterface
        public void toast(String msg) {
            act.runOnUiThread(new ToastTask(act, msg));
        }

        /**
         * @param reqId   回调 id, 结果通过 window.__hzCb[reqId] 返回
         * @param method  GET / POST
         * @param url     完整 URL
         * @param hdrJson 请求头 JSON 对象
         * @param body    请求体(页面已编码), 可为空
         */
        @JavascriptInterface
        public void http(String reqId, String method, String url, String hdrJson, String body) {
            new HttpTask(act, reqId, method, url, hdrJson, body).start();
        }

        /** JS 同步页面栈深度, 供返回键判断 */
        @JavascriptInterface
        public void setDepth(int d) {
            act.pageDepth = d;
        }

        /** 用系统浏览器打开外链(充值页等) */
        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.length() == 0) return;
            act.runOnUiThread(new OpenTask(act, url));
        }

        /**
         * 用外部应用打开任意 scheme.
         *
         * 微信的 weixin://wap/pay 必须走外部 Intent: WebView 不认非 http scheme,
         * 页面里 window.open 会被直接丢弃. 这里由原生 startActivity 交给系统路由.
         */
        @JavascriptInterface
        public void openScheme(String scheme) {
            if (scheme == null || scheme.length() == 0) return;
            act.runOnUiThread(new OpenTask(act, scheme));
        }

        /* ---------- 关键状态镜像(防 localStorage 丢失) ---------- */

        /**
         * 把页面里那几个丢不起的 key 存一份到原生 SharedPreferences.
         * 只接受 PrefStore.KEYS 白名单内的 key, 其它静默忽略.
         */
        @JavascriptInterface
        public void mirror(String key, String val) {
            PrefStore.put(act, key, val);
        }

        /** 取回一份副本, 没有时返回空串 */
        @JavascriptInterface
        public String mirrorGet(String key) {
            return PrefStore.get(act, key);
        }

        /** 一次取回全部副本(JSON 对象字面量), 页面启动时用来回填 */
        @JavascriptInterface
        public String mirrorAll() {
            return PrefStore.all(act);
        }

        @JavascriptInterface
        public void mirrorClear(String key) {
            PrefStore.clear(act, key);
        }

        /* ---------- 公共目录备份(卸载重装也不丢) ---------- */

        /** 把整份状态写进 Downloads/hz-water-backup.json, 卸载不清 */
        @JavascriptInterface
        public boolean dumpWrite(String body) {
            return AccDump.write(act, body);
        }

        /** 读回那份备份, 没有时返回空串 */
        @JavascriptInterface
        public String dumpRead() {
            return AccDump.read(act);
        }

        @JavascriptInterface
        public boolean dumpExists() {
            return AccDump.exists(act);
        }

        /** 清掉多余副本, 只留最新一条(Downloads 里不会堆 (1)(2)(3)) */
        @JavascriptInterface
        public int dumpPrune() {
            return AccDump.prune(act);
        }

        /** 当前备份走哪条路: "file"(有全文件权限, 跨卸载可读) 或 "media"(仅本安装内有效) */
        @JavascriptInterface
        public String dumpMode() {
            return AccDump.mode();
        }

        /**
         * 拉系统设置页, 让用户给"所有文件访问权限".
         * 只有拿到它, 备份才能在卸载重装后读回 -- MediaStore 的 owner 在卸载时就废了.
         */
        @JavascriptInterface
        public void askAllFiles() {
            act.runOnUiThread(new AllFilesTask(act));
        }

        /** 复制到剪贴板 */
        @JavascriptInterface
        public void copy(String text) {
            if (text == null) return;
            act.runOnUiThread(new CopyTask(act, text));
        }

        /* ---------- 支付宝原生支付 ---------- */

        @JavascriptInterface
        public void aliPay(String reqId, String orderInfo) {
            NativeExtras.aliPay(act, reqId, orderInfo);
        }
        /* ---------- 定位 ---------- */

        @JavascriptInterface
        public void locate(String reqId) {
            NativeExtras.locate(act, reqId);
        }

        /* ---------- 闹钟 ---------- */

        @JavascriptInterface
        public void setAlarm(String reqId, long triggerMs, String title, String text, String tag) {
            String r = NativeExtras.setAlarm(act, triggerMs, title, text, tag);
            act.deliver(reqId, wrapOk(r));
        }

        @JavascriptInterface
        public void cancelAlarm(String reqId, String tag) {
            String r = NativeExtras.cancelAlarm(act, tag);
            act.deliver(reqId, wrapOk(r));
        }

        /** 直接往手机自带时钟里加一条闹钟(SET_ALARM) */
        @JavascriptInterface
        public void systemAlarm(String reqId, int hour, int minute, String message) {
            String r = NativeExtras.openSystemAlarm(act, hour, minute, message);
            act.deliver(reqId, wrapOk(r));
        }

        /** 取走"最后一次闹钟操作"事件, 形如 off:<tag> / snooze:<tag>; 取完即清 */
        @JavascriptInterface
        public String alarmEvent() {
            return AlarmBridge.take();
        }

        @JavascriptInterface
        public void exactAlarmPerm(String reqId) {
            String r = NativeExtras.requestExactAlarm(act);
            act.deliver(reqId, wrapOk(r));
        }

        /* ---------- 权限 ---------- */

        @JavascriptInterface
        public String permState() {
            return NativeExtras.permState(act);
        }

        @JavascriptInterface
        public void requestPerms(String reqId) {
            NativeExtras.requestPerms(act);
            act.deliverOk(reqId, "\"asked\":true");
        }

        /* ---------- 更新 ---------- */

        @JavascriptInterface
        public void installPerm(String reqId) {
            String r = NativeExtras.requestInstallPerm(act);
            act.deliver(reqId, wrapOk(r));
        }

        /**
         * 下载 APK 并交给系统安装器.
         * 页面依次给镜像地址, 第一个成功的就用.
         */
        @JavascriptInterface
        public void updateFrom(String reqId, String urlCsv) {
            new UpdateTask(act, reqId, urlCsv).start();
        }

        /** 安装已经下载好的包(权限授完回来重试) */
        @JavascriptInterface
        public void installReady(String reqId) {
            act.runOnUiThread(new InstallTask(act, reqId));
        }

        /** 用户点"不同意"时真的要能退出, 不是只弹个提示 */
        @JavascriptInterface
        public void exitApp() {
            act.runOnUiThread(new ExitTask(act));
        }
    }

    static class ExitTask implements Runnable {
        final MainActivity act;

        ExitTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            try {
                act.finishAffinity();
            } catch (Exception e) {
                act.finish();
            }
        }
    }

    /** NativeExtras 返回的已经是 JSON 对象文本, 这里只做空值兜底 */
    static String wrapOk(String json) {
        if (json == null || json.length() == 0) return "{\"ok\":false,\"err\":\"empty\"}";
        return json;
    }

    static class OpenTask implements Runnable {
        final MainActivity act;
        final String url;

        OpenTask(MainActivity a, String u) {
            act = a;
            url = u;
        }

        @Override
        public void run() {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Exception e) {
                Toast.makeText(act, "打不开链接: " + url, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 拉系统"所有文件访问权限"授权页.
     *
     * 为什么必须让用户手点: MANAGE_EXTERNAL_STORAGE 不允许代码静默取得, 只能跳设置页.
     * 只有拿到它, 备份文件才能用 File API 读写, 从而在卸载重装后仍然读得回来
     * (MediaStore 的记录在卸载时 owner 会被系统清成 NULL, 应用再也打不开).
     */
    static class AllFilesTask implements Runnable {
        final MainActivity act;

        AllFilesTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            try {
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    Toast.makeText(act, "这个系统版本不需要该权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (android.os.Environment.isExternalStorageManager()) {
                    Toast.makeText(act, "已经拥有全部文件访问权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + act.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i2 = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i2);
                } catch (Exception e2) {
                    Toast.makeText(act, "打不开设置页, 请手动到 设置-应用-特殊权限 开启", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    static class CopyTask implements Runnable {
        final MainActivity act;
        final String text;

        CopyTask(MainActivity a, String t) {
            act = a;
            text = t;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void run() {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("hz", text));
                Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(act, "复制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 开实时扫码页; 相机权限没给就先去要 */
    static class ScanTask implements Runnable {
        final MainActivity act;

        ScanTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            if (act.checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return;
            }
            act.startActivityForResult(new Intent(act, ScanActivity.class), REQ_SCAN);
        }
    }

    static class CaptureTask implements Runnable {
        final MainActivity act;

        CaptureTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            NativeQr.capture(act);
        }
    }

    static class PickTask implements Runnable {
        final MainActivity act;

        PickTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            NativeQr.pick(act);
        }
    }

    static class ToastTask implements Runnable {
        final MainActivity act;
        final String msg;

        ToastTask(MainActivity a, String m) {
            act = a;
            msg = m;
        }

        @Override
        public void run() {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** 原生 HTTP, 绕过 WebView CORS 与自定义头预检 */
    static class HttpTask extends Thread {
        final MainActivity act;
        final String reqId;
        final String method;
        final String url;
        final String hdrJson;
        final String body;

        HttpTask(MainActivity a, String id, String m, String u, String h, String b) {
            act = a;
            reqId = id;
            method = (m == null || m.length() == 0) ? "GET" : m.toUpperCase();
            url = u;
            hdrJson = h;
            body = b;
        }

        @Override
        public void run() {
            String out = null;
            // 服务端偶发 Connection reset(尤其首次 TLS 握手被中间设备打断), 重试一次即可
            for (int attempt = 0; attempt < 2 && out == null; attempt++) {
                out = one();
                if (out == null && attempt == 0) {
                    try { Thread.sleep(350); } catch (InterruptedException ignored) { }
                }
            }
            if (out == null) {
                try {
                    JSONObject r = new JSONObject();
                    r.put("ok", false);
                    r.put("err", lastErr == null ? "unknown" : lastErr);
                    out = r.toString();
                } catch (Exception e) {
                    out = "{\"ok\":false,\"err\":\"unknown\"}";
                }
            }
            act.deliver(reqId, out);
        }

        String lastErr;

        String one() {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(15000);
                c.setReadTimeout(25000);
                c.setInstanceFollowRedirects(false);
                // 关掉 gzip: 服务端偶尔与 Content-Length 一起返回压缩体, 读流时报 reset
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Accept", "*/*");
                if (hdrJson != null && hdrJson.length() > 2) {
                    JSONObject h = new JSONObject(hdrJson);
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.setRequestProperty(k, h.optString(k, ""));
                    }
                }
                if (body != null && body.length() > 0 && !"GET".equals(method)) {
                    c.setDoOutput(true);
                    byte[] raw = body.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(raw.length);
                    OutputStream os = c.getOutputStream();
                    os.write(raw);
                    os.flush();
                    os.close();
                }
                int code = c.getResponseCode();
                InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
                String text = "";
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    text = new String(bo.toByteArray(), "UTF-8");
                }
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("status", code);
                r.put("text", text);
                return r.toString();
            } catch (Exception e) {
                Log.w(TAG, "http " + url, e);
                lastErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /**
     * 更新下载: 逐个试候选地址(镜像优先), 第一个拿到的包写到 cache/update/update.apk,
     * 然后交给系统安装器. 不做静默安装, 也不做自替换.
     */
    static class UpdateTask extends Thread {
        final MainActivity act;
        final String reqId;
        final String urlCsv;

        UpdateTask(MainActivity a, String id, String csv) {
            act = a;
            reqId = id;
            urlCsv = csv == null ? "" : csv;
        }

        @Override
        public void run() {
            String[] urls = urlCsv.split(",");
            String lastErr = null;
            for (int i = 0; i < urls.length; i++) {
                String u = urls[i].trim();
                if (u.length() == 0) continue;
                String r = tryOne(u);
                if (r == null) {
                    act.deliver(reqId, "{\"ok\":true,\"url\":\"" + u.replace("\"", "") + "\"}");
                    act.runOnUiThread(new InstallTask(act, null));
                    return;
                }
                lastErr = r;
            }
            act.deliverErr(reqId, lastErr == null ? "没有可用地址" : lastErr, "\"tried\":" + urls.length);
        }

        /** 成功返回 null, 失败返回原因 */
        String tryOne(String u) {
            HttpURLConnection c = null;
            FileOutputStream fos = null;
            File tmp = null;
            try {
                if (!ApkProvider.ensureDir(act)) return "缓存目录不可用";
                ApkProvider.clean(act);
                tmp = new File(ApkProvider.dir(act), "update.apk");
                if (tmp.exists()) tmp.delete();

                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code != 200) return "HTTP " + code;
                long len = c.getContentLength();
                if (len > 0 && len < 20000) return "包太小(" + len + "), 不像 APK";
                InputStream in = c.getInputStream();
                fos = new FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                in.close();
                fos.flush();
                fos.close();
                fos = null;
                if (total < 20000) {
                    tmp.delete();
                    return "下载不完整(" + total + ")";
                }
                // 校验是不是真的 zip(APK 开头必为 PK)
                byte[] head = new byte[2];
                InputStream chk = new java.io.FileInputStream(tmp);
                int got = chk.read(head);
                chk.close();
                if (got < 2 || head[0] != 'P' || head[1] != 'K') {
                    tmp.delete();
                    return "下载到的不是 APK";
                }
                Log.i(TAG, "update downloaded " + total + " bytes from " + u);
                return null;
            } catch (Exception e) {
                if (tmp != null && tmp.exists()) tmp.delete();
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (Exception ignored) { }
                }
                if (c != null) c.disconnect();
            }
        }
    }

    /** 把 cache/update/update.apk 交给系统安装器 */
    static class InstallTask implements Runnable {
        final MainActivity act;
        final String reqId;

        InstallTask(MainActivity a, String id) {
            act = a;
            reqId = id;
        }

        @Override
        public void run() {
            File f = new File(ApkProvider.dir(act), "update.apk");
            if (!f.exists()) {
                if (reqId != null) act.deliverErr(reqId, "没有已下载的包", null);
                return;
            }
            if (!NativeExtras.canInstall(act)) {
                if (reqId != null) {
                    act.deliver(reqId,
                            "{\"ok\":false,\"needInstallPerm\":true,\"err\":\"需要允许安装未知应用\"}");
                }
                return;
            }
            try {
                Uri u = ApkProvider.uriFor(act, f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(i);
                if (reqId != null) act.deliverOk(reqId, "\"launched\":true");
            } catch (Exception e) {
                if (reqId != null) act.deliverErr(reqId, "拉起安装器失败: " + e.getMessage(), null);
            }
        }
    }

    /** 切系统栏配色. 浅色主题下状态栏要浅底深字, 否则白页顶着一条深蓝边. */
    static class ThemeTask implements Runnable {
        final MainActivity act;
        final boolean light;

        ThemeTask(MainActivity a, String mode) {
            act = a;
            light = "light".equals(mode) || "light-hc".equals(mode);
        }

        @Override
        public void run() {
            act.applyBarTheme(light);
        }
    }

    static class SplashKill implements Runnable {
        final MainActivity act;

        SplashKill(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            act.hideSplash();
        }
    }

    static class AppClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView v, String url) {
            // 兜底: 页面自己会先调 ready(), 这里迟到 1.1 秒再收一次
            final MainActivity a = (MainActivity) v.getContext();
            v.postDelayed(new SplashKill(a), 1100);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String u = r.getUrl().toString();
            if (u.startsWith("http://localhost") || u.startsWith("https://localhost")) {
                return false;
            }
            if (u.startsWith("http://") || u.startsWith("https://")) {
                return false;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                v.getContext().startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "open external failed: " + u, e);
            }
            return true;
        }
    }

    /** 必须 static: 非静态内部类带 synthetic this$0, 同样触发 D8 8.2.2 的 NestHost NPE */
    static class AppChrome extends WebChromeClient {
        final MainActivity act;

        AppChrome(MainActivity a) {
            act = a;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            act.runOnUiThread(new GrantTask(act, request));
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(TAG, m.message() + " @" + m.lineNumber());
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
            r.confirm();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            act.pendingFile = cb;
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                act.startActivityForResult(Intent.createChooser(i, "选择二维码图片"), REQ_FILE);
            } catch (Exception e) {
                act.pendingFile = null;
                return false;
            }
            return true;
        }
    }

    /** 摄像头权限协调(WebView 侧已不用, 保留以防页面走 getUserMedia 兜底) */
    static class GrantTask implements Runnable {
        final MainActivity act;
        final PermissionRequest req;

        GrantTask(MainActivity a, PermissionRequest r) {
            act = a;
            req = r;
        }

        @Override
        public void run() {
            boolean wantCam = false;
            String[] res = req.getResources();
            for (int i = 0; i < res.length; i++) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res[i])) wantCam = true;
            }
            if (!wantCam) {
                req.deny();
                return;
            }
            boolean granted = act.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                act.pendingCam = req;
                act.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        }
    }
}
