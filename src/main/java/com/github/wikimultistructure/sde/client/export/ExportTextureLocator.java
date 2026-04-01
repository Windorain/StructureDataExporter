package com.github.wikimultistructure.sde.client.export;



import net.minecraft.block.Block;

import net.minecraft.util.IIcon;

import net.minecraftforge.common.util.ForgeDirection;



import com.github.wikimultistructure.sde.core.export.GtcBlockRenderKind;



import cpw.mods.fml.relauncher.Side;

import cpw.mods.fml.relauncher.SideOnly;



/**

 * 将扫描得到的 palette 条目解析为可在资源包中定位 PNG 的 <b>locator 字符串</b>（{@code 命名空间:path}，不含 {@code .png}）。

 * <p>

 * <b>数据流（端到端）</b>

 * <ol>

 * <li>服务端 {@link com.github.wikimultistructure.sde.core.session.ExportSession} 写出 {@code pending_bundle.json}（含 palette）；

 * 客户端 {@link ExportBundleClient#tickConsumePendingIfAny} 读取后调用 {@link ExportBundleClient#writeBundle}。</li>

 * <li>{@link ExportBundleClient#writeBundle} 对每个非 air 条目调用 {@link #resolve(Block, int, GtcBlockRenderKind)}，得到 locator。</li>

 * <li>{@link #resolve}：若为 GT 机器白名单（{@link GtcBlockRenderKind#MB_MACHINE}），走 {@link GtTextureResolver#tryMetaTileEntityLocator}，

 * 从 MTE/ITexture 解出与 {@link IIcon} 等价的原始 {@code ns:path}；否则走 {@link Block#getIcon} → {@link IIcon#getIconName()}。</li>

 * <li>两条分支均在 {@link #resolve} 出口唯一调用 {@link #normalizeLocatorForBundle}（与磁盘 {@code assets/.../textures/...} 对齐）；{@link GtTextureResolver} 内部不再重复规范化。</li>

 * <li>{@link ExportBundleClient#copyTextureAndMcmeta} 用 locator 拼 {@link net.minecraft.util.ResourceLocation} 读 jar/资源包，并镜像到 bundle 的 {@code assets/}；

 * {@code material_registry} 的键与 {@code block_registry} 里 {@code materialId} 使用<b>同一</b>规范化后的 locator 字符串。</li>

 * </ol>

 * <p>

 * <b>假设（locator 语义）</b>

 * <ul>

 * <li>本导出流程中的纹理均按<b>方块侧</b>处理：{@link Block#getIcon}、MTE 纹理等对应 Minecraft <b>方块纹理图集</b>（{@code TextureMap} block 侧），

 * 而非物品图集。详见 {@link #normalizeLocatorForBundle}。</li>

 * <li>locator 的 {@code path} 段表示 {@code assets/&lt;ns&gt;/textures/&lt;path&gt;.png} 中 {@code textures/} <b>之后</b>的路径，

 * 且规范化后应含 {@code blocks/} 或 {@code items/} 之一；无此前缀时默认补 {@code blocks/}；若注册名已显式为 {@code items/...} 则保留（见 {@link #normalizeLocatorForBundle}）。</li>

 * </ul>

 */

@SideOnly(Side.CLIENT)

public final class ExportTextureLocator {



    /**

     * 与 {@link Block#getIcon(int, int)} 的 side 参数一致（ForgeDirection 序数）；用于采样一面贴图。

     * <p>

     * <b>假设</b>：与 {@link ExportBundleClient} 中写入 registry 时使用的采样侧一致，便于与游戏内方块外观对齐。

     */

    public static final int DEFAULT_SAMPLE_SIDE = 3;



    private ExportTextureLocator() {}



    /**

     * 入口：palette 条目 → 规范化后的 locator，供 {@link ExportBundleClient#writeBundle} 与 {@link ExportBundleClient#copyTextureAndMcmeta} 使用。

     * <p>

     * <b>假设</b>：见类注释；此处两条分支（MTE / 普通方块图标）最终都经 {@link #normalizeLocatorForBundle}。

     */

    public static String resolve(Block block, int meta, GtcBlockRenderKind logicalKind) {

        if (block == null) {

            return null;

        }

        if (logicalKind == GtcBlockRenderKind.MB_MACHINE) {

            // 数据流：METATILEENTITIES[meta] → getTexture → ITexture → IIcon / mIconName → 原始 ns:path

            String fromMte = GtTextureResolver

                .tryMetaTileEntityLocator(meta, DEFAULT_SAMPLE_SIDE, ForgeDirection.NORTH);

            if (fromMte != null && !isLikelyGtRenderingErrorLocator(fromMte)) {

                return normalizeLocatorForBundle(fromMte);

            }

            return null;

        }

        // 数据流：Block.getIcon → IIcon.getIconName → iconNameToLocator → normalizeLocatorForBundle

        String fromIcon = locatorFromBlockIcon(block, meta, DEFAULT_SAMPLE_SIDE);

        return fromIcon != null ? normalizeLocatorForBundle(fromIcon) : null;

    }



    /** 过滤 GT 占位/错误图（locator 字符串层面）。 */

    static boolean isLikelyGtRenderingErrorLocator(String locator) {

        if (locator == null) {

            return true;

        }

        String s = locator.toLowerCase();

        return s.contains("rendering_error") || s.contains("error_rendering");

    }



    /**

     * <b>假设</b>：{@link Block#getIcon} 返回的 {@link IIcon} 来自方块纹理图集。

     */

    static String locatorFromBlockIcon(Block block, int meta, int sampleSideOrdinal) {

        try {

            net.minecraft.util.IIcon icon = block.getIcon(sampleSideOrdinal, meta);

            return locatorFromIcon(icon);

        } catch (Throwable e) {

            return null;

        }

    }



    /**

     * {@link IIcon#getIconName()} → 中间形式 {@code ns:path}（仍可能缺 {@code blocks/} 层，由 {@link #normalizeLocatorForBundle} 补全）。

     */

    public static String locatorFromIcon(IIcon icon) {

        if (icon == null) {

            return null;

        }

        String iconName = icon.getIconName();

        if (iconName == null || iconName.isEmpty()) {

            return null;

        }

        return iconNameToLocator(iconName);

    }



    /**

     * 图集注册名（与 {@link IIcon#getIconName()} 一致）→ 带命名空间的 locator 前缀。

     * <p>

     * <b>假设</b>：无 {@code :} 的名称按 Minecraft 约定视为默认域 {@code minecraft:}。

     */

    public static String iconNameToLocator(String iconName) {

        int colon = iconName.indexOf(':');

        if (colon < 0) {

            return "minecraft:" + iconName;

        }

        return iconName;

    }



    /**

     * 将各来源的 locator 字符串规范为与 {@link net.minecraft.client.resources.IResourceManager#getResource}、磁盘 {@code assets/.../textures/...}

     * 一致的形式，供 {@link ExportBundleClient#copyTextureAndMcmeta} 拼接 {@code textures/ + path + .png}。

     * <p>

     * <b>假设（本模组导出语境）</b>：此处出现的纹理均来自<b>方块</b>侧（{@link Block#getIcon}、MTE 对方块采样等），即对应

     * Minecraft 的<b>方块纹理图集</b>（与 {@code TextureMap} 的 block sprites 一致），而非物品图集。许多模组注册名形如

     * {@code modid:iconsets/...}，相对 {@code textures/} 仍缺一层 {@code blocks/}，磁盘实际路径为

     * {@code assets/modid/textures/blocks/iconsets/...png}。

     * <p>

     * <b>处理规则</b>：去掉 path 段重复的 {@code textures/}；若 path 既不以 {@code blocks/} 也不以 {@code items/} 开头，则补上 {@code blocks/}。

     * 已显式带 {@code blocks/} 或 {@code items/} 的不再改写。可多次调用，幂等。

     */

    public static String normalizeLocatorForBundle(String locator) {

        if (locator == null) {

            return null;

        }

        int colon = locator.indexOf(':');

        if (colon < 0) {

            return locator;

        }

        String ns = locator.substring(0, colon);

        String path = locator.substring(colon + 1);

        // 避免与 copyTextureAndMcmeta 拼 "textures/" 时重复成 textures/textures/...

        while (path.startsWith("textures/")) {

            path = path.substring("textures/".length());

        }

        // 假设：未带 blocks|items 前缀的一律按方块图集补全（见类注释）

        if (!path.startsWith("blocks/") && !path.startsWith("items/")) {

            path = "blocks/" + path;

        }

        return ns + ":" + path;

    }

}


