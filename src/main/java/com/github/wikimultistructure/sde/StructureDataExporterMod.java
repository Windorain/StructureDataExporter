package com.github.wikimultistructure.sde;

import com.github.wikimultistructure.sde.config.SdeAutomationConfig;
import com.github.wikimultistructure.sde.item.SdeItems;
import com.github.wikimultistructure.sde.proxy.IProxy;
import com.github.wikimultistructure.sde.server.command.CommandSde;

import com.github.wikimultistructure.sde.server.SdeServerRecordTickHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(modid = StructureDataExporterMod.MODID, version = StructureDataExporterMod.VERSION)
public class StructureDataExporterMod {

    public static final String MODID = "structuredataexporter";
    public static final String VERSION = "1.0.0";

    /** 1.7.10 集成服下 FML init 的 Side 可能仅有 CLIENT，tick 监听器在 {@link FMLServerStartingEvent} 注册。 */
    private static boolean sdeServerRecordTickHandlerRegistered;

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
        if (!sdeServerRecordTickHandlerRegistered) {
            FMLCommonHandler.instance()
                .bus()
                .register(new SdeServerRecordTickHandler());
            sdeServerRecordTickHandlerRegistered = true;
        }
        event.registerServerCommand(new CommandSde());
    }
}
