package com.github.wikimultistructure.sde.export;

import net.minecraft.block.Block;

/**
 * 仅白名单内 GT 方块类参与解析；顺序与 {@link GtcBlockRenderKind} 说明一致（子类先于父类）。
 * <p>
 * 使用反射按类名匹配，避免编译期依赖 GregTech（运行时在 GT 环境中类存在）。
 */
public final class BlockRenderKindResolver {

    private static final String[] CLASS_NAMES = {
        "gregtech.common.blocks.BlockMachines",
        "gregtech.common.blocks.BlockFrameBox",
        "gregtech.common.blocks.BlockGlass1",
        "gregtech.common.blocks.BlockTintedIndustrialGlass",
        "gregtech.common.blocks.BlockCyclotronCoils",
        "gregtech.common.blocks.BlockSheetMetal",
        "gregtech.common.blocks.BlockReinforced",
        "gregtech.common.blocks.BlockCasingsAbstract",
    };

    private static final GtcBlockRenderKind[] KINDS = {
        GtcBlockRenderKind.MB_MACHINE,
        GtcBlockRenderKind.MB_FRAME,
        GtcBlockRenderKind.MB_CASING_GLASS,
        GtcBlockRenderKind.MB_CASING_GLASS_TINTED,
        GtcBlockRenderKind.MB_COIL_CYCLOTRON,
        GtcBlockRenderKind.MB_SHEET_CASING,
        GtcBlockRenderKind.MB_REINFORCED,
        GtcBlockRenderKind.MB_CASING_SOLID,
    };

    private BlockRenderKindResolver() {}

    /**
     * @return 白名单未命中时 {@code null}（Wiki 侧应使用 {@code meshKind: Unknown}）
     */
    public static GtcBlockRenderKind resolve(Block block) {
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
}
