package com.hz.water;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 原生二维码识别.
 *
 * 为什么不用 WebView 里的 getUserMedia + jsQR:
 *   模拟器(以及部分真机)的 WebView GPU 进程在做逐帧 drawImage + getImageData
 *   时会触发 SIGTRAP(Chrome_InProcGp / libmonochrome_64.so), 表现为"点扫码就闪退".
 *   改用系统相机拍一张 + ZXing 静态解码, 完全不碰 WebView 的图形管线.
 *
 * 同样遵守"禁匿名内部类"约束(见 MainActivity 顶部注释).
 */
public class NativeQr {

    static final String TAG = "HzWaterQr";
    static final int REQ_CAPTURE = 2001;
    static final int REQ_GALLERY = 2002;

    /** 拉起系统相机拍照, 返回缩略图(不落盘, 不需要存储权限) */
    static void capture(Activity act) {
        try {
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            act.startActivityForResult(i, REQ_CAPTURE);
        } catch (Exception e) {
            Log.e(TAG, "capture failed", e);
        }
    }

    /** 拉起系统图库选图 */
    static void pick(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            act.startActivityForResult(Intent.createChooser(i, "选择二维码图片"), REQ_GALLERY);
        } catch (Exception e) {
            Log.e(TAG, "pick failed", e);
        }
    }

    /** 处理 onActivityResult, 返回解出的文本; 失败返回 null */
    static String handleResult(Activity act, int req, int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null) return null;
        Bitmap bmp = null;
        try {
            if (req == REQ_CAPTURE) {
                Object o = data.getExtras() == null ? null : data.getExtras().get("data");
                if (o instanceof Bitmap) bmp = (Bitmap) o;
            } else if (req == REQ_GALLERY) {
                Uri u = data.getData();
                if (u != null) {
                    InputStream in = act.getContentResolver().openInputStream(u);
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bmp = BitmapFactory.decodeStream(in, null, opt);
                    if (in != null) in.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load bitmap failed", e);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "oom", e);
        }
        if (bmp == null) return null;
        String s = decode(bmp);
        if (!bmp.isRecycled()) bmp.recycle();
        return s;
    }

    /** ZXing 解码, 带缩放重试(小图/截图放大后更容易解出) */
    static String decode(Bitmap src) {
        String r = tryDecode(src);
        if (r != null) return r;
        int w = src.getWidth(), h = src.getHeight();
        for (int k = 0; k < 2; k++) {
            float sc = (k == 0) ? 2.0f : 0.5f;
            int nw = Math.round(w * sc), nh = Math.round(h * sc);
            if (nw < 40 || nh < 40 || nw > 4000 || nh > 4000) continue;
            Bitmap b2 = null;
            try {
                b2 = Bitmap.createScaledBitmap(src, nw, nh, true);
                String s = tryDecode(b2);
                if (s != null) return s;
            } catch (Exception e) {
                Log.w(TAG, "scale decode failed", e);
            } finally {
                if (b2 != null && b2 != src && !b2.isRecycled()) b2.recycle();
            }
        }
        return null;
    }

    static String tryDecode(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        try {
            LuminanceSource ls = new RGBLuminanceSource(w, h, px);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(ls));
            Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            Result r = new MultiFormatReader().decode(bb, hints);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }
}
