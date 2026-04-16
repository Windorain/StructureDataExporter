package com.github.wikimultistructure.sde.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.wikimultistructure.sde.core.session.ExportSession;
import com.github.wikimultistructure.sde.core.session.SelectionSnapshot;
import com.github.wikimultistructure.sde.network.packet.PacketEnrichExportedScene;
import com.github.wikimultistructure.sde.network.packet.PacketEnrichExportedSceneHandler;
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
        channel.registerMessage(PacketEnrichExportedSceneHandler.class, PacketEnrichExportedScene.class, 1, Side.CLIENT);
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

    /**
     * 将场景 JSON 全文发给执行导出的玩家，由客户端捕获网格后写入本地 {@code structure_exports} 目录下对应文件名。
     */
    public static void sendEnrichExportedScene(EntityPlayerMP player, String fileName, byte[] utf8Json, boolean writeRaw) {
        if (channel == null || fileName == null || fileName.isEmpty() || utf8Json == null || utf8Json.length == 0) {
            return;
        }
        channel.sendTo(new PacketEnrichExportedScene(fileName, utf8Json, writeRaw), player);
    }
}
