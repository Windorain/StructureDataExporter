package com.github.wikimultistructure.sde.client.registry.gt;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.client.export.ExportTextureLocator;
import com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicy;
import com.github.wikimultistructure.sde.client.registry.BlockRegistryTextureProbe;
import com.github.wikimultistructure.sde.core.registry.gt.GregTechBlockReflection;
import com.github.wikimultistructure.sde.core.registry.gt.GtBlockClassWorldPolicy;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 非 {@code gt.blockmachines} 的 GT 方块：类名命中 + 固定 {@code renderProfile}。 */
@SideOnly(Side.CLIENT)
public final class GtBlockClassRegistryPolicy implements BlockRegistryPolicy {

    private final String blockClassBinaryName;
    private final String renderProfile;
    private final GtBlockClassWorldPolicy world;

    public GtBlockClassRegistryPolicy(String blockClassBinaryName, String renderProfile, GtBlockClassWorldPolicy world) {
        this.blockClassBinaryName = blockClassBinaryName;
        this.renderProfile = renderProfile;
        this.world = world;
    }

    @Override
    public boolean matches(Block block, String registryId, int meta) {
        return GregTechBlockReflection.isInstance(block, blockClassBinaryName);
    }

    @Override
    public VoxelSample sample(World worldObj, int x, int y, int z) {
        return world.sample(worldObj, x, y, z);
    }

    @Override
    public void writeBlockRegistryEntry(JsonObject entry, Block block, String registryId, int meta, Minecraft mc) {
        boolean occludes = block.isOpaqueCube();
        entry.addProperty("occludesAdjacentFaces", occludes);
        entry.addProperty("renderProfile", renderProfile);

        String locator = ExportTextureLocator.resolve(block, meta, renderProfile);

        if (locator != null && BlockRegistryTextureProbe.texturePngExistsForLocator(mc, locator)) {
            entry.addProperty("meshKind", "SimpleCube");
            JsonObject faces = new JsonObject();
            JsonObject all = new JsonObject();
            JsonArray layers = new JsonArray();
            JsonObject layer = new JsonObject();
            layer.addProperty("materialId", locator);
            layer.addProperty("layerRole", "base");
            layers.add(layer);
            all.add("layers", layers);
            faces.add("all", all);
            entry.add("faces", faces);
        } else {
            entry.addProperty("meshKind", "SimpleCube");
            entry.add("faces", new JsonObject());
        }
    }
}
