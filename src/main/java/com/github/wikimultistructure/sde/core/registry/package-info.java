/**
 * BlockRegistry（Wiki）与结构扫描的<strong>服务端安全</strong>策略：{@link com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicy}
 * 定义 {@code matches} + {@code sample}；有序列表见 {@link com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicies}。
 * <p>
 * 客户端全量注册表写出另见 {@code com.github.wikimultistructure.sde.client.registry}（三方法含 {@code writeBlockRegistryEntry}）。
 * material_registry 仍由 {@link com.github.wikimultistructure.sde.client.export.ExportBundleClient#fillMaterialsFromBlockTextureAtlas} 独立填充，与策略正交。
 */
package com.github.wikimultistructure.sde.core.registry;
