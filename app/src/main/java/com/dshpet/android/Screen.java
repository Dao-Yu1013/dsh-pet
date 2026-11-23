package com.dshpet.android;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

/** 统一读取当前真实屏幕尺寸，横竖屏切换后不会继续用旧方向的分辨率。 */
public final class Screen {
    private Screen() {
    }

    public static int[] size(Context c) {
        int sw = 0;
        int sh = 0;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                sw = m.widthPixels;
                sh = m.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        if (sw <= 0 || sh <= 0) {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            sw = m.widthPixels;
            sh = m.heightPixels;
        }
        return new int[]{sw, sh};
    }
}
