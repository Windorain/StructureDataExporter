package com.github.wikimultistructure.sde.client.export;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;

import com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicies;
import com.github.wikimultistructure.sde.core.export.PendingDumpFiles;
import com.github.wikimultistructure.sde.core.registry.GregTechMetaTileRegistry;
import com.github.wikimultistructure.sde.mixin.interfaces.accessors.TextureMapAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端：读 {@link PendingDumpFiles} 触发文件 → 写出全量 {@code block_registry.json} / {@code material_registry.json}（不写 assets 镜像）。
 * <p>
 * {@code block_registry}：一般方块为 world meta 0–15；{@code gregtech:gt.blockmachines} 按 GT5U {@code METATILEENTITIES}
 * 非空槽枚举，键 {@code registryId@n} 中 {@code n} 为 MetaTile ID（mID），见 {@link GregTechMetaTileRegistry}。
 * {@code material_registry}：仅由方块 {@link TextureMap} 的 {@code mapRegisteredSprites} 键经规范化枚举（见 {@link TextureMapAccessor}）。
 */
@SideOnly(Side.CLIENT)
public final class ExportBundleClient {

    /** 与 Wiki block_registry 对齐；schema 3 起以 {@code renderProfile} 替代 {@code logicalKind} */
    public static final int BLOCK_REGISTRY_SCHEMA_VERSION = 3;
    public static final int MATERIAL_REGISTRY_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private ExportBundleClient() {}

    /**
     * 每客户端 tick：若存在服务端写入的 {@link PendingDumpFiles#FILE_NAME} 则消费并写出全量注册表，然后删除该文件。
     */
    public static void tickConsumePendingDumpIfAny(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return;
        }
        File[] candidates = new File[] { new File(mc.mcDataDir, "structure_exports/" + PendingDumpFiles.FILE_NAME),
            new File(new File("structure_exports"), PendingDumpFiles.FILE_NAME).getAbsoluteFile(), };
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
                if (ver != PendingDumpFiles.SCHEMA_VERSION) {
                    continue;
                }
                String exportRootStr = root.get("exportRoot")
                    .getAsString();
                File bundleRoot = new File(exportRootStr);
                writeFullRegistryDump(bundleRoot);
                if (!pending.delete()) {
                    if (mc.thePlayer != null) {
                        mc.thePlayer
                            .addChatMessage(new ChatComponentText("SDE: 全量注册表已写出，但无法删除 pending_dump.json，请手动删除"));
                    }
                } else if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText("SDE: 全量注册表已写入: " + bundleRoot.getAbsolutePath()));
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText("SDE: dump 失败 — " + e.getMessage()));
                }
            }
            return;
        }
    }

    /**
     * 写出 {@code block_registry.json} 与 {@code material_registry.json}（仅方块图集已注册精灵；不复制贴图文件）。
     */
    public static void writeFullRegistryDump(File exportRoot) throws IOException {
        if (!exportRoot.exists() && !exportRoot.mkdirs()) {
            throw new IOException("无法创建目录: " + exportRoot.getAbsolutePath());
        }
        JsonObject blocks = new JsonObject();
        Minecraft mc = Minecraft.getMinecraft();

        @SuppressWarnings("unchecked")
        Iterator<Block> it = Block.blockRegistry.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (block == null) {
                continue;
            }
            GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(block);
            if (uid == null) {
                continue;
            }
            String registryId = uid.toString();
            if ("minecraft:air".equals(registryId)) {
                continue;
            }
            if (GregTechMetaTileRegistry.isGregTechBlockMachines(block, registryId)) {
                GregTechMetaTileRegistry.forEachRegisteredMetaTileId(mId -> {
                    String key = mId == 0 ? registryId : registryId + "@" + mId;
                    if (blocks.has(key)) {
                        return;
                    }
                    try {
                        BlockRegistryPolicies.appendBlockEntry(mc, block, registryId, mId, key, blocks);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
                continue;
            }
            for (int meta = 0; meta <= 15; meta++) {
                String key = meta == 0 ? registryId : registryId + "@" + meta;
                if (blocks.has(key)) {
                    continue;
                }
                try {
                    BlockRegistryPolicies.appendBlockEntry(mc, block, registryId, meta, key, blocks);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        Map<String, JsonObject> materials = fillMaterialsFromBlockTextureAtlas(mc);

        JsonObject blockRoot = new JsonObject();
        blockRoot.addProperty("schemaVersion", BLOCK_REGISTRY_SCHEMA_VERSION);
        blockRoot.add("blocks", blocks);
        writeUtf8(new File(exportRoot, "block_registry.json"), GSON.toJson(blockRoot));

        JsonObject matRoot = new JsonObject();
        matRoot.addProperty("schemaVersion", MATERIAL_REGISTRY_SCHEMA_VERSION);
        JsonObject matMap = new JsonObject();
        for (Map.Entry<String, JsonObject> e : materials.entrySet()) {
            matMap.add(e.getKey(), e.getValue());
        }
        matRoot.add("materials", matMap);
        writeUtf8(new File(exportRoot, "material_registry.json"), GSON.toJson(matRoot));
    }

    /**
     * 仅枚举方块 {@link TextureMap#getMapRegisteredSprites()} 键，写入 material 元数据；无回退路径。
     */
    private static Map<String, JsonObject> fillMaterialsFromBlockTextureAtlas(Minecraft mc) throws IOException {
        TextureMap textureMap = mc.getTextureMapBlocks();
        if (textureMap == null) {
            throw new IOException("getTextureMapBlocks() 为 null，图集未就绪");
        }
        TextureMapAccessor accessor = (TextureMapAccessor) (Object) textureMap;
        Map<String, ?> spriteMap = accessor.sde$getMapRegisteredSprites();
        if (spriteMap == null || spriteMap.isEmpty()) {
            throw new IOException("mapRegisteredSprites 为空");
        }
        Map<String, JsonObject> materials = new LinkedHashMap<>();
        for (String iconName : spriteMap.keySet()) {
            if (iconName == null || iconName.isEmpty()) {
                continue;
            }
            String rawLocator = ExportTextureLocator.iconNameToLocator(iconName);
            String locator = ExportTextureLocator.normalizeLocatorForBundle(rawLocator);
            if (locator == null) {
                continue;
            }
            registerMaterialMetadataOnly(mc, locator, materials);
        }
        return materials;
    }

    /**
     * 若 locator 对应纹理存在则登记 material_registry 条目（不写入磁盘 assets）。
     */
    private static void registerMaterialMetadataOnly(Minecraft mc, String locator, Map<String, JsonObject> materialsOut)
        throws IOException {
        int colon = locator.indexOf(':');
        if (colon < 0) {
            return;
        }
        String ns = locator.substring(0, colon);
        String path = locator.substring(colon + 1);
        ResourceLocation texLoc = new ResourceLocation(ns, "textures/" + path + ".png");

        if (!resourceExists(mc, texLoc)) {
            return;
        }

        ResourceLocation mcmetaLoc = new ResourceLocation(ns, "textures/" + path + ".png.mcmeta");
        if (!materialsOut.containsKey(locator)) {
            JsonObject m = new JsonObject();
            m.addProperty("locator", locator);
            m.addProperty("kind", detectMaterialKind(mc, mcmetaLoc));
            materialsOut.put(locator, m);
        }
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
