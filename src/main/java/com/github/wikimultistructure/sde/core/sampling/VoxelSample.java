package com.github.wikimultistructure.sde.core.sampling;

import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;

/**
 * 单格采样结果，对应 Wiki palette 中一项（无 NBT 时默认实现）。
 * 对 {@code gregtech:gt.blockmachines}，{@link #meta} 为 GT5U MetaTile ID（mID），与 {@code block_registry} 键 {@code registryId@n} 对齐，见 {@link GregTechMetaTileRegistry}；
 * 其他方块为世界 block metadata（0–15）。
 * <p>
 * 可选 {@link #facing}：机器正面在世界中的外法线（Wiki {@code FaceName}，如 {@code -z}）；与 block_registry 以北为正面一致。
 */
public final class VoxelSample {

    public final String registryId;
    /** 一般为世界 meta；GT 机器块为 mID。 */
    public final int meta;
    /**
     * Wiki palette 的 {@code facing}；{@code null} 表示默认朝北（-z），与旧数据兼容。
     */
    public final String facing;

    public VoxelSample(String registryId, int meta) {
        this(registryId, meta, null);
    }

    public VoxelSample(String registryId, int meta, String facing) {
        this.registryId = registryId;
        this.meta = meta;
        this.facing = facing;
    }

    public static String key(String registryId, int meta) {
        return key(registryId, meta, null);
    }

    public static String key(String registryId, int meta, String facing) {
        if (facing == null || facing.isEmpty()) {
            return registryId + '\0' + meta;
        }
        return registryId + '\0' + meta + '\0' + facing;
    }

    public String key() {
        return key(registryId, meta, facing);
    }
}
