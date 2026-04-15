package com.github.wikimultistructure.sde.core.scan;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.sampling.IBlockSampler;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 选区扫描 → 单文件 <strong>StructureData</strong>（{@code mode=voxelPalette}，Gson {@link JsonObject}）。
 * <p>
 * 服务端写出 {@link #STRUCTURE_DATA_SCHEMA_SCAN}：逻辑 {@code blockPalette} + 空 {@code geometry.quads}、空
 * {@code materialPalette}，须由客户端 {@link com.github.wikimultistructure.sde.client.meshcapture.MeshCaptureService}
 * 烘焙后抬升至 {@link #STRUCTURE_DATA_SCHEMA_FINAL}。
 * <p>
 * 终态几何顶点为<strong>块局部</strong> [0,1]³（相对方块最小角），见
 * {@link com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState} 与
 * {@link com.github.wikimultistructure.sde.client.meshcapture.CaptureCoordinatePolicy}。
 */
public final class StructureScan {

    /** 服务端扫描：逻辑 blockPalette + cellGrid + scanBounds，几何未烘焙。 */
    public static final int STRUCTURE_DATA_SCHEMA_SCAN = 7;

    /** 客户端烘焙完成：blockPalette 含 quads + materialPalette 填齐。 */
    public static final int STRUCTURE_DATA_SCHEMA_FINAL = 8;

    /** @deprecated 旧 capture 流程；请使用 {@link #STRUCTURE_DATA_SCHEMA_FINAL} */
    public static final int STRUCTURE_DATA_SCHEMA_WITH_CAPTURE = 7;

    private StructureScan() {}

    public static JsonObject scanToStructureJson(World world, int minX, int minY, int minZ, int maxX, int maxY,
        int maxZ, String structureId, IBlockSampler sampler) {
        int ax = Math.min(minX, maxX), bx = Math.max(minX, maxX);
        int ay = Math.min(minY, maxY), by = Math.max(minY, maxY);
        int az = Math.min(minZ, maxZ), bz = Math.max(minZ, maxZ);

        int sizeCol = bx - ax + 1;
        int sizeRow = by - ay + 1;
        int sizeZ = bz - az + 1;

        List<VoxelSample> paletteList = new ArrayList<>();
        paletteList.add(new VoxelSample("air", 0));
        Map<String, Integer> paletteIndex = new HashMap<>();
        paletteIndex.put(VoxelSample.key("air", 0), 0);

        int[][][] cellGrid = new int[sizeZ][sizeRow][sizeCol];

        for (int zi = 0; zi < sizeZ; zi++) {
            int z = az + zi;
            for (int ri = 0; ri < sizeRow; ri++) {
                int y = by - ri;
                for (int ci = 0; ci < sizeCol; ci++) {
                    int x = ax + ci;
                    VoxelSample s = sampler.sample(world, x, y, z);
                    String k = s.key();
                    Integer idx = paletteIndex.get(k);
                    if (idx == null) {
                        idx = paletteList.size();
                        paletteList.add(s);
                        paletteIndex.put(k, idx);
                    }
                    cellGrid[zi][ri][ci] = idx;
                }
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", STRUCTURE_DATA_SCHEMA_SCAN);
        root.addProperty("mode", "voxelPalette");
        root.addProperty("id", structureId);
        JsonObject src = new JsonObject();
        src.addProperty("note", "StructureDataExporter scan");
        root.add("source", src);

        JsonArray blockPalette = new JsonArray();
        for (VoxelSample s : paletteList) {
            JsonObject p = new JsonObject();
            p.addProperty("registryId", s.registryId);
            p.addProperty("meta", s.meta);
            if (s.facing != null && !s.facing.isEmpty()) {
                p.addProperty("facing", s.facing);
            }
            if (s.tileNbt != null) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    CompressedStreamTools.writeCompressed(s.tileNbt, baos);
                    p.addProperty("tileNbtB64", Base64.getEncoder()
                        .encodeToString(baos.toByteArray()));
                } catch (Exception ignored) {
                    /* 跳过无法序列化的 TE */
                }
            }
            p.addProperty("renderMode", "BakedQuads");
            JsonObject geometry = new JsonObject();
            geometry.addProperty("encoding", "bakedQuadsJsonV1");
            geometry.add("quads", new JsonArray());
            p.add("geometry", geometry);
            blockPalette.add(p);
        }
        root.add("blockPalette", blockPalette);
        root.add("materialPalette", new JsonArray());

        JsonObject scanBounds = new JsonObject();
        scanBounds.addProperty("minX", ax);
        scanBounds.addProperty("maxY", by);
        scanBounds.addProperty("minZ", az);
        root.add("scanBounds", scanBounds);

        JsonArray cellGridJson = new JsonArray();
        for (int zi = 0; zi < sizeZ; zi++) {
            JsonArray rows = new JsonArray();
            for (int ri = 0; ri < sizeRow; ri++) {
                JsonArray cols = new JsonArray();
                for (int ci = 0; ci < sizeCol; ci++) {
                    cols.add(new JsonPrimitive(cellGrid[zi][ri][ci]));
                }
                rows.add(cols);
            }
            cellGridJson.add(rows);
        }
        root.add("cellGrid", cellGridJson);

        return root;
    }
}
