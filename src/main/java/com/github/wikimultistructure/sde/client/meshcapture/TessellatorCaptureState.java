package com.github.wikimultistructure.sde.client.meshcapture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.FMLLog;

import com.github.wikimultistructure.sde.client.meshcapture.finish.BlockCaptureFinishContext;
import com.github.wikimultistructure.sde.client.meshcapture.finish.BlockCaptureFinishRegistry;

/**
 * 录制 {@link Tessellator#addVertex}：{@link #beginBlock} 激活期间将顶点组成四边形（draw mode 7 = GL_QUADS）。
 * <p>
 * <strong>导出契约（与 Wiki 一致）</strong>：{@link #endBlock} 写入的顶点为<strong>块局部</strong>，相对当前方块最小角
 * [0,1]³。变换两步：
 * <ol>
 * <li>减 Tessellator {@code setTranslation}：缓冲内 xyz = addVertex 入参 + (xOffset,yOffset,zOffset)（见 MCP Tessellator）。</li>
 * <li>按 {@link CaptureCoordinatePolicy} 与<strong>每个四边形</strong>的 AABB 选择减去的原点：已在 {@code [0,1]³}（容差内）的批次（典型 ISBRH）不减世界角；落在当前块体素 {@code [wx,wx+1]×…} 内的（如 Ender IO 电容库 TESR 面板与 {@code glTranslatef} 混用导致的世界坐标 Tessellator 顶点）减 {@code (wx,wy,wz)}；否则回退为该四边形 AABB 最小角。这样可避免「同一块内混有块局部与世界局部顶点」时全局 AABB 的 min 被拉成 0，导致大坐标顶点无法归一化（例：{@code eio2.json} 中 x≈511）。</li>
 * </ol>
 * 使用<strong>全局</strong> {@link Frame} 而非 {@link ThreadLocal}，以便 GTNH Angelica 等
 * {@code @ThreadSafeISBRH(perThread = true)} 在<strong>工作线程</strong>写入 Tessellator 时仍能命中录制状态。
 */
public final class TessellatorCaptureState {

    private static final Frame CAPTURE = new Frame();

    /** {@link net.minecraft.client.model.ModelRenderer#render} 当前 scale 参数，供 glCallList 内联路径使用 */
    private static volatile float activeModelRendererScale = 0.0625F;

    private static final double BOUNDS_ASSERT_LO = -0.06;

    private static final double BOUNDS_ASSERT_HI = 1.06;

    private static final boolean ASSERT_BLOCK_LOCAL_BOUNDS = Boolean.parseBoolean(
        System.getProperty("sde.assertBlockLocalBounds", "false"));

    private TessellatorCaptureState() {}

    public static boolean isRecording() {
        return CAPTURE.active;
    }

    /**
     * @param blockX/Y/Z 结构索引格（与 cellGrid 一致）
     * @param worldBlockX/Y/Z 该格对应的世界方块角坐标（整数）
     * @param captureBlock 当前烘焙的方块（可为 null，则策略仅依赖 registryKey/renderType）
     * @param registryKey 与 palette 一致，如 {@code minecraft:stone}
     */
    public static void beginBlock(int blockX, int blockY, int blockZ, String instanceLabel, int worldBlockX, int worldBlockY,
        int worldBlockZ, Block captureBlock, int blockMeta, int renderType, String registryKey) {
        synchronized (CAPTURE) {
            Frame f = CAPTURE;
            f.blockX = blockX;
            f.blockY = blockY;
            f.blockZ = blockZ;
            f.instanceLabel = instanceLabel;
            f.worldBlockX = worldBlockX;
            f.worldBlockY = worldBlockY;
            f.worldBlockZ = worldBlockZ;
            f.captureBlock = captureBlock;
            f.blockMeta = blockMeta;
            f.renderType = renderType;
            f.registryKey = registryKey != null ? registryKey : "";
            f.geometrySource = CaptureGeometrySource.PRIMARY;
            f.lastBoundTextureKey = "";
            f.quadsForBlock.clear();
            f.currentQuadVerts.clear();
            f.dynamicExtensionVertexRecording = false;
            f.dynamicExtensionInverseMvAtRenderStartValid = false;
            f.active = true;
        }
    }

    /**
     * 由 {@code TextureManager#bindTexture} mixin 在捕获激活时调用，用于动态扩展/实体贴图等<strong>非方块图集</strong>路径。
     */
    public static void noteTextureBind(ResourceLocation loc) {
        synchronized (CAPTURE) {
            if (!CAPTURE.active || loc == null) {
                return;
            }
            String dom = loc.getResourceDomain();
            if (dom == null || dom.isEmpty()) {
                dom = "minecraft";
            }
            String path = loc.getResourcePath();
            if (path == null) {
                path = "";
            }
            CAPTURE.lastBoundTextureKey = dom + ":" + MaterialKeyResolver.normalizeMaterialKey(path);
        }
    }

    /** 在库存渲染回退前调用，标记本帧几何来自 {@code renderBlockAsItem}。 */
    public static void markInventoryFallbackForActiveCapture() {
        synchronized (CAPTURE) {
            if (CAPTURE.active) {
                CAPTURE.geometrySource = CaptureGeometrySource.INVENTORY_FALLBACK;
            }
        }
    }

    /** 动态扩展 pass（TESR 族）调度完成后，将帧几何来源标为 {@link CaptureGeometrySource#DYNAMIC_EXTENSION}。 */
    public static void markDynamicExtensionRenderForActiveCapture() {
        synchronized (CAPTURE) {
            if (CAPTURE.active) {
                CAPTURE.geometrySource = CaptureGeometrySource.DYNAMIC_EXTENSION;
            }
        }
    }

    /**
     * 仅在本帧 {@link #beginBlock} 激活且正在执行动态扩展顶点录制（如 {@code renderTileEntityAt}）时为 true。
     */
    public static void beginDynamicExtensionVertexPhase() {
        synchronized (CAPTURE) {
            if (CAPTURE.active) {
                CAPTURE.dynamicExtensionVertexRecording = true;
            }
        }
    }

    public static void endDynamicExtensionVertexPhase() {
        synchronized (CAPTURE) {
            CAPTURE.dynamicExtensionVertexRecording = false;
            CAPTURE.dynamicExtensionInverseMvAtRenderStartValid = false;
        }
    }

    /** 为 true 时 {@link net.minecraft.client.model.ModelRenderer} 走内联绘制，且 Tessellator 顶点做相对 MODELVIEW 变换。 */
    public static boolean isDynamicExtensionVertexRecording() {
        synchronized (CAPTURE) {
            return CAPTURE.active && CAPTURE.dynamicExtensionVertexRecording;
        }
    }

    /**
     * 动态扩展入口处的 {@code inv(GL_MODELVIEW)}（列主序 16 项），用于 {@code inv(M0) * M_now * v}。
     */
    public static void armDynamicPassModelViewBaselineInverse(float[] inverseColumnMajor16) {
        synchronized (CAPTURE) {
            if (CAPTURE.active && inverseColumnMajor16 != null && inverseColumnMajor16.length >= 16) {
                System.arraycopy(inverseColumnMajor16, 0, CAPTURE.dynamicExtensionInverseMvAtRenderStart, 0, 16);
                CAPTURE.dynamicExtensionInverseMvAtRenderStartValid = true;
            }
        }
    }

    public static boolean hasDynamicPassModelViewBaseline() {
        synchronized (CAPTURE) {
            return CAPTURE.active && CAPTURE.dynamicExtensionInverseMvAtRenderStartValid;
        }
    }

    public static void copyDynamicPassModelViewBaselineInverse(float[] outColumnMajor16) {
        synchronized (CAPTURE) {
            System.arraycopy(CAPTURE.dynamicExtensionInverseMvAtRenderStart, 0, outColumnMajor16, 0, 16);
        }
    }

    /** @deprecated 使用 {@link #markDynamicExtensionRenderForActiveCapture()} */
    @Deprecated
    public static void markTesrPostRenderForActiveCapture() {
        markDynamicExtensionRenderForActiveCapture();
    }

    /** @deprecated 使用 {@link #beginDynamicExtensionVertexPhase()} */
    @Deprecated
    public static void beginTesrPostVertexPhase() {
        beginDynamicExtensionVertexPhase();
    }

    /** @deprecated 使用 {@link #endDynamicExtensionVertexPhase()} */
    @Deprecated
    public static void endTesrPostVertexPhase() {
        endDynamicExtensionVertexPhase();
    }

    /** @deprecated 使用 {@link #isDynamicExtensionVertexRecording()} */
    @Deprecated
    public static boolean isTesrPostRecording() {
        return isDynamicExtensionVertexRecording();
    }

    /** @deprecated 使用 {@link #armDynamicPassModelViewBaselineInverse(float[])} */
    @Deprecated
    public static void armTesrModelViewBaselineInverse(float[] inverseColumnMajor16) {
        armDynamicPassModelViewBaselineInverse(inverseColumnMajor16);
    }

    /** @deprecated 使用 {@link #hasDynamicPassModelViewBaseline()} */
    @Deprecated
    public static boolean hasTesrModelViewBaseline() {
        return hasDynamicPassModelViewBaseline();
    }

    /** @deprecated 使用 {@link #copyDynamicPassModelViewBaselineInverse(float[])} */
    @Deprecated
    public static void copyTesrModelViewBaselineInverse(float[] outColumnMajor16) {
        copyDynamicPassModelViewBaselineInverse(outColumnMajor16);
    }

    public static void setActiveModelRendererScale(float scale) {
        activeModelRendererScale = scale;
    }

    public static float getActiveModelRendererScale() {
        return activeModelRendererScale;
    }

    public static int currentBlockRecordedQuadCount() {
        synchronized (CAPTURE) {
            if (!CAPTURE.active) {
                return 0;
            }
            return CAPTURE.quadsForBlock.size();
        }
    }

    public static void endBlock(CapturedBlockInstance target) {
        synchronized (CAPTURE) {
            Frame f = CAPTURE;
            flushPartialQuad(f);
            BlockCaptureFinishRegistry.runAll(
                new BlockCaptureFinishContext(f.registryKey, f.captureBlock, f.blockMeta, f.renderType, f.geometrySource, f.quadsForBlock));
            normalizeVerticesToBlockContract(f);
            f.active = false;
            target.x = f.blockX;
            target.y = f.blockY;
            target.z = f.blockZ;
            target.label = f.instanceLabel;
            target.quads.addAll(f.quadsForBlock);
            f.quadsForBlock.clear();
        }
    }

    private static void normalizeVerticesToBlockContract(Frame f) {
        if (f.quadsForBlock.isEmpty()) {
            return;
        }
        CaptureCoordinatePolicy.Kind kind = CaptureCoordinatePolicy.resolve(
            f.captureBlock,
            f.blockMeta,
            f.renderType,
            f.registryKey,
            f.geometrySource);
        CaptureCoordinatePolicy.logIfSpecialExtended(kind, f.registryKey);

        List<CapturedQuad> rebuilt = new ArrayList<>(f.quadsForBlock.size());
        for (CapturedQuad q : f.quadsForBlock) {
            double[] qbb = tessAdjustedQuadBounds(q);
            double[] origin = resolveOriginForQuad(qbb, f, kind);
            double ox = origin[0];
            double oy = origin[1];
            double oz = origin[2];
            List<CapturedVertex> nv = new ArrayList<>(4);
            for (CapturedVertex v : q.vertices) {
                double x0 = v.x - v.tessOffsetX;
                double y0 = v.y - v.tessOffsetY;
                double z0 = v.z - v.tessOffsetZ;
                double xf = x0 - ox;
                double yf = y0 - oy;
                double zf = z0 - oz;
                if (ASSERT_BLOCK_LOCAL_BOUNDS) {
                    assertBlockLocalVertex(f.registryKey, f.renderType, kind, xf, yf, zf);
                }
                nv.add(new CapturedVertex(xf, yf, zf, v.u, v.v, v.brightness, v.colorArgb, 0.0, 0.0, 0.0));
            }
            CapturedQuad nq = new CapturedQuad(nv);
            nq.materialKey = q.materialKey;
            nq.samplerIndex = q.samplerIndex;
            nq.bindTextureHint = q.bindTextureHint;
            nq.materialUsesStandaloneTexture = q.materialUsesStandaloneTexture;
            nq.fromDynamicExtensionPass = q.fromDynamicExtensionPass;
            rebuilt.add(nq);
        }
        f.quadsForBlock.clear();
        f.quadsForBlock.addAll(rebuilt);
    }

    /**
     * 去 Tessellator offset 后的 AABB：{@code [minX,minY,minZ,maxX,maxY,maxZ]}。
     */
    private static double[] tessAdjustedAabbBounds(List<CapturedQuad> quads) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CapturedQuad q : quads) {
            for (CapturedVertex v : q.vertices) {
                double x0 = v.x - v.tessOffsetX;
                double y0 = v.y - v.tessOffsetY;
                double z0 = v.z - v.tessOffsetZ;
                minX = Math.min(minX, x0);
                minY = Math.min(minY, y0);
                minZ = Math.min(minZ, z0);
                maxX = Math.max(maxX, x0);
                maxY = Math.max(maxY, y0);
                maxZ = Math.max(maxZ, z0);
            }
        }
        if (minX == Double.POSITIVE_INFINITY) {
            return new double[] {
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
            };
        }
        return new double[] {
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ
        };
    }

    /** 单个四边形去 Tessellator offset 后的 AABB：{@code [minX,minY,minZ,maxX,maxY,maxZ]}。 */
    private static double[] tessAdjustedQuadBounds(CapturedQuad q) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CapturedVertex v : q.vertices) {
            double x0 = v.x - v.tessOffsetX;
            double y0 = v.y - v.tessOffsetY;
            double z0 = v.z - v.tessOffsetZ;
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x0);
            maxY = Math.max(maxY, y0);
            maxZ = Math.max(maxZ, z0);
        }
        if (minX == Double.POSITIVE_INFINITY) {
            return new double[] {
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
            };
        }
        return new double[] {
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ
        };
    }

    /**
     * 为单个四边形选择减去的原点 {@code (ox,oy,oz)}，使混批顶点能正确落入块局部契约。
     */
    private static double[] resolveOriginForQuad(double[] bb, Frame f, CaptureCoordinatePolicy.Kind kind) {
        if (kind == CaptureCoordinatePolicy.Kind.ALREADY_BLOCK_LOCAL) {
            return new double[] {
                0.0,
                0.0,
                0.0
            };
        }
        final double tol = 0.08;
        double wx = f.worldBlockX;
        double wy = f.worldBlockY;
        double wz = f.worldBlockZ;

        boolean inUnitCube = bb[0] >= -tol && bb[1] >= -tol && bb[2] >= -tol && bb[3] <= 1.0 + tol && bb[4] <= 1.0 + tol
            && bb[5] <= 1.0 + tol;

        boolean inVoxelEnvelope = bb[0] >= wx - tol && bb[1] >= wy - tol && bb[2] >= wz - tol && bb[3] <= wx + 1.0 + tol
            && bb[4] <= wy + 1.0 + tol && bb[5] <= wz + 1.0 + tol;

        if (inUnitCube) {
            return new double[] {
                0.0,
                0.0,
                0.0
            };
        }
        if (inVoxelEnvelope) {
            return new double[] {
                wx,
                wy,
                wz
            };
        }
        return new double[] {
            bb[0],
            bb[1],
            bb[2]
        };
    }

    private static void assertBlockLocalVertex(String registryKey, int renderType, CaptureCoordinatePolicy.Kind kind, double x,
        double y, double z) {
        if (x < BOUNDS_ASSERT_LO || y < BOUNDS_ASSERT_LO || z < BOUNDS_ASSERT_LO || x > BOUNDS_ASSERT_HI || y > BOUNDS_ASSERT_HI
            || z > BOUNDS_ASSERT_HI) {
            FMLLog.warning(
                "[SDE] assertBlockLocalBounds: vertex (" + x + "," + y + "," + z + ") outside [0,1] for " + registryKey
                    + " renderType=" + renderType + " kind=" + kind);
        }
    }

    public static void onVertexRecorded(double x, double y, double z, double u, double v, int brightness, int colorArgb,
        double tessOffsetX, double tessOffsetY, double tessOffsetZ) {
        synchronized (CAPTURE) {
            Frame fr = CAPTURE;
            if (!fr.active) {
                return;
            }
            CapturedVertex cv = new CapturedVertex(x, y, z, u, v, brightness, colorArgb, tessOffsetX, tessOffsetY, tessOffsetZ);
            fr.currentQuadVerts.add(cv);
            if (fr.currentQuadVerts.size() == 4) {
                CapturedQuad cq = new CapturedQuad(new ArrayList<>(fr.currentQuadVerts));
                cq.bindTextureHint = fr.lastBoundTextureKey != null ? fr.lastBoundTextureKey : "";
                cq.fromDynamicExtensionPass = fr.dynamicExtensionVertexRecording;
                fr.quadsForBlock.add(cq);
                fr.currentQuadVerts.clear();
            }
        }
    }

    private static void flushPartialQuad(Frame f) {
        if (!f.currentQuadVerts.isEmpty()) {
            f.currentQuadVerts.clear();
        }
    }

    private static final class Frame {

        volatile boolean active;
        int blockX;
        int blockY;
        int blockZ;
        int worldBlockX;
        int worldBlockY;
        int worldBlockZ;
        String instanceLabel = "";
        Block captureBlock;
        int blockMeta;
        int renderType;
        String registryKey = "";
        CaptureGeometrySource geometrySource = CaptureGeometrySource.PRIMARY;
        /** 最近一次 {@link #noteTextureBind}，闭合 quad 时写入 {@link CapturedQuad#bindTextureHint} */
        String lastBoundTextureKey = "";
        /** {@link #beginDynamicExtensionVertexPhase} 与当前 {@link #beginBlock} 捕获块对齐 */
        boolean dynamicExtensionVertexRecording;
        final float[] dynamicExtensionInverseMvAtRenderStart = new float[16];
        boolean dynamicExtensionInverseMvAtRenderStartValid;
        final List<CapturedQuad> quadsForBlock = new ArrayList<>();
        final List<CapturedVertex> currentQuadVerts = new ArrayList<>();
    }

    public static final class CapturedVertex {

        public final double x;
        public final double y;
        public final double z;
        public final double u;
        public final double v;
        public final int brightness;
        /** 与 MC 1.7.10 Tessellator 小端一致：{@code alpha<<24|blue<<16|green<<8|red}（非 0xAARRGGBB 直观顺序） */
        public final int colorArgb;
        /** 录制该顶点时 Tessellator 的 x/y/zOffset；缓冲内坐标 = addVertex 入参 + offset */
        public final double tessOffsetX;
        public final double tessOffsetY;
        public final double tessOffsetZ;

        public CapturedVertex(double x, double y, double z, double u, double v, int brightness, int colorArgb,
            double tessOffsetX, double tessOffsetY, double tessOffsetZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.brightness = brightness;
            this.colorArgb = colorArgb;
            this.tessOffsetX = tessOffsetX;
            this.tessOffsetY = tessOffsetY;
            this.tessOffsetZ = tessOffsetZ;
        }
    }

    public static final class CapturedQuad {

        public final List<CapturedVertex> vertices;
        public String materialKey = "unknown";
        public int samplerIndex;
        /** 四边形闭合时 Tessellator 绑定的纹理键（domain:path 风格，path 已 normalize） */
        public String bindTextureHint = "";
        /** {@link MaterialKeyResolver#applySpriteLocalToQuad}：材质来自非图集 bind，采样器不写 blocks 图集 */
        public boolean materialUsesStandaloneTexture;
        /** 四边形闭合时处于动态扩展顶点录制（{@link Frame#dynamicExtensionVertexRecording}） */
        public boolean fromDynamicExtensionPass;

        public CapturedQuad(List<CapturedVertex> vertices) {
            this.vertices = vertices;
        }
    }

    public static final class CapturedBlockInstance {

        public int x;
        public int y;
        public int z;
        public String label;
        public final List<CapturedQuad> quads = new ArrayList<>();
    }
}
