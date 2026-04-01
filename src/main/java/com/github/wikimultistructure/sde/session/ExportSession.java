package com.github.wikimultistructure.sde.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.sampling.DefaultBlockSampler;
import com.github.wikimultistructure.sde.sampling.IBlockSampler;
import com.github.wikimultistructure.sde.scan.StructureScan;
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

    public void setPos1(EntityPlayerMP p) {
        pos1x = floor(p.posX);
        pos1y = floor(p.posY);
        pos1z = floor(p.posZ);
    }

    public void setPos2(EntityPlayerMP p) {
        pos2x = floor(p.posX);
        pos2y = floor(p.posY);
        pos2z = floor(p.posZ);
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
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
        if (pos1x == null || pos2x == null) {
            throw new IllegalStateException("请先 /sde pos1 与 /sde pos2");
        }
        World world = player.worldObj;
        JsonObject obj = StructureScan
            .scanToStructureJson(world, pos1x, pos1y, pos1z, pos2x, pos2y, pos2z, structureId, sampler);
        frameJson.put(activeFrame, GSON.toJson(obj));
    }

    public String exportToFile() throws IOException {
        File dir = new File("structure_exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File out = new File(dir, outputName + ".json");

        if (frameJson.isEmpty()) {
            throw new IllegalStateException("无缓冲数据，请先 record");
        }

        if (frameJson.size() == 1 && frameJson.containsKey(0)) {
            String json = frameJson.get(0);
            writeUtf8(out, json);
            return out.getAbsolutePath();
        }

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
        return out.getAbsolutePath();
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
