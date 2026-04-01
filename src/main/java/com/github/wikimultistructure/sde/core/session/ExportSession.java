package com.github.wikimultistructure.sde.core.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.export.PendingBundleFiles;
import com.github.wikimultistructure.sde.core.sampling.DefaultBlockSampler;
import com.github.wikimultistructure.sde.core.sampling.IBlockSampler;
import com.github.wikimultistructure.sde.core.scan.StructureScan;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 会话状态、选区、多帧缓冲与落盘（单向：世界 → 内存 JSON 串 → export 写文件）。 */
public final class ExportSession {

    private static final ExportSession INSTANCE = new ExportSession();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    public static ExportSession get() {
        return INSTANCE;
    }

    private boolean inSession;
    private Integer pos1x;
    private Integer pos1y;
    private Integer pos1z;
    private Integer pos2x;
    private Integer pos2y;
    private Integer pos2z;
    private String outputName = "export";
    private String structureId = "structuredata.exported";
    private int activeFrame;
    private final Map<Integer, String> frameJson = new TreeMap<>();
    private IBlockSampler sampler = new DefaultBlockSampler();

    private ExportSession() {}

    public void startSession() {
        inSession = true;
    }

    public void endSession() {
        inSession = false;
    }

    public boolean isInSession() {
        return inSession;
    }

    /** 准星指向的方块角点（pos1），与创世神式选区一致。 */
    public void setPos1Block(int x, int y, int z) {
        pos1x = x;
        pos1y = y;
        pos1z = z;
    }

    /** 准星指向的方块角点（pos2）。 */
    public void setPos2Block(int x, int y, int z) {
        pos2x = x;
        pos2y = y;
        pos2z = z;
    }

    public boolean hasCompleteSelection() {
        return pos1x != null && pos1y != null && pos1z != null && pos2x != null && pos2y != null && pos2z != null;
    }

    /** 供网络同步与客户端线框；未完成选区时 {@link SelectionSnapshot#complete} 为 false。 */
    public SelectionSnapshot getSelectionSnapshot() {
        if (!hasCompleteSelection()) {
            return SelectionSnapshot.empty(this.inSession);
        }
        int minX = Math.min(pos1x, pos2x);
        int minY = Math.min(pos1y, pos2y);
        int minZ = Math.min(pos1z, pos2z);
        int maxX = Math.max(pos1x, pos2x);
        int maxY = Math.max(pos1y, pos2y);
        int maxZ = Math.max(pos1z, pos2z);
        return new SelectionSnapshot(inSession, true, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void setOutputName(String name) {
        if (name != null && !name.isEmpty()) outputName = name;
    }

    public String getOutputName() {
        return outputName;
    }

    public void setStructureId(String id) {
        if (id != null && !id.isEmpty()) structureId = id;
    }

    public String getStructureId() {
        return structureId;
    }

    public void setActiveFrame(int n) {
        if (n >= 0) activeFrame = n;
    }

    public int getActiveFrame() {
        return activeFrame;
    }

    public void setSampler(IBlockSampler sampler) {
        if (sampler != null) this.sampler = sampler;
    }

    public String statusLine() {
        return String.format(
            "session=%s pos1=%s pos2=%s activeFrame=%d recordedFrames=%s",
            inSession,
            posStr(pos1x, pos1y, pos1z),
            posStr(pos2x, pos2y, pos2z),
            activeFrame,
            frameJson.keySet());
    }

    private static String posStr(Integer x, Integer y, Integer z) {
        if (x == null || y == null || z == null) return "null";
        return x + "," + y + "," + z;
    }

    public void record(EntityPlayerMP player) {
        if (!hasCompleteSelection()) {
            throw new IllegalStateException("请先 /sde pos1 与 /sde pos2（对准方块）");
        }
        World world = player.worldObj;
        JsonObject obj = StructureScan
            .scanToStructureJson(world, pos1x, pos1y, pos1z, pos2x, pos2y, pos2z, structureId, sampler);
        frameJson.put(activeFrame, GSON.toJson(obj));
    }

    /**
     * 写出结构 JSON 到 {@code structure_exports}，并写入 {@link PendingBundleFiles#FILE_NAME}，
     * 供客户端下一 tick 起读取并生成 block_registry / material_registry / assets（无自定义网络包）。
     */
    public String exportToFile() throws IOException {
        File dir = new File("structure_exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File out = new File(dir, outputName + ".json");

        if (frameJson.isEmpty()) {
            throw new IllegalStateException("无缓冲数据，请先 record");
        }

        JsonArray paletteUnion = collectPaletteUnion();

        if (frameJson.size() == 1 && frameJson.containsKey(0)) {
            String json = frameJson.get(0);
            writeUtf8(out, json);
        } else {
            JsonObject world = new JsonObject();
            world.addProperty("schemaVersion", 1);
            world.addProperty("id", structureId);
            JsonArray frames = new JsonArray();
            JsonParser parser = new JsonParser();
            for (Map.Entry<Integer, String> e : frameJson.entrySet()) {
                JsonObject fr = new JsonObject();
                fr.addProperty("index", e.getKey());
                JsonObject nested = parser.parse(e.getValue())
                    .getAsJsonObject();
                fr.add("structure", nested);
                frames.add(fr);
            }
            world.add("frames", frames);
            JsonObject playback = new JsonObject();
            playback.addProperty("loop", false);
            playback.addProperty("defaultFrameIndex", 0);
            world.add("playback", playback);

            writeUtf8(out, GSON.toJson(world));
        }

        writePendingBundleFile(dir, paletteUnion);
        return out.getAbsolutePath();
    }

    private void writePendingBundleFile(File exportDir, JsonArray palette) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", PendingBundleFiles.SCHEMA_VERSION);
        root.addProperty("outputName", outputName);
        root.addProperty("exportRoot", exportDir.getAbsolutePath());
        root.add("palette", palette);
        writeUtf8(new File(exportDir, PendingBundleFiles.FILE_NAME), GSON.toJson(root));
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    /**
     * 多帧 palette 并集（按 registryId+meta 去重，插入顺序稳定）。
     */
    private JsonArray collectPaletteUnion() {
        JsonParser parser = new JsonParser();
        LinkedHashMap<String, JsonObject> byKey = new LinkedHashMap<>();
        for (String json : frameJson.values()) {
            JsonObject root = parser.parse(json)
                .getAsJsonObject();
            if (!root.has("palette")) {
                continue;
            }
            JsonArray palette = root.getAsJsonArray("palette");
            for (int i = 0; i < palette.size(); i++) {
                JsonObject p = palette.get(i)
                    .getAsJsonObject();
                String registryId = p.get("registryId")
                    .getAsString();
                int meta = p.get("meta")
                    .getAsInt();
                String key = meta == 0 ? registryId : registryId + "@" + meta;
                byKey.putIfAbsent(key, p);
            }
        }
        JsonArray out = new JsonArray();
        for (JsonObject p : byKey.values()) {
            out.add(p);
        }
        return out;
    }
}
