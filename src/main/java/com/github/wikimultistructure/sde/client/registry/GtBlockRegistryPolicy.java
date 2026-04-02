package com.github.wikimultistructure.sde.client.registry;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.client.export.ExportTextureLocator;
import com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind;
import com.github.wikimultistructure.sde.core.registry.GtBlockRegistryWorldPolicy;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** GregTech 多方块白名单：{@link GtBlockRegistryWorldPolicy} 与 dump 共用命中条件。 */
@SideOnly(Side.CLIENT)
public final class GtBlockRegistryPolicy implements BlockRegistryPolicy {

    private final GtBlockRegistryWorldPolicy world = new GtBlockRegistryWorldPolicy();

    @Override
    public boolean matches(Block block, String registryId, int meta) {
        return world.matches(block, registryId, meta);
    }

    @Override
    public VoxelSample sample(World world, int x, int y, int z) {
        return this.world.sample(world, x, y, z);
    }

    @Override
    public void writeBlockRegistryEntry(JsonObject entry, Block block, String registryId, int meta, Minecraft mc) {
        GtcBlockRenderKind logicalKind = GtBlockRegistryWorldPolicy.resolveLogicalKind(block);
        boolean occludes = block.isOpaqueCube();
        entry.addProperty("occludesAdjacentFaces", occludes);

        String locator = ExportTextureLocator.resolve(block, meta, logicalKind);

        if (locator != null && BlockRegistryTextureProbe.texturePngExistsForLocator(mc, locator)) {
            entry.addProperty("meshKind", "SimpleCube");
            entry.addProperty("logicalKind", logicalKind.name());
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
            entry.addProperty("logicalKind", logicalKind.name());
            entry.add("faces", new JsonObject());
        }
    }
}
