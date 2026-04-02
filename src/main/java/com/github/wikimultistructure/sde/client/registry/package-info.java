/**
 * BlockRegistry 客户端 dump 策略：{@link com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicy}（matches、sample、writeBlockRegistryEntry），
 * 有序注册 {@link com.github.wikimultistructure.sde.client.registry.BlockRegistryPolicies}。未命中策略时
 * {@link com.github.wikimultistructure.sde.core.registry.BlockRegistryJson#writeUnknownEntry}。
 * <p>
 * 世界扫描路径使用 {@link com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicy} 与
 * {@link com.github.wikimultistructure.sde.core.sampling.PolicyBackedBlockSampler}，与 dump 策略命中条件须保持一致。
 */
package com.github.wikimultistructure.sde.client.registry;
