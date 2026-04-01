package com.github.wikimultistructure.sde.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.wikimultistructure.sde.StructureDataExporterMod;
import com.github.wikimultistructure.sde.core.session.ExportSession;
import com.github.wikimultistructure.sde.core.session.SelectionSnapshot;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelectionHandler;

import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** 服务端向客户端同步选区线框数据。 */
public final class SdeNetwork {

    private static SimpleNetworkWrapper channel;
    private static boolean initialized;

    private SdeNetwork() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        channel = new SimpleNetworkWrapper(StructureDataExporterMod.MODID + "_net");
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
