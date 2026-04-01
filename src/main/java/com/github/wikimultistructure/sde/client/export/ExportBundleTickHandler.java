package com.github.wikimultistructure.sde.client.export;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

import net.minecraft.client.Minecraft;

/** 每 tick 检查 {@code pending_bundle.json}（服务端落盘），无自定义网络包。 */
public final class ExportBundleTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.side == Side.CLIENT && e.phase == TickEvent.Phase.END) {
            ExportBundleClient.tickConsumePendingIfAny(Minecraft.getMinecraft());
        }
    }
}
