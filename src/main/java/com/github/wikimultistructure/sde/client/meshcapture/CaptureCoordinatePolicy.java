package com.github.wikimultistructure.sde.client.meshcapture;

import net.minecraft.block.Block;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 网格捕获后顶点归一化到「块局部 [0,1]³」契约时采用的策略（显式路径，不用 AABB 猜坐标系）。
 * <p>
 * {@link #SPECIAL_EXTENDED} 为超单格/多格延伸模型占位：当前与 {@link #DEFAULT_WORLD_CORNER} 行为一致，仅打日志便于后续扩展。
 */
@SideOnly(Side.CLIENT)
public final class CaptureCoordinatePolicy {

    public enum Kind {

        /** addVertex 入参为世界对齐（块角 + 小数）；应再减 (worldBlockX, worldBlockY, worldBlockZ)。 */
        DEFAULT_WORLD_CORNER,
        /** 调用链已把坐标落在块局部；仅减 Tessellator offset，不再减世界角点。 */
        ALREADY_BLOCK_LOCAL,
        /** 占位：未来处理超出单格的模型；当前按 {@link #DEFAULT_WORLD_CORNER} 处理。 */
        SPECIAL_EXTENDED,
    }

    private CaptureCoordinatePolicy() {}

    /**
     * @param registryKey {@code VoxelSample.registryId}，如 {@code minecraft:stone}
     */
    public static Kind resolve(Block block, int meta, int renderType, String registryKey, boolean inventoryFallback) {
        if (inventoryFallback) {
            FMLLog.fine("[SDE] capture used inventory fallback for " + registryKey + " renderType=" + renderType);
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

    static boolean applyWorldCornerSubtract(Kind kind) {
        return kind == Kind.DEFAULT_WORLD_CORNER || kind == Kind.SPECIAL_EXTENDED;
    }

    static void logIfSpecialExtended(Kind kind, String registryKey) {
        if (kind == Kind.SPECIAL_EXTENDED) {
            FMLLog.fine("[SDE] SPECIAL_EXTENDED geometry (placeholder) for " + registryKey
                + " — currently same as DEFAULT_WORLD_CORNER");
        }
    }
}
