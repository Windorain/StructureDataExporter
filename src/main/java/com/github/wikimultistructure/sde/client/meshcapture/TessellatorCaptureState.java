package com.github.wikimultistructure.sde.client.meshcapture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;

/**
 * 录制 {@link Tessellator#addVertex}：{@link #beginBlock} 激活期间将顶点组成四边形（draw mode 7 = GL_QUADS）。
 * <p>
 * 使用<strong>全局</strong> {@link Frame} 而非 {@link ThreadLocal}，以便 GTNH Angelica 等
 * {@code @ThreadSafeISBRH(perThread = true)} 在<strong>工作线程</strong>写入 Tessellator 时仍能命中录制状态
 * （见运行时 {@code addAttempts=0} + TE 已还原的日志组合）。
 */
public final class TessellatorCaptureState {

    private static final Frame CAPTURE = new Frame();

    private TessellatorCaptureState() {}

    public static boolean isRecording() {
        return CAPTURE.active;
    }

    /**
     * @param blockX/Y/Z 结构索引格（与 cellGrid 一致）
     * @param worldBlockX/Y/Z 该格对应的世界方块角坐标（整数），用于把 Tessellator 中<strong>绝对世界坐标</strong>顶点转为块局部 [0,1]³
     */
    public static void beginBlock(int blockX, int blockY, int blockZ, String instanceLabel, int worldBlockX, int worldBlockY,
        int worldBlockZ) {
        synchronized (CAPTURE) {
            Frame f = CAPTURE;
            f.blockX = blockX;
            f.blockY = blockY;
            f.blockZ = blockZ;
            f.instanceLabel = instanceLabel;
            f.worldBlockX = worldBlockX;
            f.worldBlockY = worldBlockY;
            f.worldBlockZ = worldBlockZ;
            f.quadsForBlock.clear();
            f.currentQuadVerts.clear();
            f.active = true;
        }
    }

    public static void endBlock(CapturedBlockInstance target) {
        synchronized (CAPTURE) {
            Frame f = CAPTURE;
            flushPartialQuad(f);
            maybeNormalizeWorldSpaceVertices(f);
            f.active = false;
            target.x = f.blockX;
            target.y = f.blockY;
            target.z = f.blockZ;
            target.label = f.instanceLabel;
            target.quads.addAll(f.quadsForBlock);
            f.quadsForBlock.clear();
        }
    }

    /**
     * 部分 ISBRH（如 AE2 经 {@code RenderBlocks} 世界路径）向 Tessellator 写入<strong>含方块世界原点</strong>的坐标； vanilla 部分路径则为块局部。
     * 预览端 {@code buildCapturedMesh} 假定局部 [0,1]³ + instance 格偏移；若检测到明显「世界坐标」包围盒则减去本块世界角点。
     */
    private static void maybeNormalizeWorldSpaceVertices(Frame f) {
        if (f.quadsForBlock.isEmpty()) {
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CapturedQuad q : f.quadsForBlock) {
            for (CapturedVertex v : q.vertices) {
                minX = Math.min(minX, v.x);
                minY = Math.min(minY, v.y);
                minZ = Math.min(minZ, v.z);
                maxX = Math.max(maxX, v.x);
                maxY = Math.max(maxY, v.y);
                maxZ = Math.max(maxZ, v.z);
            }
        }
        final double lo = -0.5;
        final double hi = 2.5;
        boolean worldLike = minX < lo || minY < lo || minZ < lo || maxX > hi || maxY > hi || maxZ > hi;
        if (!worldLike) {
            return;
        }
        double ox = f.worldBlockX;
        double oy = f.worldBlockY;
        double oz = f.worldBlockZ;
        List<CapturedQuad> rebuilt = new ArrayList<>(f.quadsForBlock.size());
        for (CapturedQuad q : f.quadsForBlock) {
            List<CapturedVertex> nv = new ArrayList<>(4);
            for (CapturedVertex v : q.vertices) {
                nv.add(
                    new CapturedVertex(
                        v.x - ox,
                        v.y - oy,
                        v.z - oz,
                        v.u,
                        v.v,
                        v.brightness,
                        v.colorArgb));
            }
            CapturedQuad nq = new CapturedQuad(nv);
            nq.materialKey = q.materialKey;
            nq.samplerIndex = q.samplerIndex;
            rebuilt.add(nq);
        }
        f.quadsForBlock.clear();
        f.quadsForBlock.addAll(rebuilt);
    }

    public static void onVertexRecorded(double x, double y, double z, double u, double v, int brightness, int colorArgb) {
        synchronized (CAPTURE) {
            Frame f = CAPTURE;
            if (!f.active) {
                return;
            }
            CapturedVertex cv = new CapturedVertex(x, y, z, u, v, brightness, colorArgb);
            f.currentQuadVerts.add(cv);
            if (f.currentQuadVerts.size() == 4) {
                f.quadsForBlock.add(new CapturedQuad(new ArrayList<>(f.currentQuadVerts)));
                f.currentQuadVerts.clear();
            }
        }
    }

    private static void flushPartialQuad(Frame f) {
        if (!f.currentQuadVerts.isEmpty()) {
            // Incomplete quad at block boundary — drop or pad; dropping avoids garbage geometry.
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
        String instanceLabel;
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

        public CapturedVertex(double x, double y, double z, double u, double v, int brightness, int colorArgb) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.brightness = brightness;
            this.colorArgb = colorArgb;
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
