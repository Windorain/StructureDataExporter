package com.github.wikimultistructure.sde.client.meshcapture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedBlockInstance;
import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedQuad;
import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedVertex;
import com.github.wikimultistructure.sde.core.scan.StructureScan;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-only：按 blockPalette 槽在客户端世界烘焙 BakedQuads，写入 {@code materialPalette}，抬升 schema 至
 * {@link StructureScan#STRUCTURE_DATA_SCHEMA_FINAL}；删除旧 {@code capture}。
 */
@SideOnly(Side.CLIENT)
public final class MeshCaptureService {

    private MeshCaptureService() {}

    public static void enrichExportFile(File file) throws Exception {
        if (!file.isFile()) {
            throw new IllegalArgumentException("Not a file: " + file);
        }
        JsonParser parser = new JsonParser();
        JsonObject root;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            root = parser.parse(r)
                .getAsJsonObject();
        }
        finalizeStructureJson(root);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(file),
            StandardCharsets.UTF_8)) {
            w.write(gson.toJson(root));
        }
    }

    /**
     * 解析网络负载 JSON，在客户端写出终态 StructureData 至 {@code structure_exports}。
     */
    public static void enrichAndWriteClientExport(String fileName, byte[] utf8Json) throws Exception {
        JsonParser parser = new JsonParser();
        JsonObject root = parser.parse(new String(utf8Json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        finalizeStructureJson(root);
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "structure_exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot mkdir: " + dir.getAbsolutePath());
        }
        File out = new File(dir, fileName);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(out),
            StandardCharsets.UTF_8)) {
            w.write(gson.toJson(root));
        }
    }

    /**
     * @deprecated 请使用 {@link #finalizeStructureJson(JsonObject)}
     */
    @Deprecated
    public static void enrichRoot(JsonObject root) throws Exception {
        finalizeStructureJson(root);
    }

    /**
     * 就地修改 root：单文件 StructureData 或 World 文档内每一帧的 structure 均执行烘焙。
     */
    public static void finalizeStructureJson(JsonObject root) throws Exception {
        if (root.has("frames")) {
            JsonArray frames = root.getAsJsonArray("frames");
            for (int i = 0; i < frames.size(); i++) {
                JsonObject fr = frames.get(i)
                    .getAsJsonObject();
                if (fr.has("structure")) {
                    finalizeSingleStructure(fr.getAsJsonObject("structure"));
                }
            }
            return;
        }
        if (root.has("mode") && "voxelPalette".equals(root.get("mode")
            .getAsString())) {
            finalizeSingleStructure(root);
        }
    }

    private static void finalizeSingleStructure(JsonObject structure) throws Exception {
        if (structure == null || !structure.has("cellGrid")) {
            return;
        }
        ensureBlockPalette(structure);
        if (!structure.has("scanBounds")) {
            throw new IllegalStateException("finalize requires scanBounds for client world mapping.");
        }
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            throw new IllegalStateException("finalize requires Minecraft.theWorld (client).");
        }
        structure.remove("capture");

        JsonArray blockPalette = structure.getAsJsonArray("blockPalette");
        JsonArray gridArr = structure.getAsJsonArray("cellGrid");
        VoxelSample[] voxelByIndex = parseBlockPaletteAsVoxelSamples(blockPalette);
        int[][][] cellGrid = parseCellGrid(gridArr);
        int[] scanBounds = parseScanBounds(structure);

        int sizeZ = cellGrid.length;
        int sizeRow = cellGrid[0].length;
        int sizeCol = cellGrid[0][0].length;
        int minX = 0;
        int minY = 0;
        int minZ = 0;
        int maxX = sizeCol - 1;
        int maxY = sizeRow - 1;
        int maxZ = sizeZ - 1;

        WorldDelegatingBlockAccess blockAccess = new WorldDelegatingBlockAccess(
            world,
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            scanBounds[0],
            scanBounds[1],
            scanBounds[2]);
        validateAllChunksLoaded(world, blockAccess, sizeZ, sizeRow, sizeCol, minX, maxY, minZ);
        RenderBlocks rb = new RenderBlocks(blockAccess);
        rb.renderAllFaces = true;
        rb.blockAccess = blockAccess;

        TextureMap textureMap = Minecraft.getMinecraft()
            .getTextureMapBlocks();
        SamplerTable samplers = new SamplerTable();

        for (int pi = 0; pi < blockPalette.size(); pi++) {
            JsonObject entry = blockPalette.get(pi)
                .getAsJsonObject();
            VoxelSample vs = voxelByIndex[pi];
            if ("air".equals(vs.registryId)) {
                writeEmptyBakedGeometry(entry);
                continue;
            }
            int[] cell = findFirstCellForPaletteIndex(cellGrid, pi);
            if (cell == null) {
                writeEmptyBakedGeometry(entry);
                continue;
            }
            int zi = cell[0];
            int ri = cell[1];
            int ci = cell[2];
            int x = minX + ci;
            int y = maxY - ri;
            int z = minZ + zi;
            Block b = blockAccess.getBlock(x, y, z);
            if (b == null || b == Blocks.air) {
                writeEmptyBakedGeometry(entry);
                continue;
            }
            String label = captureLabel(vs, b, blockAccess, x, y, z);
            CapturedBlockInstance inst = new CapturedBlockInstance();
            int[] wForCapture = new int[3];
            blockAccess.structToWorld(x, y, z, wForCapture);
            int blockMeta = blockAccess.getBlockMetadata(x, y, z);
            int renderType = b.getRenderType();
            TessellatorCaptureState.beginBlock(
                x,
                y,
                z,
                label,
                wForCapture[0],
                wForCapture[1],
                wForCapture[2],
                b,
                blockMeta,
                renderType,
                vs.registryId);
            Tessellator tess = Tessellator.instance;
            tess.startDrawingQuads();
            MeshCaptureRenderPreparation.beforeBlockRender(rb);
            rb.renderBlockByRenderType(b, x, y, z);
            int quadsAfterWorld = TessellatorCaptureState.currentBlockRecordedQuadCount();
            tess.draw();
            if (quadsAfterWorld == 0) {
                TessellatorCaptureState.markInventoryFallbackForActiveCapture();
                GL11.glPushMatrix();
                try {
                    rb.renderBlockAsItem(b, blockMeta, 1.0F);
                } catch (Throwable ignored) {
                    /* 少数方块在假世界/库存路径下可能抛错，跳过即可 */
                } finally {
                    GL11.glPopMatrix();
                }
            }
            TessellatorCaptureState.endBlock(inst);
            if (inst.quads.isEmpty()) {
                writeEmptyBakedGeometry(entry);
            } else {
                attachMaterials(inst, textureMap, samplers);
                entry.add("geometry", bakedQuadsGeometryFromCapture(inst));
                entry.addProperty("renderMode", "BakedQuads");
                entry.addProperty("occludesAdjacentFaces", b.isOpaqueCube());
            }
        }

        structure.add("materialPalette", samplers.toMaterialPalette());
        structure.addProperty("schemaVersion", StructureScan.STRUCTURE_DATA_SCHEMA_FINAL);
    }

    private static void writeEmptyBakedGeometry(JsonObject entry) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("encoding", "bakedQuadsJsonV1");
        geometry.add("quads", new JsonArray());
        entry.add("geometry", geometry);
        entry.addProperty("renderMode", "BakedQuads");
        entry.addProperty("occludesAdjacentFaces", false);
    }

    private static JsonObject bakedQuadsGeometryFromCapture(CapturedBlockInstance inst) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("encoding", "bakedQuadsJsonV1");
        JsonArray quads = new JsonArray();
        for (CapturedQuad q : inst.quads) {
            JsonObject qo = new JsonObject();
            qo.addProperty("materialIndex", q.samplerIndex);
            JsonArray verts = new JsonArray();
            for (CapturedVertex v : q.vertices) {
                JsonObject vo = new JsonObject();
                vo.addProperty("x", v.x);
                vo.addProperty("y", v.y);
                vo.addProperty("z", v.z);
                vo.addProperty("u", v.u);
                vo.addProperty("v", v.v);
                vo.addProperty("brightness", v.brightness);
                vo.addProperty("color", v.colorArgb);
                verts.add(vo);
            }
            qo.add("vertices", verts);
            quads.add(qo);
        }
        geometry.add("quads", quads);
        return geometry;
    }

    private static void ensureBlockPalette(JsonObject structure) {
        if (structure.has("blockPalette")) {
            return;
        }
        if (!structure.has("palette")) {
            throw new IllegalStateException("Structure requires blockPalette or legacy palette.");
        }
        JsonArray old = structure.getAsJsonArray("palette");
        JsonArray neu = new JsonArray();
        for (int i = 0; i < old.size(); i++) {
            JsonObject o = old.get(i)
                .getAsJsonObject();
            JsonObject p = new JsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if ("shellMaterialId".equals(e.getKey())) {
                    continue;
                }
                p.add(e.getKey(), e.getValue());
            }
            p.addProperty("renderMode", "BakedQuads");
            JsonObject geo = new JsonObject();
            geo.addProperty("encoding", "bakedQuadsJsonV1");
            geo.add("quads", new JsonArray());
            p.add("geometry", geo);
            neu.add(p);
        }
        structure.remove("palette");
        structure.add("blockPalette", neu);
        if (!structure.has("materialPalette")) {
            structure.add("materialPalette", new JsonArray());
        }
    }

    private static int[] findFirstCellForPaletteIndex(int[][][] cellGrid, int paletteIndex) {
        for (int zi = 0; zi < cellGrid.length; zi++) {
            for (int ri = 0; ri < cellGrid[zi].length; ri++) {
                for (int ci = 0; ci < cellGrid[zi][ri].length; ci++) {
                    if (cellGrid[zi][ri][ci] == paletteIndex) {
                        return new int[] {
                            zi,
                            ri,
                            ci
                        };
                    }
                }
            }
        }
        return null;
    }

    private static VoxelSample[] parseBlockPaletteAsVoxelSamples(JsonArray palArr) throws Exception {
        VoxelSample[] out = new VoxelSample[palArr.size()];
        for (int i = 0; i < palArr.size(); i++) {
            JsonObject p = palArr.get(i)
                .getAsJsonObject();
            String id = p.get("registryId")
                .getAsString();
            int meta = p.get("meta")
                .getAsInt();
            String facing = p.has("facing") ? p.get("facing")
                .getAsString() : null;
            String shell = p.has("shellMaterialId") ? p.get("shellMaterialId")
                .getAsString() : null;
            NBTTagCompound tileNbt = null;
            if (p.has("tileNbtB64")) {
                String b64 = p.get("tileNbtB64")
                    .getAsString();
                byte[] raw = Base64.getDecoder()
                    .decode(b64);
                tileNbt = CompressedStreamTools.readCompressed(new ByteArrayInputStream(raw));
            }
            out[i] = new VoxelSample(id, meta, facing, shell, tileNbt);
        }
        return out;
    }

    private static int[] parseScanBounds(JsonObject structure) {
        JsonObject b = structure.getAsJsonObject("scanBounds");
        if (!b.has("minX") || !b.has("maxY") || !b.has("minZ")) {
            throw new IllegalStateException("scanBounds requires minX, maxY, minZ.");
        }
        return new int[] {
            b.get("minX")
                .getAsInt(),
            b.get("maxY")
                .getAsInt(),
            b.get("minZ")
                .getAsInt()
        };
    }

    private static void validateAllChunksLoaded(World world, WorldDelegatingBlockAccess access, int sizeZ, int sizeRow,
        int sizeCol, int minX, int maxY, int minZ) {
        if (world.getChunkProvider() == null) {
            throw new IllegalStateException("Mesh capture: world has no chunk provider.");
        }
        int[] w = new int[3];
        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    int x = minX + ci;
                    int y = maxY - ri;
                    int z = minZ + zi;
                    access.structToWorld(x, y, z, w);
                    if (!world.getChunkProvider()
                        .chunkExists(w[0] >> 4, w[2] >> 4)) {
                        throw new IllegalStateException(
                            "Mesh capture: chunk not loaded for world block " + w[0] + "," + w[1] + "," + w[2]
                                + " (structure " + x + "," + y + "," + z + "). Load the area on the client before capture.");
                    }
                }
            }
        }
    }

    private static String captureLabel(VoxelSample vs, Block b, WorldDelegatingBlockAccess access, int x, int y, int z) {
        if (!"air".equals(vs.registryId)) {
            return vs.key();
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(b);
        String id = uid == null ? b.getUnlocalizedName() : uid.toString();
        return id + '\0' + "meta:" + access.getBlockMetadata(x, y, z);
    }

    private static int[][][] parseCellGrid(JsonArray cellGridJson) {
        int sizeZ = cellGridJson.size();
        JsonArray row0 = cellGridJson.get(0)
            .getAsJsonArray();
        int sizeRow = row0.size();
        int sizeCol = row0.get(0)
            .getAsJsonArray()
            .size();
        int[][][] grid = new int[sizeZ][sizeRow][sizeCol];
        for (int zi = 0; zi < sizeZ; zi++) {
            JsonArray rows = cellGridJson.get(zi)
                .getAsJsonArray();
            for (int ri = 0; ri < sizeRow; ri++) {
                JsonArray cols = rows.get(ri)
                    .getAsJsonArray();
                for (int ci = 0; ci < sizeCol; ci++) {
                    grid[zi][ri][ci] = cols.get(ci)
                        .getAsInt();
                }
            }
        }
        return grid;
    }

    private static void attachMaterials(CapturedBlockInstance inst, TextureMap textureMap, SamplerTable samplers) {
        for (CapturedQuad q : inst.quads) {
            String materialKey = MaterialKeyResolver.applySpriteLocalToQuad(q, textureMap);
            q.samplerIndex = samplers.indexForMaterial(materialKey, textureMap);
        }
    }

    static final class SamplerTable {

        private final List<JsonObject> list = new ArrayList<>();
        private final Map<String, Integer> indexByKey = new LinkedHashMap<>();

        int indexForMaterial(String materialKey, TextureMap textureMap) {
            Integer idx = indexByKey.get(materialKey);
            if (idx != null) {
                return idx;
            }
            JsonObject s = new JsonObject();
            s.addProperty("texture", materialKey);
            s.addProperty("atlas", "blocks");
            s.addProperty("linear", true);
            s.addProperty("useMipmaps", true);
            s.addProperty("kind", inferMaterialKindForSprite(materialKey, textureMap));
            int i = list.size();
            list.add(s);
            indexByKey.put(materialKey, i);
            return i;
        }

        JsonArray toMaterialPalette() {
            JsonArray a = new JsonArray();
            for (JsonObject s : list) {
                JsonObject m = new JsonObject();
                m.addProperty("locator", s.get("texture")
                    .getAsString());
                m.addProperty("kind", s.get("kind")
                    .getAsString());
                if (s.has("atlas")) {
                    m.add("atlas", s.get("atlas"));
                }
                if (s.has("linear")) {
                    m.add("linear", s.get("linear"));
                }
                if (s.has("useMipmaps")) {
                    m.add("useMipmaps", s.get("useMipmaps"));
                }
                a.add(m);
            }
            return a;
        }
    }

    /**
     * 图集中精灵若为竖直帧条（高为宽的整数倍且≥2 帧），或 {@link TextureAtlasSprite#getFrameCount()} &gt; 1（如 .mcmeta 动画，每帧为正方形图块），导出为 {@code animated}，与 Wiki 侧仅对 animated 注册 tick 一致。
     */
    private static String inferMaterialKindForSprite(String materialKey, TextureMap textureMap) {
        TextureAtlasSprite spr = MaterialKeyResolver.findSpriteForMaterialKey(materialKey, textureMap);
        if (spr == null) {
            return "static16";
        }
        if (spr.getFrameCount() > 1) {
            return "animated";
        }
        int iw = spr.getIconWidth();
        int ih = spr.getIconHeight();
        if (iw > 0 && ih >= iw * 2 && ih % iw == 0) {
            return "animated";
        }
        return "static16";
    }
}
