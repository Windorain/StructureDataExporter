package com.github.wikimultistructure.sde.proxy;

import net.minecraftforge.common.MinecraftForge;

import com.github.wikimultistructure.sde.client.SelectionClientState;
import com.github.wikimultistructure.sde.client.export.ExportBundleTickHandler;
import com.github.wikimultistructure.sde.client.meshcapture.MeshCaptureClient;
import com.github.wikimultistructure.sde.client.render.SelectionBoxRenderer;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void initNetwork() {
        super.initNetwork();
        MinecraftForge.EVENT_BUS.register(new SelectionBoxRenderer());
        // ClientTickEvent（1.7.10）由 FML 总线派发，勿注册到 MinecraftForge.EVENT_BUS。
        FMLCommonHandler.instance()
            .bus()
            .register(new ExportBundleTickHandler());
    }

    @Override
    public void applySelectionSync(PacketSyncSelection packet) {
        SelectionClientState.apply(packet);
    }

    @Override
    public void enqueueMeshCapturePayload(String fileName, byte[] utf8Json) {
        MeshCaptureClient.enqueuePayload(fileName, utf8Json);
    }
}
