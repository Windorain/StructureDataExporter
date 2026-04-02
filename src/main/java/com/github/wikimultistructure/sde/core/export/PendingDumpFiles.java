package com.github.wikimultistructure.sde.core.export;

/**
 * 服务端写出 {@link #FILE_NAME}，客户端轮询同目录并生成全量 block_registry / material_registry（无自定义网络包）。
 */
public final class PendingDumpFiles {

    public static final String FILE_NAME = "pending_dump.json";
    public static final int SCHEMA_VERSION = 1;

    private PendingDumpFiles() {}
}
