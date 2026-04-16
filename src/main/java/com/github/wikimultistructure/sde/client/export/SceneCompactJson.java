package com.github.wikimultistructure.sde.client.export;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * 将终态 Raw 文档拆成明文 {@code meta} + gzip+Base64 {@code payload}，与 Wiki {@code normalizeSceneDocumentForWiki} 对称。
 */
public final class SceneCompactJson {

    public static final String PAYLOAD_ENCODING = "gzip+base64";

    private static final Gson COMPACT_GSON = new Gson();

    private SceneCompactJson() {}

    public static JsonObject toCompactEnvelope(JsonObject rawFinal) {
        JsonObject meta = new JsonObject();
        JsonObject inner = new JsonObject();
        if (rawFinal.has("frames") && rawFinal.get("frames")
            .isJsonArray()) {
            copyIfPresent(rawFinal, meta, "id");
            copyIfPresent(rawFinal, meta, "playback");
            copyIfPresent(rawFinal, meta, "globalConfig");
            copyIfPresent(rawFinal, meta, "schemaVersion");
            copyIfPresent(rawFinal, inner, "frames");
            copyIfPresent(rawFinal, inner, "textureBlobs");
        } else {
            copyIfPresent(rawFinal, meta, "mode");
            copyIfPresent(rawFinal, meta, "id");
            copyIfPresent(rawFinal, meta, "globalConfig");
            copyIfPresent(rawFinal, meta, "source");
            copyIfPresent(rawFinal, meta, "axis");
            copyIfPresent(rawFinal, meta, "initialCamera");
            copyIfPresent(rawFinal, meta, "scanBounds");
            copyIfPresent(rawFinal, meta, "schemaVersion");
            copyIfPresent(rawFinal, inner, "blockPalette");
            copyIfPresent(rawFinal, inner, "materialPalette");
            copyIfPresent(rawFinal, inner, "cellGrid");
            copyIfPresent(rawFinal, inner, "textureBlobs");
        }
        byte[] utf8 = COMPACT_GSON.toJson(inner)
            .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(utf8.length + 32, 1024 * 1024));
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(utf8);
        } catch (Exception e) {
            throw new IllegalStateException("gzip inner JSON", e);
        }
        String b64 = Base64.getEncoder()
            .encodeToString(bos.toByteArray());
        JsonObject env = new JsonObject();
        env.addProperty("documentFormat", "Compact");
        env.addProperty("payloadEncoding", PAYLOAD_ENCODING);
        env.add("meta", meta);
        env.addProperty("payload", b64);
        return env;
    }

    private static void copyIfPresent(JsonObject src, JsonObject dest, String key) {
        if (src.has(key)) {
            dest.add(key, src.get(key));
        }
    }
}
