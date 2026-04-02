package com.github.wikimultistructure.sde.core.registry;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind;
import com.github.wikimultistructure.sde.core.sampling.DefaultBlockSampler;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * GT5U 多方块相关方块白名单：顺序与 {@link GtcBlockRenderKind} 说明一致（子类先于父类）；反射按类名匹配，避免编译期依赖 GregTech。
 * <p>
 * 对 {@code gregtech:gt.blockmachines}，{@link #matches} 仅判断白名单；{@link #sample} 从 Tile 读 mID（见 {@link GregTechMetaTileRegistry}）。
 * 世界 {@code getBlockMetadata} 不可靠，不在此用于机器身份。
 */
public final class GtBlockRegistryWorldPolicy implements BlockRegistryWorldPolicy {

    private static final String[] CLASS_NAMES = { "gregtech.common.blocks.BlockMachines",
        "gregtech.common.blocks.BlockFrameBox", "gregtech.common.blocks.BlockGlass1",
        "gregtech.common.blocks.BlockTintedIndustrialGlass", "gregtech.common.blocks.BlockCyclotronCoils",
        "gregtech.common.blocks.BlockSheetMetal", "gregtech.common.blocks.BlockReinforced",
        "gregtech.common.blocks.BlockCasingsAbstract", };

    private static final GtcBlockRenderKind[] KINDS = { GtcBlockRenderKind.MB_MACHINE, GtcBlockRenderKind.MB_FRAME,
        GtcBlockRenderKind.MB_CASING_GLASS, GtcBlockRenderKind.MB_CASING_GLASS_TINTED,
        GtcBlockRenderKind.MB_COIL_CYCLOTRON, GtcBlockRenderKind.MB_SHEET_CASING, GtcBlockRenderKind.MB_REINFORCED,
        GtcBlockRenderKind.MB_CASING_SOLID, };

    private static final DefaultBlockSampler FALLBACK = new DefaultBlockSampler();

    /**
     * @return 白名单未命中时 {@code null}
     */
    public static GtcBlockRenderKind resolveLogicalKind(Block block) {
        if (block == null) {
            return null;
        }
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            if (isInstance(block, CLASS_NAMES[i])) {
                return KINDS[i];
            }
        }
        return null;
    }

    private static boolean isInstance(Block block, String binaryName) {
        try {
            Class<?> c = Class.forName(binaryName);
            return c.isInstance(block);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean matches(Block block, String registryId, int meta) {
        return resolveLogicalKind(block) != null;
    }

    /**
     * 对 {@code gregtech:gt.blockmachines} 优先从 Tile 读 mID；无 Tile 或反射失败时回退 {@link DefaultBlockSampler}，此时 {@code meta} 为世界 4bit，
     * 可能与 {@code block_registry} 中机器键不一致。
     */
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
                return new VoxelSample(registryId, mId);
            }
        }
        return FALLBACK.sample(world, x, y, z);
    }
}
