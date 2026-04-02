package com.github.wikimultistructure.sde.client.registry.gt;

import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import com.github.wikimultistructure.sde.client.export.ExportTextureLocator;
import com.github.wikimultistructure.sde.client.export.GtTextureResolver;
import com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicy;
import com.github.wikimultistructure.sde.client.registry.BlockRegistryTextureProbe;
import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;
import com.github.wikimultistructure.sde.core.registry.gt.GtBlockMachinesWorldPolicy;
import com.github.wikimultistructure.sde.core.registry.gt.GtRenderProfiles;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * {@code gregtech:gt.blockmachines}：内层 {@link MetaTileBlockRegistryStrategy} 链（控制器 → 默认），
 * 与 {@link GtBlockMachinesWorldPolicy} 成对世界采样。
 */
@SideOnly(Side.CLIENT)
public final class GtBlockMachinesRegistryPolicy implements BlockRegistryPolicy {

    private static final List<MetaTileBlockRegistryStrategy> META_TILE_CHAIN = Arrays.asList(new GregTechMultiblockControllerRegistryStrategy(),
        new GregTechDefaultMetaTileRegistryStrategy());

    private final GtBlockMachinesWorldPolicy world;

    public GtBlockMachinesRegistryPolicy(GtBlockMachinesWorldPolicy world) {
        this.world = world;
    }

    @Override
    public boolean matches(Block block, String registryId, int meta) {
        return GregTechMetaTileRegistry.isGregTechBlockMachines(block, registryId)
            && GregTechMetaTileRegistry.isMetaTileSlotRegistered(meta);
    }

    @Override
    public VoxelSample sample(World worldObj, int x, int y, int z) {
        return world.sample(worldObj, x, y, z);
    }

    @Override
    public void writeBlockRegistryEntry(JsonObject entry, Block block, String registryId, int meta, Minecraft mc) {
        for (MetaTileBlockRegistryStrategy s : META_TILE_CHAIN) {
            if (s.matches(block, registryId, meta)) {
                s.writeBlockRegistryEntry(entry, block, registryId, meta, mc);
                return;
            }
        }
        GregTechDefaultMetaTileRegistryStrategy fallback = new GregTechDefaultMetaTileRegistryStrategy();
        fallback.writeBlockRegistryEntry(entry, block, registryId, meta, mc);
    }

    static void writeSimpleCubeEntry(JsonObject entry, Block block, int meta, String renderProfile, Minecraft mc) {
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

    /**
     * 多方块主机：{@code faces.all} 为侧面外壳一层；{@code faces.-z} 为正面（与 Wiki 默认朝北 -z 一致）叠加镂空 / glow 层。
     */
    static void writeMultiblockControllerEntry(JsonObject entry, Block block, int meta, Minecraft mc) {
        boolean occludes = block.isOpaqueCube();
        entry.addProperty("occludesAdjacentFaces", occludes);
        entry.addProperty("renderProfile", GtRenderProfiles.MULTIBLOCK_CONTROLLER);
        entry.addProperty("meshKind", "SimpleCube");

        ForgeDirection front = ForgeDirection.NORTH;
        ForgeDirection sideNonFront = ForgeDirection.SOUTH;
        List<String> shell = GtTextureResolver.tryMetaTileEntityLayerLocatorsNormalized(meta, sideNonFront, front);
        List<String> frontFace = GtTextureResolver.tryMetaTileEntityLayerLocatorsNormalized(meta, front, front);

        if (frontFace.size() < 2) {
            writeSimpleCubeEntry(entry, block, meta, GtRenderProfiles.MULTIBLOCK_CONTROLLER, mc);
            return;
        }

        String baseLocator = !shell.isEmpty() ? shell.get(0) : frontFace.get(0);
        if (baseLocator == null || !BlockRegistryTextureProbe.texturePngExistsForLocator(mc, baseLocator)) {
            writeSimpleCubeEntry(entry, block, meta, GtRenderProfiles.MULTIBLOCK_CONTROLLER, mc);
            return;
        }

        JsonObject faces = new JsonObject();
        JsonObject all = new JsonObject();
        JsonArray allLayers = new JsonArray();
        JsonObject baseLayer = new JsonObject();
        baseLayer.addProperty("materialId", baseLocator);
        baseLayer.addProperty("layerRole", "base");
        allLayers.add(baseLayer);
        all.add("layers", allLayers);
        faces.add("all", all);

        JsonArray frontExtra = new JsonArray();
        for (int i = 1; i < frontFace.size(); i++) {
            String loc = frontFace.get(i);
            if (loc == null || !BlockRegistryTextureProbe.texturePngExistsForLocator(mc, loc)) {
                continue;
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("materialId", loc);
            boolean last = i == frontFace.size() - 1;
            layer.addProperty("layerRole", last && frontFace.size() > 2 ? "glass" : "cutout");
            frontExtra.add(layer);
        }
        if (frontExtra.size() > 0) {
            JsonObject minusZ = new JsonObject();
            minusZ.add("layers", frontExtra);
            faces.add("-z", minusZ);
        }
        entry.add("faces", faces);
    }
}
