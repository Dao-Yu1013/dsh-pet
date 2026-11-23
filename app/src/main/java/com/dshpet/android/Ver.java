package com.dshpet.android;

/** 版本号唯一出处：打包脚本、关于页面、诊断信息都从这里取，避免各处写死后不一致。 */
public final class Ver {
    public static final String NAME = "2.6.2";
    /** 加固是否启用（由打包脚本决定，这里只是给界面显示用）。 */
    public static final boolean HARDENED = true;

    private Ver() {
    }
}
