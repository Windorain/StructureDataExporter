package com.github.wikimultistructure.sde.client.meshcapture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Timer;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.github.wikimultistructure.sde.client.export.ExportTextureLocator;
import com.github.wikimultistructure.sde.client.export.MaterialAnimationJson;
import com.github.wikimultistructure.sde.client.export.SceneCompactJson;
import com.github.wikimultistructure.sde.client.export.TextureBlobEmbedder;
import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedBlockInstance;
import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedQuad;
import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState.CapturedVertex;
import com.github.wikimultistructure.sde.client.meshcapture.postrender.MeshCaptureBlockPostRenderContext;
import com.github.wikimultistructure.sde.client.meshcapture.postrender.MeshCaptureBlockPostRenderRegistry;
import com.github.wikimultistructure.sde.client.meshcapture.primary.BlockPrimaryCaptureContext;
import com.github.wikimultistructure.sde.client.meshcapture.primary.BlockPrimaryCaptureRegistry;
import com.github.wikimultistructure.sde.core.session.SdeCellCoords;
import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.github.wikimultistructure.sde.mixin.interfaces.accessors.TextureMapAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-only：读取 {@code geometryPhase=scan}，在客户端世界坐标下烘焙 BakedQuads，按几何指纹合并槽位，写出 {@code geometryPhase=baked}（无根级
 * {@code schemaVersion}，{@code blockPalette} 无 {@code tileNbtB64}）。
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
     *
     * @param writeRaw {@code true} 时写出明文 Raw（含 {@code documentFormat: Raw}）；默认 {@code false} 为 Compact 信封。
     */
    public static void enrichAndWriteClientExport(String fileName, byte[] utf8Json, boolean writeRaw) throws Exception {
        JsonParser parser = new JsonParser();
        JsonObject root = parser.parse(new String(utf8Json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        finalizeStructureJson(root);
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "structure_exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot mkdir: " + dir.getAbsolutePath());
        }
        File out = new File(dir, fileName);
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(out),
            StandardCharsets.UTF_8)) {
            if (writeRaw) {
                root.addProperty("documentFormat", "Raw");
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .create();
                w.write(gson.toJson(root));
            } else {
                JsonObject envelope = SceneCompactJson.toCompactEnvelope(root);
                w.write(new com.google.gson.Gson().toJson(envelope));
            }
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
     * 就地修改 root：单文件 {@code geometryPhase=scan} 或 World 文档内每一帧的 {@code structure} 均执行烘焙。
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
            integrateSdeCellNotesWorldRoot(root);
            if (!root.has("tooltipPalette")) {
                root.add("tooltipPalette", new com.google.gson.JsonArray());
            }
            TextureBlobEmbedder.embedIntoDocument(root);
            return;
        }
        if (root.has("geometryPhase") && "scan".equals(
            root.get("geometryPhase")
                .getAsString())) {
            finalizeSingleStructure(root);
            integrateSdeCellNotesSingleBakedRoot(root);
            TextureBlobEmbedder.embedIntoDocument(root);
            return;
        }
        if (root.has("geometryPhase") && "baked".equals(
            root.get("geometryPhase")
                .getAsString())
            && !root.has("textureBlobs")) {
            TextureBlobEmbedder.embedIntoDocument(root);
        }
    }

    /**
     * 将 scan 中 {@code sdeCellNotes} 并入库根 {@code tooltipPalette} + 各帧 {@code cellTooltipGrid}，再移除临时字段（与
     * WebStructureRenderer 一致）。
     */
    private static void integrateSdeCellNotesWorldRoot(JsonObject worldRoot) {
        if (worldRoot == null || !worldRoot.has("frames")) {
            return;
        }
        JsonArray frames = worldRoot.getAsJsonArray("frames");
        List<String> palette = new ArrayList<>();
        Map<String, Integer> stringToIndex = new HashMap<>();
        for (int i = 0; i < frames.size(); i++) {
            JsonObject fr = frames.get(i)
                .getAsJsonObject();
            if (!fr.has("structure")) {
                continue;
            }
            JsonObject st = fr.getAsJsonObject("structure");
            collectSdeNoteStringsForPalette(st.get("sdeCellNotes"), stringToIndex, palette);
        }
        if (palette.isEmpty()) {
            return;
        }
        JsonArray palJson = new JsonArray();
        for (String s : palette) {
            palJson.add(new JsonPrimitive(s));
        }
        worldRoot.add("tooltipPalette", palJson);
        for (int i = 0; i < frames.size(); i++) {
            JsonObject fr = frames.get(i)
                .getAsJsonObject();
            if (!fr.has("structure")) {
                continue;
            }
            JsonObject st = fr.getAsJsonObject("structure");
            applySdeCellNotesToBakedStructure(st, stringToIndex);
        }
    }

    private static void collectSdeNoteStringsForPalette(JsonElement sdeCellNotes, Map<String, Integer> stringToIndex,
        List<String> palette) {
        if (sdeCellNotes == null || !sdeCellNotes.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : sdeCellNotes.getAsJsonObject()
            .entrySet()) {
            if (!e.getValue()
                .isJsonPrimitive()) {
                continue;
            }
            String t = e.getValue()
                .getAsString();
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (!stringToIndex.containsKey(t)) {
                stringToIndex.put(t, Integer.valueOf(palette.size()));
                palette.add(t);
            }
        }
    }

    private static void applySdeCellNotesToBakedStructure(JsonObject st, Map<String, Integer> stringToIndex) {
        if (st == null || !st.has("cellGrid")) {
            return;
        }
        int[] dim = readCellGridDimensions3(st.getAsJsonArray("cellGrid"));
        if (dim == null) {
            return;
        }
        int sizeZ = dim[0], sizeRow = dim[1], sizeCol = dim[2];
        int[][][] ttg = new int[sizeZ][sizeRow][sizeCol];
        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    ttg[zi][ri][ci] = -1;
                }
            }
        }
        if (st.has("sdeCellNotes") && st.get("sdeCellNotes")
            .isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : st.getAsJsonObject("sdeCellNotes")
                .entrySet()) {
                if (!e.getValue()
                    .isJsonPrimitive()) {
                    continue;
                }
                int[] zrc = SdeCellCoords.tryParseCellKey(e.getKey());
                if (zrc == null
                    || zrc[0] < 0
                    || zrc[0] >= sizeZ
                    || zrc[1] < 0
                    || zrc[1] >= sizeRow
                    || zrc[2] < 0
                    || zrc[2] >= sizeCol) {
                    continue;
                }
                String t = e.getValue()
                    .getAsString();
                if (t == null || t.isEmpty()) {
                    continue;
                }
                Integer id = stringToIndex.get(t);
                if (id == null) {
                    continue;
                }
                ttg[zrc[0]][zrc[1]][zrc[2]] = id.intValue();
            }
        }
        st.add("cellTooltipGrid", cellGridToJson(ttg));
        st.remove("sdeCellNotes");
    }

    private static int[] readCellGridDimensions3(JsonArray cellGrid) {
        if (cellGrid == null || cellGrid.size() == 0) {
            return null;
        }
        try {
            int sizeZ = cellGrid.size();
            JsonArray row0 = cellGrid.get(0)
                .getAsJsonArray();
            int sizeRow = row0.size();
            int sizeCol = row0.get(0)
                .getAsJsonArray()
                .size();
            return new int[] { sizeZ, sizeRow, sizeCol };
        } catch (Exception e) {
            return null;
        }
    }

    private static void integrateSdeCellNotesSingleBakedRoot(JsonObject root) {
        if (root == null || !root.has("sdeCellNotes") || !root.get("sdeCellNotes")
            .isJsonObject()) {
            return;
        }
        List<String> palette = new ArrayList<>();
        Map<String, Integer> stringToIndex = new HashMap<>();
        collectSdeNoteStringsForPalette(root.get("sdeCellNotes"), stringToIndex, palette);
        if (palette.isEmpty()) {
            root.remove("sdeCellNotes");
            return;
        }
        JsonArray palJson = new JsonArray();
        for (String s : palette) {
            palJson.add(new JsonPrimitive(s));
        }
        root.add("tooltipPalette", palJson);
        applySdeCellNotesToBakedStructure(root, stringToIndex);
    }

    private static void finalizeSingleStructure(JsonObject structure) throws Exception {
        if (structure == null || !structure.has("cellGrid")) {
            return;
        }
        if (!structure.has("geometryPhase") || !"scan".equals(
            structure.get("geometryPhase")
                .getAsString())) {
            return;
        }
        if (!structure.has("cellTypes") || !structure.has("worldGrid")) {
            throw new IllegalStateException("geometryPhase=scan finalize requires cellTypes and worldGrid.");
        }
        structure.remove("capture");
        structure.remove("schemaVersion");

        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            throw new IllegalStateException("finalize requires Minecraft.theWorld (client).");
        }

        JsonArray cellTypesJson = structure.getAsJsonArray("cellTypes");
        JsonArray gridArr = structure.getAsJsonArray("cellGrid");
        JsonArray worldGridArr = structure.getAsJsonArray("worldGrid");
        VoxelSample[] cellTypes = parseCellTypes(cellTypesJson);
        int[][][] cellGrid = parseCellGrid(gridArr);
        int[][][][] worldCoords = parseWorldGrid(worldGridArr, cellGrid);

        int sizeZ = cellGrid.length;
        int sizeRow = cellGrid[0].length;
        int sizeCol = cellGrid[0][0].length;

        validateWorldGridChunks(world, cellGrid, worldCoords);

        RenderBlocks rb = new RenderBlocks(world);
        rb.renderAllFaces = true;
        rb.blockAccess = world;

        TextureMap textureMap = Minecraft.getMinecraft()
            .getTextureMapBlocks();
        SamplerTable samplers = new SamplerTable();

        Map<Integer, JsonObject> geometryByCellType = new HashMap<>();
        Map<Integer, Boolean> opaqueByCellType = new HashMap<>();

        for (int ti = 0; ti < cellTypes.length; ti++) {
            VoxelSample vs = cellTypes[ti];
            if ("air".equals(vs.registryId) && ti == 0) {
                continue;
            }
            if ("air".equals(vs.registryId)) {
                throw new IllegalStateException("cellTypes[" + ti + "] is air but index != 0.");
            }
            int[] cell = findFirstCellForPaletteIndex(cellGrid, ti);
            if (cell == null) {
                geometryByCellType.put(ti, emptyGeometryJson());
                opaqueByCellType.put(ti, false);
                continue;
            }
            int zi = cell[0];
            int ri = cell[1];
            int ci = cell[2];
            int wx = worldCoords[zi][ri][ci][0];
            int wy = worldCoords[zi][ri][ci][1];
            int wz = worldCoords[zi][ri][ci][2];

            Block b = world.getBlock(wx, wy, wz);
            if (b == null || b == Blocks.air) {
                geometryByCellType.put(ti, emptyGeometryJson());
                opaqueByCellType.put(ti, false);
                continue;
            }
            String label = captureLabel(vs, b, world, wx, wy, wz);
            CapturedBlockInstance inst = new CapturedBlockInstance();
            /*
             * 必须与当帧 scan JSON 的 cellTypes[ti] 一致：多帧联录后一次性 finalize 时，客户端
             * world.getBlockMetadata 往往已是“最后一帧”世界态；若用此处元数据，各帧几何会与 record 时服务端
             * 采样的体素态脱节（GT 等 mID 在 VoxelSample.meta / TE，而非客户端此时 blockMetadata）。
             */
            int blockMeta = vs != null ? vs.meta : world.getBlockMetadata(wx, wy, wz);
            int renderType = b.getRenderType();
            /*
             * ISBRH/TESR 从世界 TE 读状态；多帧时客户端 TE 多为最终态。临时写入 scan 内服务端 NBT 再恢复。
             * TileMultipart 未覆写 readFromNBT — 直接调用不会更新 parts 的取向等内部态，
             * 需额外通过 MultipartNbtStateUtil 反射 part.load() 逐 part 注入。
             */
            TileEntity teForCapture = world.getTileEntity(wx, wy, wz);
            NBTTagCompound teCaptureBackup = null;
            boolean teIsMultipart = false;
            if (teForCapture != null && vs != null && vs.tileNbt != null) {
                teCaptureBackup = new NBTTagCompound();
                teForCapture.writeToNBT(teCaptureBackup);
                teIsMultipart = MultipartNbtStateUtil.isTileMultipart(teForCapture);
                NBTTagCompound toApply = (NBTTagCompound) vs.tileNbt.copy();
                toApply.setInteger("x", teForCapture.xCoord);
                toApply.setInteger("y", teForCapture.yCoord);
                toApply.setInteger("z", teForCapture.zCoord);
                try {
                    teForCapture.readFromNBT(toApply);
                } catch (Throwable ignored) {
                    /* 个别模组 NBT 不兼容时仍走原 TE */
                }
                MultipartNbtStateUtil.diagnoseReadFromNbtOverride(teForCapture);
                if (teIsMultipart) {
                    MultipartNbtStateUtil.loadPartStatesFromNbt(teForCapture, toApply);
                }
            }
            try {
                TessellatorCaptureState.beginBlock(wx, wy, wz, label, wx, wy, wz, b, blockMeta, renderType, vs.registryId);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                BlockPrimaryCaptureRegistry.dispatch(
                    new BlockPrimaryCaptureContext(world, wx, wy, wz, b, blockMeta, rb, partialTicksForMeshCapture()));
                /*
                 * 结束静态批次再 dispatch：FMP/PR 的 renderDynamic 内会 CCRenderState#startDrawingInstance →
                 * Tessellator#startDrawing。若外层 startDrawingQuads 尚未 draw，将 IllegalStateException: Already tesselating
                 * （与 Vector3/ClassLoader 反射无关）。
                 */
                tess.draw();

                MeshCaptureBlockPostRenderRegistry.dispatch(
                    new MeshCaptureBlockPostRenderContext(
                        world,
                        wx,
                        wy,
                        wz,
                        b,
                        blockMeta,
                        rb,
                        partialTicksForMeshCapture()));
                int quadsBeforeDraw = TessellatorCaptureState.currentBlockRecordedQuadCount();
                try {
                    tess.draw();
                } catch (Throwable ignored) {
                    /* 动态路径常已在内部 drawInstance；此时不再处于绘制中 */
                }
                if (quadsBeforeDraw == 0) {
                    GL11.glPushMatrix();
                    try {
                        rb.renderBlockAsItem(b, blockMeta, 1.0F);
                    } catch (Throwable ignored) {
                        /* 少数方块在库存路径下可能抛错 */
                    } finally {
                        GL11.glPopMatrix();
                    }
                }
                TessellatorCaptureState.endBlock(inst);
                if (inst.quads.isEmpty()) {
                    geometryByCellType.put(ti, emptyGeometryJson());
                    opaqueByCellType.put(ti, false);
                } else {
                    attachMaterials(inst, textureMap, samplers);
                    JsonObject geo = bakedQuadsGeometryFromCapture(inst);
                    geometryByCellType.put(ti, geo);
                    opaqueByCellType.put(ti, b.isOpaqueCube());
                }
            } finally {
                if (teForCapture != null && teCaptureBackup != null) {
                    try {
                        teForCapture.readFromNBT(teCaptureBackup);
                    } catch (Throwable ignored) { /* 尽力恢复 */ }
                    if (teIsMultipart) {
                        MultipartNbtStateUtil.loadPartStatesFromNbt(teForCapture, teCaptureBackup);
                    }
                }
            }
        }

        /* 几何指纹合并 → blockPalette + remapped cellGrid */
        int nextFinal = 1;
        Map<String, Integer> sigToFinal = new HashMap<>();
        Map<Integer, Integer> cellTypeToFinal = new HashMap<>();
        cellTypeToFinal.put(0, 0);
        Map<Integer, VoxelSample> repForFinal = new HashMap<>();
        Map<Integer, JsonObject> geometryForFinal = new HashMap<>();
        Map<Integer, Boolean> opaqueForFinal = new HashMap<>();

        for (int ti = 1; ti < cellTypes.length; ti++) {
            JsonObject geom = geometryByCellType.get(ti);
            if (geom == null) {
                geom = emptyGeometryJson();
            }
            String sig = geometrySignature(geom);
            Integer fin = sigToFinal.get(sig);
            if (fin == null) {
                fin = Integer.valueOf(nextFinal++);
                sigToFinal.put(sig, fin);
                repForFinal.put(fin, cellTypes[ti]);
                geometryForFinal.put(fin, deepCopyJsonObject(geom));
                opaqueForFinal.put(fin, opaqueByCellType.getOrDefault(ti, false));
            }
            cellTypeToFinal.put(ti, fin);
        }

        JsonArray blockPalette = new JsonArray();
        blockPalette.add(airBlockPaletteEntry());
        for (int fi = 1; fi < nextFinal; fi++) {
            VoxelSample r = repForFinal.get(fi);
            JsonObject p = new JsonObject();
            p.addProperty("registryId", r.registryId);
            p.addProperty("meta", r.meta);
            if (r.facing != null && !r.facing.isEmpty()) {
                p.addProperty("facing", r.facing);
            }
            p.addProperty("renderMode", "BakedQuads");
            p.add("geometry", deepCopyJsonObject(geometryForFinal.get(fi)));
            p.addProperty(
                "occludesAdjacentFaces",
                opaqueForFinal.getOrDefault(fi, false)
                    .booleanValue());
            blockPalette.add(p);
        }

        int[][][] remapped = new int[sizeZ][sizeRow][sizeCol];
        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    int oldIdx = cellGrid[zi][ri][ci];
                    remapped[zi][ri][ci] = cellTypeToFinal.getOrDefault(oldIdx, 0)
                        .intValue();
                }
            }
        }

        structure.add("blockPalette", blockPalette);
        structure.add("cellGrid", cellGridToJson(remapped));
        int[][][] tooltips = new int[sizeZ][sizeRow][sizeCol];
        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    tooltips[zi][ri][ci] = -1;
                }
            }
        }
        structure.add("cellTooltipGrid", cellGridToJson(tooltips));
        structure.add("materialPalette", samplers.toMaterialPalette());
        structure.addProperty("geometryPhase", "baked");
        structure.remove("cellTypes");
        structure.remove("worldGrid");
    }

    private static JsonObject emptyGeometryJson() {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("encoding", "bakedQuadsJsonV1");
        geometry.add("quads", new JsonArray());
        return geometry;
    }

    private static JsonObject airBlockPaletteEntry() {
        JsonObject p = new JsonObject();
        p.addProperty("registryId", "air");
        p.addProperty("meta", 0);
        p.addProperty("renderMode", "BakedQuads");
        p.add("geometry", emptyGeometryJson());
        p.addProperty("occludesAdjacentFaces", false);
        return p;
    }

    /** 稳定几何指纹：四边形排序后，顶点与 UV 量化，SHA-256 Base64。 */
    private static String geometrySignature(JsonObject geometry) {
        try {
            JsonArray quads = geometry.getAsJsonArray("quads");
            List<String> quadLines = new ArrayList<>();
            for (JsonElement qe : quads) {
                JsonObject q = qe.getAsJsonObject();
                int mat = q.get("materialIndex")
                    .getAsInt();
                JsonArray verts = q.getAsJsonArray("vertices");
                List<String> vparts = new ArrayList<>();
                for (JsonElement ve : verts) {
                    JsonObject v = ve.getAsJsonObject();
                    int br = v.has("brightness") ? v.get("brightness")
                        .getAsInt() : 0;
                    int col = v.has("color") ? v.get("color")
                        .getAsInt() : 0;
                    vparts.add(
                        String.format(
                            Locale.ROOT,
                            "%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d",
                            quant4(v.get("x")),
                            quant4(v.get("y")),
                            quant4(v.get("z")),
                            quant4(v.get("u")),
                            quant4(v.get("v")),
                            br,
                            col));
                }
                Collections.sort(vparts);
                StringBuilder sb = new StringBuilder();
                sb.append(mat)
                    .append('|');
                for (String s : vparts) {
                    sb.append(s)
                        .append(';');
                }
                quadLines.add(sb.toString());
            }
            Collections.sort(quadLines);
            String joined = String.join("\n", quadLines);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                .encodeToString(digest);
        } catch (Exception e) {
            return "sig-error:" + String.valueOf(geometry);
        }
    }

    private static double quant4(JsonElement e) {
        double x = e.getAsDouble();
        return Math.round(x * 10000.0) / 10000.0;
    }

    private static JsonObject deepCopyJsonObject(JsonObject src) {
        return new JsonParser().parse(src.toString())
            .getAsJsonObject();
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

    private static VoxelSample[] parseCellTypes(JsonArray arr) throws Exception {
        VoxelSample[] out = new VoxelSample[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonObject t = arr.get(i)
                .getAsJsonObject();
            String id = t.get("registryId")
                .getAsString();
            int meta = t.get("meta")
                .getAsInt();
            String facing = t.has("facing") ? t.get("facing")
                .getAsString() : null;
            NBTTagCompound tileNbt = null;
            if (t.has("tileNbtB64")) {
                String b64 = t.get("tileNbtB64")
                    .getAsString();
                byte[] raw = Base64.getDecoder()
                    .decode(b64);
                tileNbt = CompressedStreamTools.readCompressed(new ByteArrayInputStream(raw));
            }
            out[i] = new VoxelSample(id, meta, facing, null, tileNbt);
        }
        return out;
    }

    private static int[][][][] parseWorldGrid(JsonArray worldGridJson, int[][][] cellGrid) {
        int sizeZ = cellGrid.length;
        int sizeRow = cellGrid[0].length;
        int sizeCol = cellGrid[0][0].length;
        if (worldGridJson.size() != sizeZ) {
            throw new IllegalStateException("worldGrid z depth != cellGrid.");
        }
        int[][][][] w = new int[sizeZ][sizeRow][sizeCol][3];
        for (int zi = 0; zi < sizeZ; zi++) {
            JsonArray rows = worldGridJson.get(zi)
                .getAsJsonArray();
            if (rows.size() != sizeRow) {
                throw new IllegalStateException("worldGrid row count mismatch.");
            }
            for (int ri = 0; ri < sizeRow; ri++) {
                JsonArray cols = rows.get(ri)
                    .getAsJsonArray();
                if (cols.size() != sizeCol) {
                    throw new IllegalStateException("worldGrid col count mismatch.");
                }
                for (int ci = 0; ci < sizeCol; ci++) {
                    int ct = cellGrid[zi][ri][ci];
                    JsonElement el = cols.get(ci);
                    if (ct == 0) {
                        if (!el.isJsonNull()) {
                            throw new IllegalStateException("worldGrid must be null for air cells.");
                        }
                        w[zi][ri][ci][0] = 0;
                        w[zi][ri][ci][1] = 0;
                        w[zi][ri][ci][2] = 0;
                    } else {
                        if (!el.isJsonObject()) {
                            throw new IllegalStateException("worldGrid missing coordinates for non-air cell.");
                        }
                        JsonObject o = el.getAsJsonObject();
                        w[zi][ri][ci][0] = o.get("x")
                            .getAsInt();
                        w[zi][ri][ci][1] = o.get("y")
                            .getAsInt();
                        w[zi][ri][ci][2] = o.get("z")
                            .getAsInt();
                    }
                }
            }
        }
        return w;
    }

    private static void validateWorldGridChunks(World world, int[][][] cellGrid, int[][][][] worldCoords) {
        if (world.getChunkProvider() == null) {
            throw new IllegalStateException("Mesh capture: world has no chunk provider.");
        }
        int sizeZ = cellGrid.length;
        int sizeRow = cellGrid[0].length;
        int sizeCol = cellGrid[0][0].length;
        for (int zi = 0; zi < sizeZ; zi++) {
            for (int ri = 0; ri < sizeRow; ri++) {
                for (int ci = 0; ci < sizeCol; ci++) {
                    if (cellGrid[zi][ri][ci] == 0) {
                        continue;
                    }
                    int wx = worldCoords[zi][ri][ci][0];
                    int wz = worldCoords[zi][ri][ci][2];
                    if (!world.getChunkProvider()
                        .chunkExists(wx >> 4, wz >> 4)) {
                        throw new IllegalStateException(
                            "Mesh capture: chunk not loaded for world block " + wx
                                + ","
                                + worldCoords[zi][ri][ci][1]
                                + ","
                                + wz
                                + ". Load the area on the client before capture.");
                    }
                }
            }
        }
    }

    private static String captureLabel(VoxelSample vs, Block b, World world, int wx, int wy, int wz) {
        if (!"air".equals(vs.registryId)) {
            return vs.key();
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(b);
        String id = uid == null ? b.getUnlocalizedName() : uid.toString();
        return id + '\0' + "meta:" + world.getBlockMetadata(wx, wy, wz);
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

    private static JsonArray cellGridToJson(int[][][] grid) {
        JsonArray cellGridJson = new JsonArray();
        for (int zi = 0; zi < grid.length; zi++) {
            JsonArray rows = new JsonArray();
            for (int ri = 0; ri < grid[zi].length; ri++) {
                JsonArray cols = new JsonArray();
                for (int ci = 0; ci < grid[zi][ri].length; ci++) {
                    cols.add(new JsonPrimitive(grid[zi][ri][ci]));
                }
                rows.add(cols);
            }
            cellGridJson.add(rows);
        }
        return cellGridJson;
    }

    private static int[] findFirstCellForPaletteIndex(int[][][] cellGrid, int paletteIndex) {
        for (int zi = 0; zi < cellGrid.length; zi++) {
            for (int ri = 0; ri < cellGrid[zi].length; ri++) {
                for (int ci = 0; ci < cellGrid[zi][ri].length; ci++) {
                    if (cellGrid[zi][ri][ci] == paletteIndex) {
                        return new int[] { zi, ri, ci };
                    }
                }
            }
        }
        return null;
    }

    private static void attachMaterials(CapturedBlockInstance inst, TextureMap textureMap, SamplerTable samplers) {
        for (CapturedQuad q : inst.quads) {
            String materialKey = MaterialKeyResolver.applySpriteLocalToQuad(q, textureMap);
            String atlasKind = q.materialUsesStandaloneTexture ? null : q.materialAtlasKind;
            q.samplerIndex = samplers
                .indexForMaterial(materialKey, textureMap, q.materialUsesStandaloneTexture, atlasKind);
        }
    }

    static final class SamplerTable {

        private final List<JsonObject> list = new ArrayList<>();
        private final Map<String, Integer> indexByKey = new LinkedHashMap<>();

        int indexForMaterial(String materialKey, TextureMap blocksTextureMap, boolean standaloneFileTexture,
            String paletteAtlasKind) {
            String atlasBand = standaloneFileTexture ? "file" : ("items".equals(paletteAtlasKind) ? "items" : "blocks");
            String cacheKey = standaloneFileTexture ? materialKey + "\0__sde_file_tex" : materialKey + "\0" + atlasBand;
            Integer idx = indexByKey.get(cacheKey);
            if (idx != null) {
                return idx;
            }
            JsonObject s = new JsonObject();
            s.addProperty("texture", materialKey);
            if (standaloneFileTexture) {
                s.add("atlas", JsonNull.INSTANCE);
            } else {
                s.addProperty("atlas", atlasBand);
            }
            s.addProperty("linear", true);
            s.addProperty("useMipmaps", true);
            TextureMap kindMap = "items".equals(paletteAtlasKind)
                ? MaterialKeyResolver.getTextureMapItems(Minecraft.getMinecraft())
                : blocksTextureMap;
            if (kindMap == null || !(kindMap instanceof TextureMapAccessor)) {
                kindMap = blocksTextureMap;
            }
            String kind = inferMaterialKindForSprite(materialKey, kindMap);
            s.addProperty("kind", kind);
            if ("animated".equals(kind)) {
                TextureAtlasSprite sprAnim = MaterialKeyResolver.findSpriteForMaterialKey(materialKey, kindMap);
                String norm = ExportTextureLocator.normalizeLocatorForBundle(materialKey);
                JsonObject anim = MaterialAnimationJson.tryResolveForMaterial(Minecraft.getMinecraft(), norm, sprAnim);
                if (anim != null) {
                    s.add("animation", anim);
                }
            }
            int i = list.size();
            list.add(s);
            indexByKey.put(cacheKey, i);
            return i;
        }

        JsonArray toMaterialPalette() {
            JsonArray a = new JsonArray();
            for (JsonObject s : list) {
                JsonObject m = new JsonObject();
                m.addProperty(
                    "locator",
                    s.get("texture")
                        .getAsString());
                m.addProperty(
                    "kind",
                    s.get("kind")
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
                if (s.has("animation")) {
                    m.add("animation", s.get("animation"));
                }
                a.add(m);
            }
            return a;
        }
    }

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

    /** {@link Minecraft#timer} 在 MCP 中为 private，供 multipart 动态插值与游戏一致。 */
    private static float partialTicksForMeshCapture() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Timer timer = ReflectionHelper.getPrivateValue(Minecraft.class, mc, "timer", "field_71428_T");
            if (timer != null) {
                return timer.renderPartialTicks;
            }
        } catch (Throwable ignored) {
            /* ignore */
        }
        return 0.0F;
    }
}
