package com.github.wikimultistructure.sde.core.sampling;

import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;

/**
 * 单格采样结果，对应 Wiki palette 中一项（无 NBT 时默认实现）。
 * 对 {@code gregtech:gt.blockmachines}，{@link #meta} 为 GT5U MetaTile ID（mID），与 {@code block_registry} 键 {@code registryId@n} 对齐，见 {@link GregTechMetaTileRegistry}；
 * 其他方块为世界 block metadata（0–15）。
 * <p>
 * 可选 {@link #facing}：机器正面在世界中的外法线（Wiki {@code FaceName}，如 {@code -z}）；与 block_registry 以北为正面一致。
 * <p>
 * 可选 {@link #shellMaterialId}：GT 仓室（MTEHatch）在结构导出时由世界邻格解析的壳层材质 locator（与 {@code material_registry} 键一致）；非仓室或未解析时为 {@code null}。
 */
public final class VoxelSample {

    public final String registryId;
    /** 一般为世界 meta；GT 机器块为 mID。 */
    public final int meta;
    /**
     * Wiki palette 的 {@code facing}；{@code null} 表示默认朝北（-z），与旧数据兼容。
     */
    public final String facing;
    /**
     * 仓室壳层材质 locator（与 block_registry / material_registry 一致）；仅 Hatch 扫描时可能非空。
     */
    public final String shellMaterialId;

    public VoxelSample(String registryId, int meta) {
        this(registryId, meta, null, null);
    }

    public VoxelSample(String registryId, int meta, String facing) {
        this(registryId, meta, facing, null);
    }

    public VoxelSample(String registryId, int meta, String facing, String shellMaterialId) {
        this.registryId = registryId;
        this.meta = meta;
        this.facing = facing;
        this.shellMaterialId = shellMaterialId;
    }

    public static String key(String registryId, int meta) {
        return key(registryId, meta, null, null);
    }

    public static String key(String registryId, int meta, String facing) {
        return key(registryId, meta, facing, null);
    }

    public static String key(String registryId, int meta, String facing, String shellMaterialId) {
        String base;
        if (facing == null || facing.isEmpty()) {
            base = registryId + '\0' + meta;
        } else {
            base = registryId + '\0' + meta + '\0' + facing;
        }
        if (shellMaterialId == null || shellMaterialId.isEmpty()) {
            return base;
        }
        return base + '\0' + shellMaterialId;
    }

    public String key() {
        return key(registryId, meta, facing, shellMaterialId);
    }
}
