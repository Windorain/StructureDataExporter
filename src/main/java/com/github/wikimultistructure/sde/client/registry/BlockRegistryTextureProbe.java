package com.github.wikimultistructure.sde.client.registry;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端：locator 对应 PNG 是否在资源包中（供 block_registry 策略使用）。 */
@SideOnly(Side.CLIENT)
public final class BlockRegistryTextureProbe {

    private BlockRegistryTextureProbe() {}

    public static boolean texturePngExistsForLocator(Minecraft mc, String locator) {
        int colon = locator.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String ns = locator.substring(0, colon);
        String path = locator.substring(colon + 1);
        ResourceLocation texLoc = new ResourceLocation(ns, "textures/" + path + ".png");
        return resourceExists(mc, texLoc);
    }

    private static boolean resourceExists(Minecraft mc, ResourceLocation loc) {
        try {
            mc.getResourceManager()
                .getResource(loc);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
