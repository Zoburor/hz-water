package com.hz.water;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把登录态等到外部存储(公共 Downloads)落一份明文备份, 让"卸载重装"也不丢.
 *
 * ===== 为什么这件事这么难(全部真机实测结论, 不是推测) =====
 *
 * 1. SharedPreferences 与 WebView 的 localStorage 都在 /data/data/com.hz.water 里.
 *    `adb uninstall` 与系统"清除数据"会把整个目录删掉, 二者一起消失. 所以必须在
 *    外部存储留副本.
 *
 * 2. 走 MediaStore.Downloads 写文件, 免运行时权限, 应用自己读自己写都正常.
 *    但一旦 uninstall 或 pm clear, 那条 MediaStore 记录的 owner_package_name
 *    会被系统改成 NULL, 之后本应用 openInputStream 直接失败 -- 等于备份文件还在,
 *    但读不回来了. 实测: uninstall 前后 owner 从 com.hz.water 变 NULL.
 *
 * 3. 所以真正的解法只有 File API + MANAGE_EXTERNAL_STORAGE(所有文件访问权限).
 *    有了它就能直接用 File 读写 /storage/emulated/0/Download/..., 绕开 MediaStore
 *    的 owner 校验. 用户需要去系统设置里点一下授权(不能由代码静默取得).
 *
 * ===== 本类的策略 =====
 *   readFile()  : 有全文件权限 -> 直接读 File; 读不到再走 MediaStore
 *   writeFile() : 有全文件权限 -> 直接写 File(覆盖同一个文件名, 不增生); 否则走 MediaStore
 *   两条路都保留, 因为用户可能不给全文件权限, 那时至少 MediaStore 这条路在
 *   "没卸载过"的场景下仍然有效.
 *
 * 约束: 全部静态方法, 无匿名内部类(D8 8.2.2 对 NestHost 抛 NPE).
 */
final class AccDump {

    static final String NAME_BASE = "hz-water-backup";
    static final String DISPLAY = NAME_BASE + ".json";
    static final String MIME = "application/json";
    static final String TAG = "HzWater";

    /** 一个 MediaStore 候选条目 */
    static final class Hit {
        Uri uri;
        String name;
        long id;

        Hit(Uri u, String n, long i) {
            uri = u;
            name = n;
            id = i;
        }
    }

    /** 是否拿到"所有文件访问权限" */
    static boolean canAllFiles() {
        try {
            if (Build.VERSION.SDK_INT < 30) return true; /* Android 10 及以下, 传统外部存储可直写 */
            return Environment.isExternalStorageManager();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 公共 Downloads 的物理路径, 优先取系统给的那个, 取不到用固定挂载点 */
    static File publicFile() {
        File dir = null;
        try {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable e) {
        }
        if (dir == null) {
            dir = new File("/storage/emulated/0/Download");
        }
        return new File(dir, DISPLAY);
    }

    /* ================= File API 路径(需要全文件权限) ================= */

    static String readFile() {
        if (!canAllFiles()) return "";
        try {
            File f = publicFile();
            if (!f.exists() || f.length() < 2) return "";
            FileInputStream is = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                String s = new String(bo.toByteArray(), "UTF-8");
                Log.i(TAG, "dump file read: " + s.length() + " chars");
                return s;
            } finally {
                try { is.close(); } catch (Throwable e2) { }
            }
        } catch (Throwable e) {
            Log.i(TAG, "dump file read failed: " + e);
            return "";
        }
    }

    static boolean writeFile(String body) {
        if (!canAllFiles()) return false;
        if (body == null) return false;
        FileOutputStream os = null;
        try {
            File f = publicFile();
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            os = new FileOutputStream(f); /* 覆盖同一个文件名, 不会堆出 (1)(2)(3) */
            os.write(body.getBytes("UTF-8"));
            os.flush();
            Log.i(TAG, "dump file written: " + body.length() + " chars");
            return true;
        } catch (Throwable e) {
            Log.i(TAG, "dump file write failed: " + e);
            return false;
        } finally {
            if (os != null) {
                try { os.close(); } catch (Throwable e2) { }
            }
        }
    }

    /** File 路径上是否已有备份 */
    static boolean fileExists() {
        if (!canAllFiles()) return false;
        try {
            File f = publicFile();
            return f.exists() && f.length() > 2;
        } catch (Throwable e) {
            return false;
        }
    }

    /* ================= MediaStore 路径(无权限时的退路) ================= */

    /**
     * 列出所有同名/改名副本, 按 _id 倒序.
     * 用 LIKE 前缀, 因为 MediaStore 会把重名文件改成 "hz-water-backup (1).json".
     */
    static List<Hit> findAll(Context c) {
        List<Hit> out = new ArrayList<Hit>();
        Cursor cu = null;
        try {
            ContentResolver cr = c.getContentResolver();
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] proj = new String[]{
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME
            };
            String sel = MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
            String[] args = new String[]{NAME_BASE + "%"};
            cu = cr.query(col, proj, sel, args, MediaStore.Downloads._ID + " DESC");
            if (cu == null) return out;
            while (cu.moveToNext()) {
                long id = cu.getLong(0);
                String nm = cu.getString(1);
                out.add(new Hit(ContentUris.withAppendedId(col, id), nm == null ? "" : nm, id));
            }
        } catch (Throwable e) {
            Log.i(TAG, "dump findAll failed: " + e);
        } finally {
            if (cu != null) {
                try { cu.close(); } catch (Throwable e2) { }
            }
        }
        return out;
    }

    /** 逐个候选尝试读, 第一个"能打开且非空"的返回. 读不到的(别人的/owner 已清空的)直接跳过 */
    static String readMedia(Context c) {
        List<Hit> hits = findAll(c);
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            InputStream is = null;
            try {
                is = c.getContentResolver().openInputStream(h.uri);
                if (is == null) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                String s = new String(bo.toByteArray(), "UTF-8");
                if (s.length() > 2) {
                    Log.i(TAG, "dump media read: " + s.length() + " chars from " + h.name);
                    return s;
                }
            } catch (Throwable e) {
                Log.i(TAG, "dump media read skip " + h.name + ": " + e);
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable e2) { }
                }
            }
        }
        return "";
    }

    static boolean writeMedia(Context c, String body) {
        byte[] bytes;
        try {
            bytes = body.getBytes("UTF-8");
        } catch (Throwable e) {
            return false;
        }
        List<Hit> hits = findAll(c);
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            if (overwrite(c, h.uri, bytes)) {
                Log.i(TAG, "dump media overwrite " + h.name + ": " + body.length() + " chars");
                return true;
            }
        }
        Uri nu = create(c);
        if (nu == null) return false;
        if (overwrite(c, nu, bytes)) {
            /* 必须写完字节再 commit, 否则系统在 0 字节时就收尾, 文件变成 .pending- 死条目 */
            commit(c, nu);
            Log.i(TAG, "dump media create: " + body.length() + " chars");
            return true;
        }
        return false;
    }

    static boolean overwrite(Context c, Uri uri, byte[] bytes) {
        OutputStream os = null;
        try {
            os = c.getContentResolver().openOutputStream(uri, "wt");
            if (os == null) return false;
            os.write(bytes);
            os.flush();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (os != null) {
                try { os.close(); } catch (Throwable e2) { }
            }
        }
    }

    /** 新建一条 MediaStore 记录, 保持 IS_PENDING=1 直到写完再 commit */
    static Uri create(Context c) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, DISPLAY);
            v.put(MediaStore.Downloads.MIME_TYPE, MIME);
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Downloads.IS_PENDING, 1);
            }
            return c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        } catch (Throwable e) {
            Log.i(TAG, "dump create failed: " + e);
            return null;
        }
    }

    /**
     * 把 IS_PENDING 置 0, 让文件对别的应用可见.
     * 顺序必须是 insert(pending=1) -> 写字节 -> commit(pending=0);
     * 提前置 0 会让系统在文件还是 0 字节时判定完成, 之后写入报
     * No item at content://media/external/downloads/<id>, 并留下 .pending- 死文件.
     */
    static void commit(Context c, Uri uri) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            c.getContentResolver().update(uri, v, null, null);
        } catch (Throwable e) {
            Log.i(TAG, "dump commit failed: " + e);
        }
    }

    /* ================= 对外统一入口 ================= */

    /** 读: 先 File(需要全文件权限, 能跨卸载), 再 MediaStore(无需权限, 但卸载后 owner 失效) */
    static String read(Context c) {
        String s = readFile();
        if (s.length() > 2) return s;
        return readMedia(c);
    }

    /** 写: 有全文件权限就写 File(单文件覆盖, 跨卸载可读); 否则退回 MediaStore */
    static boolean write(Context c, String body) {
        if (body == null) return false;
        if (writeFile(body)) return true;
        return writeMedia(c, body);
    }

    static boolean exists(Context c) {
        if (fileExists()) return true;
        return findAll(c).size() > 0;
    }

    /** 清理 MediaStore 里多余的副本, 只留最新一条. File 路径只有一个文件, 无需清理 */
    static int prune(Context c) {
        List<Hit> hits = findAll(c);
        int n = 0;
        for (int i = 1; i < hits.size(); i++) {
            try {
                if (c.getContentResolver().delete(hits.get(i).uri, null, null) > 0) n++;
            } catch (Throwable e) {
            }
        }
        if (n > 0) Log.i(TAG, "dump prune: removed " + n);
        return n;
    }

    /** 当前用的是哪条路, 给页面显示用 */
    static String mode() {
        return canAllFiles() ? "file" : "media";
    }
}
