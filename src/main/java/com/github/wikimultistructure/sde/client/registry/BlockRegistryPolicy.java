package com.github.wikimultistructure.sde.client.registry;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import com.github.wikimultistructure.sde.core.sampling.VoxelSample;
import com.google.gson.JsonObject;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * BlockRegistry（Wiki {@code BlockEntry}）写出策略：三方法须与 {@link com.github.wikimultistructure.sde.core.registry.BlockRegistryWorldPolicy} 语义一致；
 * {@link #writeBlockRegistryEntry} 仅在客户端全量 dump 中调用。
 */
@SideOnly(Side.CLIENT)
public interface BlockRegistryPolicy {

    boolean matches(Block block, String registryId, int meta);

    /** 与结构扫描共用；无坐标 dump 路径可不调用。 */
    VoxelSample sample(World world, int x, int y, int z);

    /** 向 {@code entry} 写入与 Wiki 契约一致的字段；不负责 material_registry。 */
    void writeBlockRegistryEntry(JsonObject entry, Block block, String registryId, int meta, Minecraft mc);
}
