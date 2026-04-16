package com.github.wikimultistructure.sde.mixin.mixins.early.minecraft.accessors;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GLAllocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState;

/**
 * 原版 {@link ModelRenderer} 首次编译后仅用 {@code glCallList}，不再走 {@link net.minecraft.client.renderer.Tessellator}，
 * 导致 {@link com.github.wikimultistructure.sde.mixin.mixins.early.minecraft.accessors.MixinTessellatorCapture} 录不到几何
 * （例：{@link net.minecraft.client.renderer.tileentity.TileEntityChestRenderer} + {@link net.minecraft.client.model.ModelChest}）。
 * 捕获激活时强制失效 display list，使 {@code compileDisplayList} 再次用 Tessellator 构建。
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRendererCapture {

    @Shadow
    private boolean compiled;

    @Shadow
    private int displayList;

    @Inject(method = "render", at = @At("HEAD"))
    private void sde$invalidateDisplayListWhenCapturing(float scale, CallbackInfo ci) {
        if (!TessellatorCaptureState.isRecording()) {
            return;
        }
        if (this.compiled && this.displayList != 0) {
            GLAllocation.deleteDisplayLists(this.displayList);
            this.displayList = 0;
        }
        this.compiled = false;
    }
}
