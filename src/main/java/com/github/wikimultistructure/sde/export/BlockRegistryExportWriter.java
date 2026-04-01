package com.github.wikimultistructure.sde.export;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 写出与 Wiki {@code block_registry.json} 同形的独立文件：palette 键 → {@code meshKind}、
 * {@code logicalKind}、{@code occludesAdjacentFaces}；白名单命中为 {@code SimpleCube}，否则
 * {@code Unknown}。{@code faces} 仅占位，由 Wiki 全局表或人工补全。
 */
public final class BlockRegistryExportWriter {

    public static final int SCHEMA_VERSION = 1;

    private BlockRegistryExportWriter() {}

    /**
     * @param palette StructureData 的 {@code palette} 数组（已去重并集）
     */
    public static JsonObject buildRegistryRoot(JsonArray palette) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject blocks = new JsonObject();
        for (int i = 0; i < palette.size(); i++) {
            JsonObject p = palette.get(i).getAsJsonObject();
            String registryId = p.get("registryId").getAsString();
            int meta = p.get("meta").getAsInt();
            if ("air".equals(registryId)) {
                continue;
            }
            String key = meta == 0 ? registryId : registryId + "@" + meta;
            if (blocks.has(key)) {
                continue;
            }
            Block block = resolveBlock(registryId);
            GtcBlockRenderKind kind = BlockRenderKindResolver.resolve(block);
            boolean occludes = block != null && block.isOpaqueCube();
            JsonObject entry = new JsonObject();
            entry.add("faces", new JsonObject());
            if (kind != null) {
                entry.addProperty("meshKind", "SimpleCube");
                entry.addProperty("logicalKind", kind.name());
            } else {
                entry.addProperty("meshKind", "Unknown");
            }
            entry.addProperty("occludesAdjacentFaces", occludes);
            blocks.add(key, entry);
        }
        root.add("blocks", blocks);
        return root;
    }

    private static Block resolveBlock(String registryId) {
        String[] parts = registryId.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        return GameRegistry.findBlock(parts[0], parts[1]);
    }
}
