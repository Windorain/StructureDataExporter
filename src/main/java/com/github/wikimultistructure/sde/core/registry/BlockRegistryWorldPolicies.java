package com.github.wikimultistructure.sde.core.registry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 有序手工策略列表（世界采样）；与 {@link com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicies} 顺序须一致。
 */
public final class BlockRegistryWorldPolicies {

    private static final List<BlockRegistryWorldPolicy> ALL = Collections
        .unmodifiableList(Arrays.asList(new GtBlockRegistryWorldPolicy()));

    private BlockRegistryWorldPolicies() {}

    public static List<BlockRegistryWorldPolicy> all() {
        return ALL;
    }
}
