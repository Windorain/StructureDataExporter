package com.github.wikimultistructure.sde.client.meshcapture;

import net.minecraft.block.Block;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 网格捕获后顶点归一化到「块局部 [0,1]³」契约时采用的策略。
 * <p>
 * {@link #DEFAULT_WORLD_CORNER}：由 {@link TessellatorCaptureState} 对每个 quad 在减 {@code (0,0,0)} 与减世界角之间按贴近 {@code [0,1]³} 的代价择一。
 * <p>
 * {@link #SPECIAL_EXTENDED} 为超单格/多格延伸模型占位：当前与默认分支相同，仅打日志便于后续扩展。
 */
@SideOnly(Side.CLIENT)
public final class CaptureCoordinatePolicy {

    public enum Kind {

        /**
         * 默认：由捕获管线在「减结构格 (blockX,blockY,blockZ)」与「减世界角点 (worldBlock*)」之间自动选择（见 TessellatorCaptureState）。
         */
        DEFAULT_WORLD_CORNER,
        /** 调用链已把坐标落在块局部；仅减 Tessellator offset，不再减世界角点。 */
        ALREADY_BLOCK_LOCAL,
        /** 占位：未来处理超出单格的模型；当前按 {@link #DEFAULT_WORLD_CORNER} 处理。 */
        SPECIAL_EXTENDED,
    }

    private CaptureCoordinatePolicy() {}

    /**
     * @param registryKey    {@code VoxelSample.registryId}，如 {@code minecraft:stone}
     * @param geometrySource 几何来自主路径、库存回退或 TESR post（当前仅库存回退影响日志；TESR 与主路径同属默认归一化）。
     */
    public static Kind resolve(Block block, int meta, int renderType, String registryKey,
        CaptureGeometrySource geometrySource) {
        if (geometrySource == CaptureGeometrySource.INVENTORY_FALLBACK) {
            FMLLog.fine("[SDE] capture used inventory fallback for " + registryKey + " renderType=" + renderType);
        } else if (geometrySource == CaptureGeometrySource.DYNAMIC_EXTENSION) {
            FMLLog.fine("[SDE] capture used TESR post-render for " + registryKey + " renderType=" + renderType);
        }

        if (registryKey != null) {
            Kind k = resolveByRegistry(registryKey, block, meta, renderType);
            if (k != null) {
                return k;
            }
        }

        if (block != null) {
            Kind k = resolveByBlock(block, meta, renderType);
            if (k != null) {
                return k;
            }
        }

        return Kind.DEFAULT_WORLD_CORNER;
    }

    /** 表驱动占位：后续按 mod 前缀或 id 返回 ALREADY_BLOCK_LOCAL / SPECIAL_EXTENDED。 */
    private static Kind resolveByRegistry(String registryKey, Block block, int meta, int renderType) {
        return null;
    }

    private static Kind resolveByBlock(Block block, int meta, int renderType) {
        return null;
    }

    static void logIfSpecialExtended(Kind kind, String registryKey) {
        if (kind == Kind.SPECIAL_EXTENDED) {
            FMLLog.fine(
                "[SDE] SPECIAL_EXTENDED geometry (placeholder) for " + registryKey
                    + " — currently same as DEFAULT_WORLD_CORNER");
        }
    }
}
