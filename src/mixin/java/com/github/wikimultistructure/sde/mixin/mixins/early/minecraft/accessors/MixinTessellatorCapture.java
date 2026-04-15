package com.github.wikimultistructure.sde.mixin.mixins.early.minecraft.accessors;

import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.wikimultistructure.sde.client.meshcapture.TessellatorCaptureState;

/**
 * Records vertices into {@link TessellatorCaptureState} while a capture block is active.
 */
@Mixin(Tessellator.class)
public abstract class MixinTessellatorCapture {

    @Shadow
    private int rawBufferIndex;

    @Shadow
    private int[] rawBuffer;

    @Shadow
    private boolean hasTexture;

    @Shadow
    private boolean hasBrightness;

    @Shadow
    private boolean hasColor;

    @Inject(method = "addVertex", at = @At("TAIL"))
    private void sde$afterAddVertex(double x, double y, double z, CallbackInfo ci) {
        if (!TessellatorCaptureState.isRecording() || !this.hasTexture) {
            return;
        }
        int base = this.rawBufferIndex - 8;
        if (base < 0) {
            return;
        }
        double vx = Float.intBitsToFloat(this.rawBuffer[base]);
        double vy = Float.intBitsToFloat(this.rawBuffer[base + 1]);
        double vz = Float.intBitsToFloat(this.rawBuffer[base + 2]);
        double u = Float.intBitsToFloat(this.rawBuffer[base + 3]);
        double v = Float.intBitsToFloat(this.rawBuffer[base + 4]);
        int color = this.hasColor ? this.rawBuffer[base + 5] : 0xFFFFFFFF;
        int brightness = this.hasBrightness ? this.rawBuffer[base + 7] : 0;
        TessellatorCaptureState.onVertexRecorded(vx, vy, vz, u, v, brightness, color);
    }
}
