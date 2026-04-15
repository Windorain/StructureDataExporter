package com.github.wikimultistructure.sde.network.packet;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * 将服务端刚写出的场景 JSON 全文发给客户端，由客户端 Tessellator 烘焙 {@code blockPalette.geometry} 与
 * {@code materialPalette} 后写回终态 StructureData（{@code structure_exports}）。
 */
public class PacketEnrichExportedScene implements IMessage {

    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;

    public String fileName;
    public byte[] utf8Json;

    public PacketEnrichExportedScene() {}

    public PacketEnrichExportedScene(String fileName, byte[] utf8Json) {
        this.fileName = fileName;
        this.utf8Json = utf8Json;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        fileName = ByteBufUtils.readUTF8String(buf);
        int n = buf.readInt();
        if (n < 0 || n > MAX_PAYLOAD) {
            utf8Json = new byte[0];
            return;
        }
        utf8Json = new byte[n];
        buf.readBytes(utf8Json);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, fileName == null ? "" : fileName);
        int n = utf8Json == null ? 0 : utf8Json.length;
        buf.writeInt(n);
        if (n > 0) {
            buf.writeBytes(utf8Json);
        }
    }
}
