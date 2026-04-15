package com.github.wikimultistructure.sde;

import com.github.wikimultistructure.sde.config.SdeAutomationConfig;
import com.github.wikimultistructure.sde.item.SdeItems;
import com.github.wikimultistructure.sde.proxy.IProxy;
import com.github.wikimultistructure.sde.server.command.CommandSde;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(modid = StructureDataExporterMod.MODID, version = StructureDataExporterMod.VERSION)
public class StructureDataExporterMod {

    public static final String MODID = "structuredataexporter";
    public static final String VERSION = "1.0.0";

    @SidedProxy(
        clientSide = "com.github.wikimultistructure.sde.proxy.ClientProxy",
        serverSide = "com.github.wikimultistructure.sde.proxy.CommonProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        SdeAutomationConfig.load(event);
        SdeItems.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.initNetwork();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSde());
    }
}
