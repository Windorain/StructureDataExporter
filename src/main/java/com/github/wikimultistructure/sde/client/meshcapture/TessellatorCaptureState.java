package com.github.wikimultistructure.sde.client.meshcapture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;

import cpw.mods.fml.common.FMLLog;

/**
 * 录制 {@link Tessellator#addVertex}：{@link #beginBlock} 激活期间将顶点组成四边形（draw mode 7 = GL_QUADS）。
 * <p>
 * <strong>导出契约（与 Wiki 一致）</strong>：{@link #endBlock} 写入的顶点为<strong>块局部</strong>，相对当前方块最小角
 * [0,1]³。变换两步（解析式，无 AABB 启发式）：
 * <ol>
 * <li>减 Tessellator {@code setTranslation}：缓冲内 xyz = addVertex 入参 + (xOffset,yOffset,zOffset)（见 MCP Tessellator）。</li>
 * <li>按 {@link CaptureCoordinatePolicy}：在<strong>结构格 (blockX,Y,Z)</strong> 与 <strong>世界角点 (worldBlockX,Y,Z)</strong> 中择一减去，
 * 使结果落在单位块内（原版多为结构格；部分模组 Tessellator 为世界坐标）。{@link CaptureCoordinatePolicy.Kind#ALREADY_BLOCK_LOCAL} 则不再减。</li>
 * </ol>
 * 使用<strong>全局</strong> {@link Frame} 而非 {@link ThreadLocal}，以便 GTNH Angelica 等
 * {@code @ThreadSafeISBRH(perThread = true)} 在<strong>工作线程</strong>写入 Tessellator 时仍能命中录制状态。
 */
public final class TessellatorCaptureState {

    private static final Frame CAPTURE = new Frame();

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
            f.inventoryFallback = false;
            f.quadsForBlock.clear();
            f.currentQuadVerts.clear();
            f.active = true;
        }
    }

    /** 在库存渲染回退前调用，标记本帧几何来自 {@code renderBlockAsItem}。 */
    public static void markInventoryFallbackForActiveCapture() {
        synchronized (CAPTURE) {
            if (CAPTURE.active) {
                CAPTURE.inventoryFallback = true;
            }
        }
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
            f.inventoryFallback);
        CaptureCoordinatePolicy.logIfSpecialExtended(kind, f.registryKey);

        final double bx = f.blockX;
        final double by = f.blockY;
        final double bz = f.blockZ;
        final double wx = f.worldBlockX;
        final double wy = f.worldBlockY;
        final double wz = f.worldBlockZ;

        final double ox;
        final double oy;
        final double oz;
        if (kind == CaptureCoordinatePolicy.Kind.ALREADY_BLOCK_LOCAL) {
            ox = oy = oz = 0.0;
        } else {
            boolean structFits = quadSetFitsUnitBlockAfterOriginSubtract(f.quadsForBlock, bx, by, bz);
            boolean worldFits = quadSetFitsUnitBlockAfterOriginSubtract(f.quadsForBlock, wx, wy, wz);
            if (structFits && !worldFits) {
                ox = bx;
                oy = by;
                oz = bz;
            } else if (worldFits && !structFits) {
                ox = wx;
                oy = wy;
                oz = wz;
            } else if (structFits) {
                ox = bx;
                oy = by;
                oz = bz;
            } else if (worldFits) {
                ox = wx;
                oy = wy;
                oz = wz;
            } else {
                ox = bx;
                oy = by;
                oz = bz;
            }
        }

        List<CapturedQuad> rebuilt = new ArrayList<>(f.quadsForBlock.size());
        for (CapturedQuad q : f.quadsForBlock) {
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
            rebuilt.add(nq);
        }
        f.quadsForBlock.clear();
        f.quadsForBlock.addAll(rebuilt);
    }

    /**
     * 判定：所有顶点在减 Tessellator offset 后，再减 (ox,oy,oz) 是否落在单格 [0,1]³（带容差）。
     */
    private static boolean quadSetFitsUnitBlockAfterOriginSubtract(List<CapturedQuad> quads, double ox, double oy, double oz) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CapturedQuad q : quads) {
            for (CapturedVertex v : q.vertices) {
                double tx = v.x - v.tessOffsetX - ox;
                double ty = v.y - v.tessOffsetY - oy;
                double tz = v.z - v.tessOffsetZ - oz;
                minX = Math.min(minX, tx);
                minY = Math.min(minY, ty);
                minZ = Math.min(minZ, tz);
                maxX = Math.max(maxX, tx);
                maxY = Math.max(maxY, ty);
                maxZ = Math.max(maxZ, tz);
            }
        }
        if (minX == Double.POSITIVE_INFINITY) {
            return false;
        }
        final double tol = 0.08;
        double sx = maxX - minX;
        double sy = maxY - minY;
        double sz = maxZ - minZ;
        return minX >= -tol && minY >= -tol && minZ >= -tol && maxX <= 1.0 + tol && maxY <= 1.0 + tol && maxZ <= 1.0 + tol
            && sx <= 1.0 + tol && sy <= 1.0 + tol && sz <= 1.0 + tol;
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
                fr.quadsForBlock.add(new CapturedQuad(new ArrayList<>(fr.currentQuadVerts)));
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
        boolean inventoryFallback;
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
        /** Packed ARGB from Tessellator */
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
