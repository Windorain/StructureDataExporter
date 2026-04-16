package com.github.wikimultistructure.sde.client.meshcapture;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 单格网格录制几何来自哪条路径，供 {@link CaptureCoordinatePolicy} 与日志使用。
 */
@SideOnly(Side.CLIENT)
public enum CaptureGeometrySource {

    /** 主 {@link net.minecraft.client.renderer.RenderBlocks} / ISBRH 批次（可含 FMP 等 post 追加但未单独标记）。 */
    PRIMARY,
    /** 主路径与 post 均无 quad 时的 {@link net.minecraft.client.renderer.RenderBlocks#renderBlockAsItem} 回退。 */
    INVENTORY_FALLBACK,
    /**
     * 动态扩展：{@code TileEntitySpecialRenderer}、FMP {@code renderDynamic} 等 TESR 族（主批次 {@code draw} 之后）。
     */
    DYNAMIC_EXTENSION
}
