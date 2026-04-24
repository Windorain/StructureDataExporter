package com.github.wikimultistructure.sde.network.packet;

import com.github.wikimultistructure.sde.StructureDataExporterMod;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public class PacketOpenCellNoteHandler implements IMessageHandler<PacketOpenCellNote, IMessage> {

    @Override
    public IMessage onMessage(PacketOpenCellNote m, MessageContext ctx) {
        StructureDataExporterMod.proxy
            .openCellNoteEditor(m.frameIndex, m.zSlice, m.row, m.column, m.initialText);
        return null;
    }
}
