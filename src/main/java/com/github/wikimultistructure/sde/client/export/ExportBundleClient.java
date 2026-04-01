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
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import com.github.wikimultistructure.sde.core.export.BlockRenderKindResolver;
import com.github.wikimultistructure.sde.core.export.PendingBundleFiles;
import com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端：palette → IIcon → ResourceLocation → 复制 assets 镜像；写出 block_registry（含 faces）与 material_registry。
 * 六面暂用 {@link Block#getIcon(int, int)} 的 side=3（北向）代表纹理；与 SimpleCube all 一致。
 */
@SideOnly(Side.CLIENT)
public final class ExportBundleClient {

    /** 与 Wiki block_registry 对齐；schema 2 起含 faces.layers 与完备 material 引用 */
    public static final int BLOCK_REGISTRY_SCHEMA_VERSION = 2;
    public static final int MATERIAL_REGISTRY_SCHEMA_VERSION = 1;

    /** 北向面，与多数机器「正面」展示一致；仅用于 all 六面同纹 */
    private static final int SAMPLE_SIDE = 3;

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
        File[] candidates = new File[] {
            new File(mc.mcDataDir, "structure_exports/" + PendingBundleFiles.FILE_NAME),
            new File(new File("structure_exports"), PendingBundleFiles.FILE_NAME).getAbsoluteFile(),
        };
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
                        mc.thePlayer.addChatMessage(
                            new ChatComponentText("SDE: 材质包已写出，但无法删除 pending_bundle.json，请手动删除"));
                    }
                } else if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentText("SDE: 材质包已写入: " + bundleRoot.getAbsolutePath()));
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentText("SDE: 材质包失败 — " + e.getMessage()));
                }
            }
            return;
        }
    }

    /**
     * @param bundleRootDir 与 {@code export.json} 同目录，一般为 {@code structure_exports} 的绝对路径（由服务端传入，与 user.dir 一致）
     * @param outputName 无后缀，与 export.json 同名 stem
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

            String locator = null;
            try {
                locator = sampleBlockTextureLocator(block, meta);
            } catch (Exception ignored) {
                // 保持 locator null
            }

            if (locator != null && copyTextureAndMcmeta(mc, outDir, locator, materials)) {
                entry.addProperty("meshKind", "SimpleCube");
                if (logicalKind != null) {
                    entry.addProperty("logicalKind", logicalKind.name());
                }
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

    private static String sampleBlockTextureLocator(Block block, int meta) {
        IIcon icon = block.getIcon(SAMPLE_SIDE, meta);
        if (icon == null) {
            return null;
        }
        String iconName = icon.getIconName();
        if (iconName == null || iconName.isEmpty()) {
            return null;
        }
        return iconNameToLocator(iconName);
    }

    static String iconNameToLocator(String iconName) {
        int colon = iconName.indexOf(':');
        if (colon < 0) {
            return "minecraft:" + iconName;
        }
        return iconName;
    }

    private static boolean copyTextureAndMcmeta(
        Minecraft mc,
        File bundleRoot,
        String locator,
        Map<String, JsonObject> materialsOut
    ) throws IOException {
        int colon = locator.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String ns = locator.substring(0, colon);
        String path = locator.substring(colon + 1);
        ResourceLocation texLoc = new ResourceLocation(ns, "textures/" + path + ".png");

        if (!resourceExists(mc, texLoc)) {
            return false;
        }

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
