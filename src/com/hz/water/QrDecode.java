package com.hz.water;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码逐帧解码.
 *
 * 从 ScanActivity 里抽出来, 不依赖任何 Android 类, 这样能在 JVM 上直接跑单测
 * (ScanActivity 是 Activity, 在 PC 上跑不起来, 逻辑就只能靠"看过代码"来保证).
 *
 * 输入是相机预览的 NV21 原帧. 预览方向通常是传感器方向(横的), 竖屏要转,
 * 所以先按 rotationDeg 转, 转完解不出来再按原方向试一次 —— 有些设备给的
 * 帧已经是正的了, 多试一次不亏.
 */
public final class QrDecode {

    /** MultiFormatReader 不是线程安全的, 但本类只在一个解码线程里用 */
    private static final MultiFormatReader READER = new MultiFormatReader();

    static {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        READER.setHints(hints);
    }

    private QrDecode() {
    }

    /**
     * 解一帧 NV21.
     *
     * @param yuv         NV21 数据, 长度至少 w*h
     * @param w           帧宽
     * @param h           帧高
     * @param rotationDeg 需要顺时针显示的度数(0/90/180/270)
     * @return 解出的文本, 没解出返回 null
     */
    public static String frame(byte[] yuv, int w, int h, int rotationDeg) {
        if (yuv == null || w <= 0 || h <= 0 || yuv.length < w * h) return null;

        LuminanceSource base;
        try {
            base = new PlanarYUVLuminanceSource(yuv, w, h, 0, 0, w, h, false);
        } catch (Exception e) {
            return null;
        }

        /*
         * 不能只试一个方向.
         *
         * setDisplayOrientation 只影响预览显示, 不改 preview callback 交上来的数据方向.
         * 模拟器上实测到 rotationDeg=0 却给出 1280x720 的横帧 —— 竖屏拿到的就是躺倒的码,
         * 按 0 度解必然失败. 各家厂商对 sensor orientation 的报法也不一致, 所以:
         *   先试 rotationDeg 推出来的方向(正常情况下一次就中), 再依次补试另外三个.
         * 解不出来才多花钱, 解出来就返回, 稳态仍是每帧一次解码.
         */
        int first = (((rotationDeg % 360) + 360) % 360) / 90;
        int[] order = new int[]{first, (first + 1) % 4, (first + 2) % 4, (first + 3) % 4};

        for (int i = 0; i < order.length; i++) {
            int turns = order[i];
            LuminanceSource cur = rotate(base, turns);
            if (cur == null) continue;
            String r = tryOne(cur);
            if (r != null) return r;
        }
        return null;
    }

    /** 逆时针转 turns 个 90 度; 不支持旋转时返回 null */
    private static LuminanceSource rotate(LuminanceSource base, int turns) {
        if (turns == 0) return base;
        if (!base.isRotateSupported()) return null;
        LuminanceSource cur = base;
        try {
            for (int i = 0; i < turns; i++) {
                LuminanceSource next = cur.rotateCounterClockwise();
                if (next == null) return null;
                cur = next;
            }
        } catch (Exception e) {
            return null;
        }
        return cur;
    }

    static String tryOne(LuminanceSource ls) {
        if (ls == null) return null;
        try {
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(ls));
            Result r = READER.decodeWithState(bb);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                READER.reset();
            } catch (Exception e) {
            }
        }
    }
}
