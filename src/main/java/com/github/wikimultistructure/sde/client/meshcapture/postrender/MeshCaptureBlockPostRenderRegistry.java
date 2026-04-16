package com.github.wikimultistructure.sde.client.meshcapture.postrender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 注册表：主绘制后的补画仅通过本类 {@link #dispatch} 调度，避免散装回调。
 * <p>
 * 内置已注册 {@link ForgeMultipartDynamicPostRenderStrategy}（priority={@value ForgeMultipartDynamicPostRenderStrategy#DEFAULT_PRIORITY}）。
 * 自定义策略：实现 {@link MeshCaptureBlockPostRenderStrategy}，{@code priority} 取更小值以优先检测；在
 * {@link MeshCaptureBlockPostRenderStrategy#renderPostMainBlock} 内可委托
 * {@link ForgeMultipartDynamicPostRenderStrategy#renderDynamicPartsIfMultipart(MeshCaptureBlockPostRenderContext)}。
 * 于客户端 init 调用 {@link #register(MeshCaptureBlockPostRenderStrategy)} 一次即可。
 */
@SideOnly(Side.CLIENT)
public final class MeshCaptureBlockPostRenderRegistry {

    private static final CopyOnWriteArrayList<MeshCaptureBlockPostRenderStrategy> STRATEGIES = new CopyOnWriteArrayList<>();

    static {
        register(new ForgeMultipartDynamicPostRenderStrategy());
    }

    private MeshCaptureBlockPostRenderRegistry() {}

    /** 注册策略；集成模组在客户端 init 调用即可。 */
    public static void register(MeshCaptureBlockPostRenderStrategy strategy) {
        if (strategy != null) {
            STRATEGIES.add(strategy);
        }
    }

    /**
     * 按 priority 升序扫描，首个 {@code applies} 为 true 的策略执行 {@code renderPostMainBlock} 后返回。
     */
    public static void dispatch(MeshCaptureBlockPostRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        List<MeshCaptureBlockPostRenderStrategy> sorted = new ArrayList<>(STRATEGIES);
        sorted.sort(Comparator.comparingInt(MeshCaptureBlockPostRenderStrategy::priority));
        for (MeshCaptureBlockPostRenderStrategy s : sorted) {
            try {
                if (s.applies(ctx)) {
                    s.renderPostMainBlock(ctx);
                    return;
                }
            } catch (Throwable t) {
                FMLLog.warning("[SDE] MeshCaptureBlockPostRenderRegistry: strategy %s failed: %s", s.getClass()
                    .getName(), t.getMessage());
            }
        }
    }
}
