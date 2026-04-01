package com.github.wikimultistructure.sde.network.packet;

import com.github.wikimultistructure.sde.StructureDataExporterMod;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public class PacketSyncSelectionHandler implements IMessageHandler<PacketSyncSelection, IMessage> {

    @Override
    public IMessage onMessage(PacketSyncSelection message, MessageContext ctx) {
        StructureDataExporterMod.proxy.applySelectionSync(message);
        return null;
    }
}
