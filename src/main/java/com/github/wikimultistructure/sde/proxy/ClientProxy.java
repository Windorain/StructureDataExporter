package com.github.wikimultistructure.sde.proxy;

import net.minecraftforge.common.MinecraftForge;

import com.github.wikimultistructure.sde.client.SelectionClientState;
import com.github.wikimultistructure.sde.client.render.SelectionBoxRenderer;
import com.github.wikimultistructure.sde.network.packet.PacketSyncSelection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void initNetwork() {
        super.initNetwork();
        MinecraftForge.EVENT_BUS.register(new SelectionBoxRenderer());
    }

    @Override
    public void applySelectionSync(PacketSyncSelection packet) {
        SelectionClientState.apply(packet);
    }
}
