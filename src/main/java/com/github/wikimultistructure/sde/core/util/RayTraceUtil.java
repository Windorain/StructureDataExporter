package com.github.wikimultistructure.sde.core.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

/** 玩家视线与方块交点（创世神式选角点）。 */
public final class RayTraceUtil {

    public static final double DEFAULT_REACH = 128.0;

    private RayTraceUtil() {}

    /**
     * @return 命中方块时返回 {@link MovingObjectPosition}，否则 null 或非 BLOCK。
     */
    public static MovingObjectPosition rayTraceBlock(EntityPlayer player, double reach) {
        return player.rayTrace(reach, 1.0F);
    }

    public static boolean isBlockHit(MovingObjectPosition mop) {
        return mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }
}
