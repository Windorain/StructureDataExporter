package com.github.wikimultistructure.sde.sampling;

/** 单格采样结果，对应 Wiki palette 中一项（无 NBT 时默认实现）。 */
public final class VoxelSample {

    public final String registryId;
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
