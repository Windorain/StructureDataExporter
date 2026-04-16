package com.github.wikimultistructure.sde.client.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.github.wikimultistructure.sde.client.meshcapture.MaterialKeyResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 将 {@code materialPalette} 中每条材质的像素写入根级 {@code textureBlobs}（标准 Base64 PNG 字节）并设置
 * {@code textureBlobIndex}，供 Wiki 单文件消费。与 {@link ExportTextureLocator#normalizeLocatorForBundle} /
 * {@link ExportBundleClient} 的资源路径规则一致；优先读资源包 PNG（保留动画竖条），失败时再尝试图集 sprite 帧缓冲。
 */
@SideOnly(Side.CLIENT)
public final class TextureBlobEmbedder {

    /** 与 Wiki 预览占位一致；无材质槽时仍须非空池以满足 Wiki 校验 */
    private static final String PNG_BASE64_PLACEHOLDER =
        "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAJ0lEQVR4nGNkwAH+M/zHKs7EQCJgGtVABGDEFd6MDIxDxQ9Mw0ADACTzBB1zL3aeAAAAAElFTkSuQmCC";

    private static byte[] cachedPlaceholderPng;

    private static byte[] getPlaceholderPngBytes() {
        if (cachedPlaceholderPng == null) {
            cachedPlaceholderPng = java.util.Base64.getDecoder()
                .decode(PNG_BASE64_PLACEHOLDER);
        }
        return cachedPlaceholderPng;
    }

    private TextureBlobEmbedder() {}

    public static void embedIntoDocument(JsonObject documentRoot) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        TextureMap map = mc.getTextureMapBlocks();
        IResourceManager rm = mc.getResourceManager();

        List<JsonArray> palettes = new ArrayList<>();
        collectMaterialPalettes(documentRoot, palettes);
        if (palettes.isEmpty()) {
            JsonArray only = new JsonArray();
            only.add(new JsonPrimitive(PNG_BASE64_PLACEHOLDER));
            documentRoot.add("textureBlobs", only);
            return;
        }

        JsonArray blobs = new JsonArray();
        Map<String, Integer> shaToIndex = new HashMap<>();

        for (JsonArray palette : palettes) {
            for (JsonElement el : palette) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject m = el.getAsJsonObject();
                if (!m.has("locator")) {
                    throw new IOException("materialPalette 条目缺少 locator");
                }
                String locator = m.get("locator")
                    .getAsString();
                boolean standalone = m.has("atlas") && m.get("atlas")
                    .isJsonNull();

                byte[] png = loadTexturePngBytes(locator, standalone, map, rm);
                String sha = sha256Hex(png);
                Integer idx = shaToIndex.get(sha);
                if (idx == null) {
                    idx = blobs.size();
                    shaToIndex.put(sha, idx);
                    blobs.add(new JsonPrimitive(java.util.Base64.getEncoder()
                        .encodeToString(png)));
                }
                m.addProperty("textureBlobIndex", idx.intValue());
            }
        }

        if (blobs.size() == 0) {
            blobs.add(new JsonPrimitive(PNG_BASE64_PLACEHOLDER));
        }

        documentRoot.add("textureBlobs", blobs);
    }

    private static void collectMaterialPalettes(JsonObject root, List<JsonArray> out) {
        if (root.has("frames")) {
            JsonArray frames = root.getAsJsonArray("frames");
            for (JsonElement frEl : frames) {
                JsonObject fr = frEl.getAsJsonObject();
                if (fr.has("structure")) {
                    JsonObject st = fr.getAsJsonObject("structure");
                    addPaletteIfPresent(st, out);
                }
            }
            return;
        }
        addPaletteIfPresent(root, out);
    }

    private static void addPaletteIfPresent(JsonObject structureOrRoot, List<JsonArray> out) {
        if (structureOrRoot.has("materialPalette")) {
            out.add(structureOrRoot.getAsJsonArray("materialPalette"));
        }
    }

    private static byte[] loadTexturePngBytes(String locator, boolean standalone, TextureMap map, IResourceManager rm)
        throws Exception {
        if (locator == null || locator.isEmpty() || "unknown".equals(locator)) {
            return getPlaceholderPngBytes();
        }

        byte[] fromDisk = tryLoadPng(rm, locatorToTextureResource(locator));
        if (fromDisk != null) {
            return fromDisk;
        }

        if (!standalone) {
            TextureAtlasSprite spr = MaterialKeyResolver.findSpriteForMaterialKey(locator, map);
            if (spr != null) {
                /*
                 * 图集 stitch 上传后，无动画 sprite 会 clearFramesTextureData，getFrameTextureData(0) 对空 list 越界。
                 * 仅在有 CPU 帧时读取；否则依赖磁盘路径（含 iconName 再解析）。
                 */
                if (spr.getFrameCount() > 0) {
                    int[][] fd = spr.getFrameTextureData(0);
                    if (fd != null && fd.length > 0 && fd[0] != null) {
                        int w = spr.getIconWidth();
                        int h = spr.getIconHeight();
                        if (w > 0 && h > 0 && fd[0].length == w * h) {
                            return rgbaArrayToPng(fd[0], w, h);
                        }
                    }
                }
                String iconName = spr.getIconName();
                if (iconName != null && !iconName.isEmpty()) {
                    String fromIcon = ExportTextureLocator.iconNameToLocator(iconName);
                    fromDisk = tryLoadPng(rm, locatorToTextureResource(fromIcon));
                    if (fromDisk != null) {
                        return fromDisk;
                    }
                }
            }
        }

        throw new IOException("无法读取纹理 PNG（locator=" + locator + ", standalone=" + standalone + "）");
    }

    private static byte[] tryLoadPng(IResourceManager rm, ResourceLocation rl) {
        if (rl == null) {
            return null;
        }
        try (InputStream in = rm.getResource(rl)
            .getInputStream()) {
            return readAll(in);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** 与 Wiki {@code locatorToRelativePngPath}：{@code models/} 在 assets 根下，其余在 {@code textures/} 下。 */
    private static ResourceLocation locatorToTextureResource(String locator) {
        String norm = ExportTextureLocator.normalizeLocatorForBundle(locator);
        if (norm == null) {
            return null;
        }
        int c = norm.indexOf(':');
        if (c < 0) {
            return null;
        }
        String ns = norm.substring(0, c);
        String path = norm.substring(c + 1);
        if (path.startsWith("models/")) {
            return new ResourceLocation(ns, path + ".png");
        }
        return new ResourceLocation(ns, "textures/" + path + ".png");
    }

    private static byte[] rgbaArrayToPng(int[] argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        return bos.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
