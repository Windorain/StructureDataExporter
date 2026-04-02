package com.github.wikimultistructure.sde.core.sampling;

import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;

/**
 * 单格采样结果，对应 Wiki palette 中一项（无 NBT 时默认实现）。
 * 对 {@code gregtech:gt.blockmachines}，{@link #meta} 为 GT5U MetaTile ID（mID），与 {@code block_registry} 键 {@code registryId@n} 对齐，见 {@link GregTechMetaTileRegistry}；
 * 其他方块为世界 block metadata（0–15）。
 */
public final class VoxelSample {

    public final String registryId;
    /** 一般为世界 meta；GT 机器块为 mID。 */
    public final int meta;

    public VoxelSample(String registryId, int meta) {
        this.registryId = registryId;
        this.meta = meta;
    }

    public static String key(String registryId, int meta) {
        return registryId + '\0' + meta;
    }

    public String key() {
        return key(registryId, meta);
    }
}
