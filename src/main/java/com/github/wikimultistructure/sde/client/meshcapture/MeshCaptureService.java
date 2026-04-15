package com.github.wikimultistructure.sde.client.meshcapture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-only: 按 {@code cellGrid} 遍历结构格，方块/meta/TE/光照均来自客户端 {@link World}（经 {@code scanBounds} 映射），
 * 调色板仅作 label等附加信息；用 {@link RenderBlocks} 绘制并由 {@link TessellatorCaptureState} 捕获四边形。
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
        enrichRoot(root);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(file),
            StandardCharsets.UTF_8)) {
            w.write(gson.toJson(root));
        }
    }

    /**
     * 解析来自网络负载的 JSON，附加 capture 后写入客户端游戏目录 {@code structure_exports}。
     */
    public static void enrichAndWriteClientExport(String fileName, byte[] utf8Json) throws Exception {
        JsonParser parser = new JsonParser();
        JsonObject root = parser.parse(new String(utf8Json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        enrichRoot(root);
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

    /** 就地修改 root：在解包后的 structure 上添加 {@code capture}并抬升 {@code schemaVersion}。 */
    public static void enrichRoot(JsonObject root) throws Exception {
        JsonObject structure = unwrapStructure(root);
        if (structure == null || !structure.has("palette") || !structure.has("cellGrid")) {
            return;
        }
        if (!structure.has("scanBounds")) {
            throw new IllegalStateException("Mesh capture requires scanBounds for client world mapping.");
        }
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            throw new IllegalStateException("Mesh capture requires Minecraft.theWorld (client).");
        }
        JsonObject captureTarget = structure;
        JsonArray palArr = structure.getAsJsonArray("palette");
        JsonArray gridArr = structure.getAsJsonArray("cellGrid");
        VoxelSample[] palette = parsePalette(palArr);
        int[][][] cellGrid = parseCellGrid(gridArr);
        int[] scanBounds = parseScanBounds(captureTarget);

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
        List<CapturedBlockInstance> instances = new ArrayList<>();
        SamplerTable samplers = new SamplerTable();

        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    int x = minX + ci;
                    int y = maxY - ri;
                    int z = minZ + zi;
                    int pi = cellGrid[zi][ri][ci];
                    VoxelSample vs = palette[pi];
                    Block b = blockAccess.getBlock(x, y, z);
                    if (b == null || b == Blocks.air) {
                        continue;
                    }
                    String label = captureLabel(vs, b, blockAccess, x, y, z);
                    CapturedBlockInstance inst = new CapturedBlockInstance();
                    int[] wForCapture = new int[3];
                    blockAccess.structToWorld(x, y, z, wForCapture);
                    TessellatorCaptureState.beginBlock(x, y, z, label, wForCapture[0], wForCapture[1], wForCapture[2]);
                    Tessellator tess = Tessellator.instance;
                    tess.startDrawingQuads();
                    MeshCaptureRenderPreparation.beforeBlockRender(rb);
                    rb.renderBlockByRenderType(b, x, y, z);
                    int rawVertexCount = tessellatorVertexCount(tess);
                    tess.draw();
                    boolean inventoryFallback = false;
                    /*
                     * Angelica：@ThreadSafeISBRH(perThread=true) 的世界路径常不写主线程 Tessellator。
                     * 对任意非空气方块在 rawVertexCount==0 时尝试 {@link RenderBlocks#renderBlockAsItem}（库存 ISBR），
                     * 以便机壳等同为自定义 renderType 的方块也能产生顶点。
                     */
                    if (rawVertexCount == 0) {
                        inventoryFallback = true;
                        GL11.glPushMatrix();
                        try {
                            rb.renderBlockAsItem(b, blockAccess.getBlockMetadata(x, y, z), 1.0F);
                        } catch (Throwable ignored) {
                            // 少数方块在假世界/库存路径下可能抛错，跳过即可
                        } finally {
                            GL11.glPopMatrix();
                        }
                    }
                    TessellatorCaptureState.endBlock(inst);
                    if (!inst.quads.isEmpty()) {
                        attachMaterials(inst, textureMap, samplers);
                        instances.add(inst);
                    }
                }
            }
        }

        JsonObject capture = toJsonCapture(instances, samplers);
        captureTarget.add("capture", capture);
        int prevSv = captureTarget.has("schemaVersion") ? captureTarget.get("schemaVersion")
            .getAsInt() : 6;
        captureTarget.addProperty("schemaVersion", Math.max(prevSv, 7));
    }

    private static int tessellatorVertexCount(Tessellator t) {
        if (t == null) {
            return -1;
        }
        try {
            java.lang.reflect.Field f = Tessellator.class.getDeclaredField("vertexCount");
            f.setAccessible(true);
            return f.getInt(t);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static JsonObject unwrapStructure(JsonObject root) {
        if (root.has("mode") && "voxelPalette".equals(root.get("mode")
            .getAsString())) {
            return root;
        }
        if (root.has("frames")) {
            JsonArray frames = root.getAsJsonArray("frames");
            if (frames.size() > 0) {
                JsonObject fr = frames.get(0)
                    .getAsJsonObject();
                if (fr.has("structure")) {
                    return fr.getAsJsonObject("structure");
                }
            }
        }
        return null;
    }

    /** scanBounds：minX, maxY（顶行）, minZ，与 StructureScan 一致。 */
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

    private static VoxelSample[] parsePalette(JsonArray palArr) throws Exception {
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

    private static int[][][] parseCellGrid(JsonArray zSlices) {
        int sizeZ = zSlices.size();
        JsonArray row0 = zSlices.get(0)
            .getAsJsonArray();
        int sizeRow = row0.size();
        int sizeCol = row0.get(0)
            .getAsJsonArray()
            .size();
        int[][][] grid = new int[sizeZ][sizeRow][sizeCol];
        for (int zi = 0; zi < sizeZ; zi++) {
            JsonArray rows = zSlices.get(zi)
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
            double su = 0, sv = 0;
            for (CapturedVertex v : q.vertices) {
                su += v.u;
                sv += v.v;
            }
            su /= 4.0;
            sv /= 4.0;
            String materialKey = MaterialKeyResolver.resolveMidUv(su, sv, textureMap);
            TextureAtlasSprite spr = MaterialKeyResolver.findSpriteForMaterialKey(materialKey, textureMap);
            List<CapturedVertex> remapped = new ArrayList<>(4);
            for (CapturedVertex v : q.vertices) {
                double u = v.u;
                double vv = v.v;
                if (spr != null) {
                    double minU = spr.getMinU();
                    double maxU = spr.getMaxU();
                    double minV = spr.getMinV();
                    double maxV = spr.getMaxV();
                    double du = maxU - minU;
                    double dvv = maxV - minV;
                    if (du > 1e-9 && dvv > 1e-9) {
                        u = (v.u - minU) / du;
                        vv = (v.v - minV) / dvv;
                    }
                }
                remapped.add(new CapturedVertex(v.x, v.y, v.z, u, vv, v.brightness, v.colorArgb));
            }
            q.vertices.clear();
            q.vertices.addAll(remapped);
            int samplerIndex = samplers.indexForMaterial(materialKey);
            q.materialKey = materialKey;
            q.samplerIndex = samplerIndex;
        }
    }

    private static JsonObject toJsonCapture(List<CapturedBlockInstance> instances, SamplerTable samplers) {
        JsonObject cap = new JsonObject();
        cap.addProperty("schemaVersion", 2);
        cap.addProperty("uvSpace", "spriteLocal");
        cap.add("samplers", samplers.toJson());
        JsonArray instArr = new JsonArray();
        for (CapturedBlockInstance i : instances) {
            JsonObject jo = new JsonObject();
            jo.addProperty("x", i.x);
            jo.addProperty("y", i.y);
            jo.addProperty("z", i.z);
            jo.addProperty("label", i.label);
            JsonArray quads = new JsonArray();
            for (CapturedQuad q : i.quads) {
                JsonObject qo = new JsonObject();
                qo.addProperty("materialKey", q.materialKey);
                qo.addProperty("samplerIndex", q.samplerIndex);
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
            jo.add("quads", quads);
            instArr.add(jo);
        }
        cap.add("instances", instArr);
        return cap;
    }

    static final class SamplerTable {

        private final List<JsonObject> list = new ArrayList<>();
        private final Map<String, Integer> indexByKey = new LinkedHashMap<>();

        int indexForMaterial(String materialKey) {
            Integer idx = indexByKey.get(materialKey);
            if (idx != null) {
                return idx;
            }
            JsonObject s = new JsonObject();
            s.addProperty("texture", materialKey);
            s.addProperty("atlas", "blocks");
            s.addProperty("linear", true);
            s.addProperty("useMipmaps", true);
            int i = list.size();
            list.add(s);
            indexByKey.put(materialKey, i);
            return i;
        }

        JsonArray toJson() {
            JsonArray a = new JsonArray();
            for (JsonObject o : list) {
                a.add(o);
            }
            return a;
        }
    }
}
