package com.github.wikimultistructure.sde.core.sampling;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicies;
import com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicy;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * 按 {@link BlockRegistryWorldPolicies} 首个 {@link BlockRegistryWorldPolicy#matches} 采样，否则回退 {@link DefaultBlockSampler}。
 */
public final class PolicyBackedBlockSampler implements IBlockSampler {

    private static final DefaultBlockSampler FALLBACK = new DefaultBlockSampler();

    @Override
    public VoxelSample sample(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return new VoxelSample("air", 0);
        }
        int meta = world.getBlockMetadata(x, y, z);
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(block);
        String registryId = uid == null ? ("unknown:" + block.getUnlocalizedName()) : uid.toString();
        for (BlockRegistryWorldPolicy p : BlockRegistryWorldPolicies.all()) {
            if (p.matches(block, registryId, meta)) {
                return p.sample(world, x, y, z);
            }
        }
        return FALLBACK.sample(world, x, y, z);
    }
}
