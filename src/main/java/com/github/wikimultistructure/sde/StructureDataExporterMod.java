package com.github.wikimultistructure.sde;

import com.github.wikimultistructure.sde.command.CommandSde;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(modid = StructureDataExporterMod.MODID, version = StructureDataExporterMod.VERSION)
public class StructureDataExporterMod {

    public static final String MODID = "structuredataexporter";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSde());
    }
}
