package com.github.wikimultistructure.sde.core.registry.gt;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicy;
import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;
import com.github.wikimultistructure.sde.core.sampling.DefaultBlockSampler;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * {@code gregtech:gt.blockmachines}：{@link #sample} 从 Tile 读 mID。
 */
public final class GtBlockMachinesWorldPolicy implements BlockRegistryWorldPolicy {

    private static final DefaultBlockSampler FALLBACK = new DefaultBlockSampler();

    @Override
    public boolean matches(Block block, String registryId, int meta) {
        return GregTechMetaTileRegistry.isGregTechBlockMachines(block, registryId);
    }

    @Override
    public VoxelSample sample(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air || block.getMaterial() == Material.air) {
            return FALLBACK.sample(world, x, y, z);
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(block);
        String registryId = uid == null ? ("unknown:" + block.getUnlocalizedName()) : uid.toString();
        if (GregTechMetaTileRegistry.isGregTechBlockMachines(block, registryId)) {
            Integer mId = GregTechMetaTileRegistry.tryGetMetaTileIdAt(world, x, y, z);
            if (mId != null) {
                String facing = GregTechMetaTileRegistry.tryGetFrontFacingWikiFaceNameAt(world, x, y, z);
                return new VoxelSample(registryId, mId, facing);
            }
        }
        return FALLBACK.sample(world, x, y, z);
    }
}
