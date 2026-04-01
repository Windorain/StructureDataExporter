package com.github.wikimultistructure.sde.client.export;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;

import com.github.wikimultistructure.sde.core.export.BlockRenderKindResolver;
import com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind;
import com.github.wikimultistructure.sde.core.export.PendingBundleFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端材质包：读 {@code pending_bundle.json} → 写 registry 与 {@code assets/} 镜像。
 * <p>
 * <b>数据流（locator 相关）</b>：详见 {@link ExportTextureLocator} 类注释「数据流（端到端）」。此处 {@link #writeBundle} 对每个 palette 条目调用
 * {@link ExportTextureLocator#resolve} 得到规范化 locator（唯一规范化点）；{@link #copyTextureAndMcmeta} 将 locator 转为 {@link ResourceLocation} 并从
 * {@link net.minecraft.client.resources.IResourceManager} 复制字节到 bundle。{@code block_registry} 的 {@code materialId} 与
 * {@code material_registry} 的键必须与 {@link #copyTextureAndMcmeta} 内使用的 locator 字符串一致（同一规范化结果）。
 * <p>
 * <b>假设</b>：与 {@link ExportTextureLocator} 一致，采样纹理视为方块图集侧；六面暂用 {@link Block#getIcon(int, int)} 的 side 与
 * {@link ExportTextureLocator#DEFAULT_SAMPLE_SIDE} 一致；GT 机器纹理来自 MTE#getTexture（见 {@link com.github.wikimultistructure.sde.client.export.GtTextureResolver}）。
 */
@SideOnly(Side.CLIENT)
public final class ExportBundleClient {

    /** 与 Wiki block_registry 对齐；schema 2 起含 faces.layers 与完备 material 引用 */
    public static final int BLOCK_REGISTRY_SCHEMA_VERSION = 2;
    public static final int MATERIAL_REGISTRY_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private ExportBundleClient() {}

    /**
     * 每客户端 tick 调用：若存在服务端写入的 {@link PendingBundleFiles#FILE_NAME} 则消费并生成材质包，然后删除该文件。
     */
    public static void tickConsumePendingIfAny(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return;
        }
        File[] candidates = new File[] { new File(mc.mcDataDir, "structure_exports/" + PendingBundleFiles.FILE_NAME),
            new File(new File("structure_exports"), PendingBundleFiles.FILE_NAME).getAbsoluteFile(), };
        for (File pending : candidates) {
            if (!pending.isFile() || pending.length() == 0) {
                continue;
            }
            try {
                String raw = new String(Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8);
                JsonObject root = new JsonParser().parse(raw)
                    .getAsJsonObject();
                int ver = root.get("schemaVersion")
                    .getAsInt();
                if (ver != PendingBundleFiles.SCHEMA_VERSION) {
                    continue;
                }
                String outName = root.get("outputName")
                    .getAsString();
                String exportRootStr = root.get("exportRoot")
                    .getAsString();
                JsonArray palette = root.getAsJsonArray("palette");
                File bundleRoot = new File(exportRootStr);
                writeBundle(bundleRoot, outName, palette);
                if (!pending.delete()) {
                    if (mc.thePlayer != null) {
                        mc.thePlayer
                            .addChatMessage(new ChatComponentText("SDE: 材质包已写出，但无法删除 pending_bundle.json，请手动删除"));
                    }
                } else if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText("SDE: 材质包已写入: " + bundleRoot.getAbsolutePath()));
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText("SDE: 材质包失败 — " + e.getMessage()));
                }
            }
            return;
        }
    }

    /**
     * @param bundleRootDir 与 {@code export.json} 同目录，一般为 {@code structure_exports} 的绝对路径（由服务端传入，与 user.dir 一致）
     * @param outputName    无后缀，与 export.json 同名 stem
     */
    public static void writeBundle(File bundleRootDir, String outputName, JsonArray palette) throws IOException {
        File outDir = bundleRootDir;
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录: " + outDir.getAbsolutePath());
        }

        Map<String, JsonObject> materials = new LinkedHashMap<>();
        JsonObject blocks = new JsonObject();

        Minecraft mc = Minecraft.getMinecraft();

        for (int i = 0; i < palette.size(); i++) {
            JsonObject p = palette.get(i)
                .getAsJsonObject();
            String registryId = p.get("registryId")
                .getAsString();
            int meta = p.get("meta")
                .getAsInt();
            if ("air".equals(registryId)) {
                continue;
            }
            String key = meta == 0 ? registryId : registryId + "@" + meta;
            if (blocks.has(key)) {
                continue;
            }

            Block block = resolveBlock(registryId);
            GtcBlockRenderKind logicalKind = BlockRenderKindResolver.resolve(block);
            boolean occludes = block != null && block.isOpaqueCube();

            JsonObject entry = new JsonObject();
            entry.addProperty("occludesAdjacentFaces", occludes);

            if (block == null) {
                entry.addProperty("meshKind", "Unknown");
                entry.add("faces", new JsonObject());
                blocks.add(key, entry);
                continue;
            }

            // locator：规范化后的 ns:path，path 含 blocks/ 或 items/（默认 blocks，见 ExportTextureLocator）
            String locator = ExportTextureLocator.resolve(block, meta, logicalKind);

            if (locator != null && copyTextureAndMcmeta(mc, outDir, locator, materials)) {
                entry.addProperty("meshKind", "SimpleCube");
                if (logicalKind != null) {
                    entry.addProperty("logicalKind", logicalKind.name());
                }
                JsonObject faces = new JsonObject();
                JsonObject all = new JsonObject();
                JsonArray layers = new JsonArray();
                JsonObject layer = new JsonObject();
                // materialId 必须与 material_registry 的键、磁盘导出的 locator 一致（同一字符串）
                layer.addProperty("materialId", locator);
                layer.addProperty("layerRole", "base");
                layers.add(layer);
                all.add("layers", layers);
                faces.add("all", all);
                entry.add("faces", faces);
            } else if (logicalKind != null) {
                entry.addProperty("meshKind", "SimpleCube");
                entry.addProperty("logicalKind", logicalKind.name());
                entry.add("faces", new JsonObject());
            } else {
                entry.addProperty("meshKind", "Unknown");
                entry.add("faces", new JsonObject());
            }

            blocks.add(key, entry);
        }

        JsonObject blockRoot = new JsonObject();
        blockRoot.addProperty("schemaVersion", BLOCK_REGISTRY_SCHEMA_VERSION);
        blockRoot.add("blocks", blocks);
        File regOut = new File(outDir, outputName + ".block_registry.json");
        writeUtf8(regOut, GSON.toJson(blockRoot));

        JsonObject matRoot = new JsonObject();
        matRoot.addProperty("schemaVersion", MATERIAL_REGISTRY_SCHEMA_VERSION);
        JsonObject matMap = new JsonObject();
        for (Map.Entry<String, JsonObject> e : materials.entrySet()) {
            matMap.add(e.getKey(), e.getValue());
        }
        matRoot.add("materials", matMap);
        File matOut = new File(outDir, outputName + ".material_registry.json");
        writeUtf8(matOut, GSON.toJson(matRoot));
    }

    /**
     * 将 locator 映射到资源包内 PNG/MCMETA，并写入 bundle。
     * <p>
     * <b>约定</b>：locator 为 {@code 命名空间:path}，path 为 {@code textures/} 之后、不含 {@code .png}；即资源路径为
     * {@code textures/&lt;path&gt;.png}。须已由 {@link ExportTextureLocator#resolve} 经 {@link ExportTextureLocator#normalizeLocatorForBundle} 规范化。
     */
    private static boolean copyTextureAndMcmeta(Minecraft mc, File bundleRoot, String locator,
        Map<String, JsonObject> materialsOut) throws IOException {
        int colon = locator.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String ns = locator.substring(0, colon);
        String path = locator.substring(colon + 1);
        // ResourceLocation：domain + textures/<path>.png → 与 jar 内 assets/<domain>/textures/<path>.png 对应
        ResourceLocation texLoc = new ResourceLocation(ns, "textures/" + path + ".png");

        if (!resourceExists(mc, texLoc)) {
            return false;
        }

        // 镜像目录与 texLoc 路径一致（不含 assets 前缀，由 File 拼 assets/ns/textures/...）
        File destPng = new File(bundleRoot, "assets/" + ns + "/textures/" + path + ".png");
        destPng.getParentFile()
            .mkdirs();
        try (InputStream in = mc.getResourceManager()
            .getResource(texLoc)
            .getInputStream()) {
            Files.copy(in, destPng.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        ResourceLocation mcmetaLoc = new ResourceLocation(ns, "textures/" + path + ".png.mcmeta");
        if (resourceExists(mc, mcmetaLoc)) {
            File destMeta = new File(bundleRoot, "assets/" + ns + "/textures/" + path + ".png.mcmeta");
            destMeta.getParentFile()
                .mkdirs();
            try (InputStream in = mc.getResourceManager()
                .getResource(mcmetaLoc)
                .getInputStream()) {
                Files.copy(in, destMeta.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // materials Map 键 = 规范化 locator，与 block_registry.materialId 一致
        if (!materialsOut.containsKey(locator)) {
            JsonObject m = new JsonObject();
            m.addProperty("locator", locator);
            m.addProperty("kind", detectMaterialKind(mc, mcmetaLoc));
            materialsOut.put(locator, m);
        }
        return true;
    }

    private static boolean resourceExists(Minecraft mc, ResourceLocation loc) {
        try {
            mc.getResourceManager()
                .getResource(loc);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String detectMaterialKind(Minecraft mc, ResourceLocation mcmetaLoc) {
        if (!resourceExists(mc, mcmetaLoc)) {
            return "static16";
        }
        try (InputStream in = mc.getResourceManager()
            .getResource(mcmetaLoc)
            .getInputStream()) {
            String raw = readUtf8(in);
            if (raw.contains("\"animation\"")) {
                return "animated";
            }
        } catch (IOException ignored) {
            // ignore
        }
        return "static16";
    }

    private static Block resolveBlock(String registryId) {
        String[] parts = registryId.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        return GameRegistry.findBlock(parts[0], parts[1]);
    }

    private static void writeUtf8(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) {
            buf.write(b, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
