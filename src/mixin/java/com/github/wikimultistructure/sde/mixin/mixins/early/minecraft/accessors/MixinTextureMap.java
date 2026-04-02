package com.github.wikimultistructure.sde.mixin.mixins.early.minecraft.accessors;

import java.util.Map;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.github.wikimultistructure.sde.mixin.interfaces.accessors.TextureMapAccessor;

@Mixin(TextureMap.class)
public abstract class MixinTextureMap implements TextureMapAccessor {

    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapRegisteredSprites;

    @Override
    public Map<String, TextureAtlasSprite> sde$getMapRegisteredSprites() {
        return mapRegisteredSprites;
    }
}
