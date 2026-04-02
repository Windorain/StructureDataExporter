package com.github.wikimultistructure.sde.core.registry;

import java.util.List;

import com.github.wikimultistructure.sde.core.registry.gt.GtGregtechRegistryPolicyOrder;

/**
 * 有序手工策略列表（世界采样）；与 {@link com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicies} 顺序须一致
 * （见 {@link GtGregtechRegistryPolicyOrder}）。
 */
public final class BlockRegistryWorldPolicies {

    private static final List<BlockRegistryWorldPolicy> ALL = GtGregtechRegistryPolicyOrder.orderedWorldPolicies();

    private BlockRegistryWorldPolicies() {}

    public static List<BlockRegistryWorldPolicy> all() {
        return ALL;
    }
}
