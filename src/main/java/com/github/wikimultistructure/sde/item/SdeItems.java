package com.github.wikimultistructure.sde.item;

import cpw.mods.fml.common.registry.GameRegistry;

public final class SdeItems {

    public static ItemSdeSelectionTool selectionTool;
    public static ItemSdeRecordTool recordTool;

    private SdeItems() {}

    public static void register() {
        selectionTool = new ItemSdeSelectionTool();
        recordTool = new ItemSdeRecordTool();
        GameRegistry.registerItem(selectionTool, "sde_tool_select");
        GameRegistry.registerItem(recordTool, "sde_tool_record");
    }
}
