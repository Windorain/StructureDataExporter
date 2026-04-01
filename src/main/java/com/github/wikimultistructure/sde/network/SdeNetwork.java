package com.github.wikimultistructure.sde.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.wikimultistructure.sde.core.session.ExportSession;
import com.github.wikimultistructure.sde.core.session.SelectionSnapshot;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelectionHandler;

import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 服务端向客户端同步选区线框。
 * <p>
 * 通道名须短：GTNH 的 Hodgepodge 等对 {@code S3FPacketCustomPayload} 通道字符串有 ≤20 字符校验。
 */
public final class SdeNetwork {

    /** 与 modid 无关；仅用于 FML SimpleNetworkWrapper 注册名 */
    private static final String NETWORK_CHANNEL = "sde";

    private static SimpleNetworkWrapper channel;
    private static boolean initialized;

    private SdeNetwork() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        channel = new SimpleNetworkWrapper(NETWORK_CHANNEL);
        channel.registerMessage(PacketSyncSelectionHandler.class, PacketSyncSelection.class, 0, Side.CLIENT);
    }

    public static void sendSelectionSync(EntityPlayerMP player) {
        if (channel == null) {
            return;
        }
        SelectionSnapshot snap = ExportSession.get()
            .getSelectionSnapshot();
        channel.sendTo(new PacketSyncSelection(snap), player);
    }

    public static void sendSelectionToPlayer(EntityPlayerMP player) {
        sendSelectionSync(player);
    }
}
