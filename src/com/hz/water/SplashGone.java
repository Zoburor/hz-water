package com.hz.water;

import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 启动层淡出结束后的收尾: 从父容器移除, 避免一直占用一层合成.
 * 独立的顶层类, 不用匿名内部类(D8 8.2.2 对匿名类的 NestHost 有 NPE bug).
 */
final class SplashGone implements Runnable {

    final MainActivity act;

    SplashGone(MainActivity a) {
        act = a;
    }

    @Override
    public void run() {
        FrameLayout root = (FrameLayout) act.splash.getParent();
        if (root != null) {
            root.removeView(act.splash);
        }
        act.splash = null;
        ViewGroup.LayoutParams lp = act.web.getLayoutParams();
        if (lp != null) {
            act.web.requestLayout();
        }
    }
}
