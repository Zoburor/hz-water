package com.hz.water;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 最小可用的文件提供器: 只对外开一个目录 cache/update/, 用于把下载好的 APK
 * 交给系统安装器(Android 7+ 不允许直接传 file:// 给 Intent).
 *
 * 继承 ContentProvider 而非 androidx FileProvider, 因为本工程不依赖 androidx.
 * 只读, 只允许 update 目录下的 .apk, 防路径穿越.
 */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.hz.water.apk";

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static Uri uriFor(Context c, File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    /** 解析出真实文件; 越界一律返回 null */
    static File resolve(Context c, Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        if (name.indexOf('/') >= 0 || name.indexOf("..") >= 0) return null;
        File f = new File(dir(c), name);
        if (!f.exists()) return null;
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(getContext(), uri);
        if (f == null) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] selArgs, String sort) {
        File f = resolve(getContext(), uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor mc = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        mc.addRow(row);
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }

    /** 清掉旧的更新包, 避免缓存堆积 */
    static void clean(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (int i = 0; i < fs.length; i++) {
            try {
                fs[i].delete();
            } catch (Exception ignored) {
            }
        }
    }

    static boolean ensureDir(Context c) {
        File d = dir(c);
        return d.isDirectory() || d.mkdirs();
    }

    static long sizeOf(File f) {
        return f == null ? -1 : f.length();
    }

    static void touch(File f) throws IOException {
        if (!f.exists()) f.createNewFile();
    }
}
