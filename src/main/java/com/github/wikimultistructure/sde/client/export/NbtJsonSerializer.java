package com.github.wikimultistructure.sde.client.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.NBTBase;

public final class NbtJsonSerializer {
    private static final Gson GSON = new GsonBuilder()
        .registerTypeHierarchyAdapter(NBTBase.class, new NbtTagSerializer())
        .create();

    public static String toJson(NBTBase nbt) {
        return GSON.toJson(nbt);
    }
}
