package com.github.wikimultistructure.sde.client.export;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.minecraft.block.Block;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * GregTech：从 {@code GregTechAPI.METATILEENTITIES[meta]} 取 {@code IMetaTileEntity#getTexture}，再自 {@code ITexture}
 * 解析出 locator 字符串（形态与 {@link IIcon#getIconName()} 一致：{@code ns:path}，无 {@code textures/}、无 {@code .png}）。
 * <p>
 * <b>在数据流中的位置</b>：仅被 {@link ExportTextureLocator#resolve} 在 {@link com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind#MB_MACHINE}
 * 分支调用；本类只产出与 {@link IIcon#getIconName()} 同形态的原始 {@code ns:path}，<b>规范化仅在</b> {@link ExportTextureLocator#resolve} 内调用
 * {@link ExportTextureLocator#normalizeLocatorForBundle} 一次完成。
 * <p>
 * <b>假设</b>：MTE 对方块渲染的纹理与 {@link ExportTextureLocator} 的「方块图集」假设一致；解析自 {@link IIcon} / {@code mIconName} /
 * {@link net.minecraft.util.ResourceLocation} 的字符串最终由 {@link ExportTextureLocator#normalizeLocatorForBundle} 统一补全路径。
 * 不依赖编译期 GT 类。
 */
@SideOnly(Side.CLIENT)
public final class GtTextureResolver {

    private static final String C_GREGTECH_API = "gregtech.api.GregTechAPI";
    private static final String C_IGREG_TECH_TILE = "gregtech.api.interfaces.tileentity.IGregTechTileEntity";
    private static final String C_FORGE_DIRECTION = "net.minecraftforge.common.util.ForgeDirection";

    private GtTextureResolver() {}

    /**
     * @param sampleSideOrdinal 与 {@link Block#getIcon(int, int)} 的 side 序数一致（见 {@link ExportTextureLocator#DEFAULT_SAMPLE_SIDE}）
     * @param facing            传入 MTE#getTexture 的朝向参数，常用 {@link ForgeDirection#NORTH}
     * @return 原始 {@code ns:path}（无 {@code .png}），尚未 {@link ExportTextureLocator#normalizeLocatorForBundle}；失败时 {@code null}。
     *         仅 {@link ExportTextureLocator#resolve} 应作为对外入口并负责规范化。
     */
    public static String tryMetaTileEntityLocator(int meta, int sampleSideOrdinal, ForgeDirection facing) {
        try {
            Class<?> gta = Class.forName(C_GREGTECH_API, false, GtTextureResolver.class.getClassLoader());
            Object array = gta.getField("METATILEENTITIES")
                .get(null);
            if (array == null || !array.getClass()
                .isArray()) {
                return null;
            }
            int len = Array.getLength(array);
            if (meta < 0 || meta >= len) {
                return null;
            }
            Object mte = Array.get(array, meta);
            if (mte == null) {
                return null;
            }
            ClassLoader cl = mte.getClass()
                .getClassLoader();
            Class<?> igte = Class.forName(C_IGREG_TECH_TILE, false, cl);
            Object stub = createGregTechTileStub(igte);
            ForgeDirection side = ForgeDirection.getOrientation(sampleSideOrdinal);
            if (facing == null) {
                facing = ForgeDirection.NORTH;
            }

            for (int colorIndex : new int[] { 0, -1, 1 }) {
                Object[] texturesMachine = invokeGetTextureMachine(mte, stub, side, facing, colorIndex, cl);
                String loc = extractFirstLocator(texturesMachine, sampleSideOrdinal);
                if (loc != null && !ExportTextureLocator.isLikelyGtRenderingErrorLocator(loc)) {
                    return loc;
                }
            }

            Object[] texturesPipe = invokeGetTexturePipe(mte, stub, side);
            String loc = extractFirstLocator(texturesPipe, sampleSideOrdinal);
            if (loc != null && !ExportTextureLocator.isLikelyGtRenderingErrorLocator(loc)) {
                return loc;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    private static String extractFirstLocator(Object[] textures, int sampleSideOrdinal) {
        if (textures == null || textures.length == 0) {
            return null;
        }
        for (Object tex : textures) {
            if (tex == null) {
                continue;
            }
            String loc = locatorFromGtITexture(tex, sampleSideOrdinal);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    private static Object[] invokeGetTextureMachine(Object mte, Object stub, ForgeDirection side,
        ForgeDirection facing, int colorIndex, ClassLoader cl) {
        try {
            Method m = findMachineGetTextureMethod(mte.getClass());
            if (m == null) {
                return null;
            }
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                m.setAccessible(true);
            }
            Class<?>[] pt = m.getParameterTypes();
            Object colorArg = colorIndex;
            if (pt.length > 3 && pt[3] == byte.class) {
                colorArg = (byte) colorIndex;
            }
            Object r = m.invoke(mte, stub, side, facing, colorArg, false, false);
            return toTextureArray(r);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 在具体 MTE 类上查找机器用 {@code getTexture}。参数类型用 {@link Class#getName()} 比较，避免 FML/TransformingClassLoader
     * 下 {@code p[0] != IGregTechTileEntity.class}（引用不等）导致永远匹配失败。
     */
    private static Method findMachineGetTextureMethod(Class<?> start) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"getTexture".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 6) {
                    continue;
                }
                if (!C_IGREG_TECH_TILE.equals(p[0].getName())) {
                    continue;
                }
                if (!C_FORGE_DIRECTION.equals(p[1].getName()) || !C_FORGE_DIRECTION.equals(p[2].getName())) {
                    continue;
                }
                if (p[3] != int.class && p[3] != byte.class) {
                    continue;
                }
                if (p[4] != boolean.class || p[5] != boolean.class) {
                    continue;
                }
                return m;
            }
        }
        return null;
    }

    /**
     * 与 {@link gregtech.api.metatileentity.BaseMetaPipeEntity#getTextureUncovered} 在 connections=0 时一致：末端贴图。
     */
    private static Object[] invokeGetTexturePipe(Object mte, Object stub, ForgeDirection side) {
        try {
            int connections = 0;
            int colorIndex = 0;
            boolean connected = true;
            boolean redstone = false;
            for (Class<?> c = mte.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"getTexture".equals(m.getName())) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 6) {
                        continue;
                    }
                    if (!C_IGREG_TECH_TILE.equals(p[0].getName())) {
                        continue;
                    }
                    if (!C_FORGE_DIRECTION.equals(p[1].getName())) {
                        continue;
                    }
                    if (p[2] != int.class) {
                        continue;
                    }
                    if (p[3] != int.class && p[3] != byte.class) {
                        continue;
                    }
                    if (p[4] != boolean.class || p[5] != boolean.class) {
                        continue;
                    }
                    if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                        m.setAccessible(true);
                    }
                    Object cArg = colorIndex;
                    if (p[3] == byte.class) {
                        cArg = (byte) colorIndex;
                    }
                    Object r = m.invoke(mte, stub, side, connections, cArg, connected, redstone);
                    return toTextureArray(r);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static Object[] toTextureArray(Object r) {
        if (r == null) {
            return null;
        }
        if (r instanceof Object[]) {
            return (Object[]) r;
        }
        return null;
    }

    private static Object createGregTechTileStub(Class<?> igteIface) {
        return Proxy
            .newProxyInstance(igteIface.getClassLoader(), new Class<?>[] { igteIface }, new InvocationHandler() {

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == byte.class) {
                        return (byte) 0;
                    }
                    if (rt == short.class) {
                        return (short) 0;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == float.class) {
                        return 0f;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    if (rt == void.class) {
                        return null;
                    }
                    return null;
                }
            });
    }

    /**
     * 从 GT {@code ITexture} 实现类中取出与图集一致的原始 {@code ns:path}；由 {@link ExportTextureLocator#resolve} 统一
     * {@link ExportTextureLocator#normalizeLocatorForBundle}。
     */
    static String locatorFromGtITexture(Object texture, int sampleSideOrdinal) {
        if (texture == null) {
            return null;
        }
        try {
            Object ic = readFieldChain(texture, "mIconContainer");
            if (ic != null) {
                String fromIc = locatorFromGtIconContainer(ic);
                if (fromIc != null) {
                    return fromIc;
                }
            }
            Object arr = readFieldChain(texture, "mTextures");
            if (arr instanceof Object[]) {
                Object[] m = (Object[]) arr;
                String cn = texture.getClass()
                    .getName();
                if (cn.contains("GTSidedTextureRender") && sampleSideOrdinal >= 0
                    && sampleSideOrdinal < m.length
                    && m[sampleSideOrdinal] != null) {
                    return locatorFromGtITexture(m[sampleSideOrdinal], sampleSideOrdinal);
                }
                for (Object sub : m) {
                    if (sub == null) {
                        continue;
                    }
                    try {
                        Method isValid = sub.getClass()
                            .getMethod("isValidTexture");
                        Object v = isValid.invoke(sub);
                        if (Boolean.FALSE.equals(v)) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // continue
                    }
                    String inner = locatorFromGtITexture(sub, sampleSideOrdinal);
                    if (inner != null) {
                        return inner;
                    }
                }
            }
            String cn2 = texture.getClass()
                .getName();
            if (cn2.contains("GTCopiedBlockTextureRender")) {
                Object blk = readFieldChain(texture, "mBlock");
                Object mSide = readFieldChain(texture, "mSide");
                Object mMeta = readFieldChain(texture, "mMeta");
                if (blk instanceof Block && mMeta instanceof Integer) {
                    int sideVal = (mSide instanceof Number) ? ((Number) mSide).intValue() : 6;
                    int meta = (Integer) mMeta;
                    int ord = (sideVal == 6) ? sampleSideOrdinal : sideVal;
                    IIcon icon = ((Block) blk).getIcon(ord, meta);
                    // 回退到方块 IIcon：与 ExportTextureLocator 方块图集假设一致
                    return ExportTextureLocator.locatorFromIcon(icon);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static Object readFieldChain(Object o, String name) {
        for (Class<?> z = o.getClass(); z != null; z = z.getSuperclass()) {
            try {
                Field f = z.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // next
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    /**
     * GregTech {@code IIconContainer}：优先 {@link IIcon#getIconName()} 语义；否则反射 {@code mIconName}；再否则 {@code getTextureFile()} 非图集路径。
     * 返回原始 {@code ns:path}，由 {@link ExportTextureLocator#resolve} 统一 {@link ExportTextureLocator#normalizeLocatorForBundle}。
     */
    private static String locatorFromGtIconContainer(Object iconContainer) {
        if (iconContainer == null) {
            return null;
        }
        try {
            Method getIcon = iconContainer.getClass()
                .getMethod("getIcon");
            Object icon = getIcon.invoke(iconContainer);
            if (icon instanceof IIcon) {
                String from = ExportTextureLocator.locatorFromIcon((IIcon) icon);
                if (from != null && !from.isEmpty()) {
                    return from;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Field f = iconContainer.getClass()
                .getDeclaredField("mIconName");
            f.setAccessible(true);
            Object v = f.get(iconContainer);
            if (v instanceof String) {
                // 注册时传入的 icon 名，与 getIconName() 同源信息
                return ExportTextureLocator.iconNameToLocator((String) v);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Method getTextureFile = iconContainer.getClass()
                .getMethod("getTextureFile");
            Object rl = getTextureFile.invoke(iconContainer);
            if (rl instanceof ResourceLocation) {
                String fromRl = locatorFromResourceLocationIfFileTexture((ResourceLocation) rl);
                if (fromRl != null) {
                    return fromRl;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * 将 {@code IIconContainer#getTextureFile()} 的绝对资源路径转为 {@code ns:path}；若为图集占位（{@code textures/atlas/...}）则无独立 PNG，返回 null。
     * <p>
     * <b>假设</b>：非 atlas 时去掉 {@code textures/} 与 {@code .png} 后缀；{@code blocks/} / {@code items/} 由 {@link ExportTextureLocator#normalizeLocatorForBundle} 补全。
     */
    private static String locatorFromResourceLocationIfFileTexture(ResourceLocation rl) {
        if (rl == null) {
            return null;
        }
        String path = rl.getResourcePath();
        if (path.contains("atlas")) {
            return null;
        }
        if (!path.startsWith("textures/")) {
            return null;
        }
        String p = path.substring("textures/".length());
        if (p.endsWith(".png")) {
            p = p.substring(0, p.length() - 4);
        }
        return rl.getResourceDomain() + ":" + p;
    }
}
