package com.github.wikimultistructure.sde.core.sampling;

import net.minecraft.world.World;

/**
 * 方块解析接口：默认实现仅 Block + meta；可替换为带 TE/NBT 的 Enricher。
 */
public interface IBlockSampler {

    VoxelSample sample(World world, int x, int y, int z);
}
