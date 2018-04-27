package com.android.systemui.settings;

import android.util.Log;

public final class UsbPanelBrightnessController {
    static {
        System.loadLibrary("setbacklight_jni");
    }

    public UsbPanelBrightnessController() {
        // do nothing
    }

    public static void setBacklightValue(int v) {
        nativeSetBacklightValue(v);
    }

    private static native void nativeSetBacklightValue(int value);

}
