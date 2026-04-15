package com.github.wikimultistructure.sde.client.meshcapture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

import com.github.wikimultistructure.sde.mixin.interfaces.accessors.TextureMapAccessor;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Maps atlas UV to a stable sprite name (materialKey) by containment test; prefers smallest sprite area on ties.
 */
@SideOnly(Side.CLIENT)
public final class MaterialKeyResolver {

    private MaterialKeyResolver() {}

    public static String resolveMidUv(double u, double v) {
        TextureMap map = Minecraft.getMinecraft()
            .getTextureMapBlocks();
        return resolveMidUv(u, v, map);
    }

    public static String resolveMidUv(double u, double v, TextureMap map) {
        if (!(map instanceof TextureMapAccessor)) {
            return "unknown";
        }
        Map<String, TextureAtlasSprite> sprites = ((TextureMapAccessor) map).sde$getMapRegisteredSprites();
        double bestArea = Double.POSITIVE_INFINITY;
        String bestName = "unknown";
        for (TextureAtlasSprite spr : sprites.values()) {
            double minU = spr.getMinU();
            double maxU = spr.getMaxU();
            double minV = spr.getMinV();
            double maxV = spr.getMaxV();
            if (u + 1e-6 >= minU && u <= maxU + 1e-6 && v + 1e-6 >= minV && v <= maxV + 1e-6) {
                double area = (maxU - minU) * (maxV - minV);
                if (area < bestArea) {
                    bestArea = area;
                    bestName = spr.getIconName();
                }
            }
        }
        return normalizeMaterialKey(bestName);
    }

    /** Strip leading {@code textures/} and trailing {@code .png} if present for Wiki locator style. */
    public static String normalizeMaterialKey(String iconName) {
        if (iconName == null || iconName.isEmpty()) {
            return "unknown";
        }
        String s = iconName;
        if (s.startsWith("textures/")) {
            s = s.substring("textures/".length());
        }
        if (s.endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.replace('\\', '/');
    }

    /**
     * 按与 {@link #normalizeMaterialKey} 一致的键在图集中查找 sprite，供将图集 UV 换算为 sprite 局部 [0,1]。
     */
    public static TextureAtlasSprite findSpriteForMaterialKey(String materialKey, TextureMap map) {
        if (materialKey == null || materialKey.isEmpty() || "unknown".equals(materialKey)) {
            return null;
        }
        if (!(map instanceof TextureMapAccessor)) {
            return null;
        }
        Map<String, TextureAtlasSprite> sprites = ((TextureMapAccessor) map).sde$getMapRegisteredSprites();
        for (TextureAtlasSprite spr : sprites.values()) {
            if (spr == null) {
                continue;
            }
            if (normalizeMaterialKey(spr.getIconName()).equals(materialKey)) {
                return spr;
            }
        }
        return null;
    }

    /**
     * 由四边形在图集中的 UV 质心解析 {@code materialKey}，将顶点 UV 重映射为 sprite 局部 [0,1]，并写回 {@code q}。
     *
     * @return 与 {@link #normalizeMaterialKey} 一致的材质键，供采样器表注册
     */
    public static String applySpriteLocalToQuad(TessellatorCaptureState.CapturedQuad q, TextureMap textureMap) {
        double su = 0, sv = 0;
        for (TessellatorCaptureState.CapturedVertex v : q.vertices) {
            su += v.u;
            sv += v.v;
        }
        su /= 4.0;
        sv /= 4.0;
        String materialKey = resolveMidUv(su, sv, textureMap);
        TextureAtlasSprite spr = findSpriteForMaterialKey(materialKey, textureMap);
        List<TessellatorCaptureState.CapturedVertex> remapped = new ArrayList<>(4);
        for (TessellatorCaptureState.CapturedVertex v : q.vertices) {
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
            remapped.add(
                new TessellatorCaptureState.CapturedVertex(v.x, v.y, v.z, u, vv, v.brightness, v.colorArgb));
        }
        q.vertices.clear();
        q.vertices.addAll(remapped);
        q.materialKey = materialKey;
        return materialKey;
    }
}
