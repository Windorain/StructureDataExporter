package com.github.wikimultistructure.sde.core.export;

/**
 * 服务端写出 {@code pending_bundle.json}，客户端轮询同文件生成材质包；不经过自定义网络包。
 */
public final class PendingBundleFiles {

    public static final String FILE_NAME = "pending_bundle.json";
    public static final int SCHEMA_VERSION = 1;

    private PendingBundleFiles() {}
}
