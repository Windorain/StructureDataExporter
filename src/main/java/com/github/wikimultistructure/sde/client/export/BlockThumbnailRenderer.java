package com.github.wikimultistructure.sde.client.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class BlockThumbnailRenderer {

    private static final int THUMB_SIZE = 64;
    private static Framebuffer thumbnailFbo;

    public static String renderToBase64PNG(Block block, int meta) {
        if (block == null || block == Blocks.air) return null;
        try {
            Framebuffer fbo = getOrCreateFbo();
            Framebuffer prevFbo = Minecraft.getMinecraft().getFramebuffer();

            fbo.bindFramebuffer(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Set up orthographic projection for item rendering
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(-1, 1, -1, 1, -1, 10);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);

            // Standard MC inventory item rotation
            GL11.glRotatef(30.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(-45.0F, 0.0F, 1.0F, 0.0F);
            GL11.glTranslatef(-0.5F, -0.5F, -0.5F);

            RenderBlocks rb = new RenderBlocks(); // null blockAccess = item mode
            rb.renderBlockAsItem(block, meta, 1.0F);

            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_LIGHTING);

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();

            // Read pixels (OpenGL bottom-left origin)
            ByteBuffer pixels = ByteBuffer.allocateDirect(THUMB_SIZE * THUMB_SIZE * 4);
            GL11.glReadPixels(0, 0, THUMB_SIZE, THUMB_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            pixels.rewind();

            // Convert to BufferedImage with y-flip
            BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < THUMB_SIZE; y++) {
                for (int x = 0; x < THUMB_SIZE; x++) {
                    int idx = (y * THUMB_SIZE + x) * 4;
                    int r = pixels.get(idx) & 0xFF;
                    int g = pixels.get(idx + 1) & 0xFF;
                    int b = pixels.get(idx + 2) & 0xFF;
                    int a = pixels.get(idx + 3) & 0xFF;
                    img.setRGB(x, THUMB_SIZE - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            byte[] pngBytes = baos.toByteArray();

            // Restore previous framebuffer
            if (prevFbo != null) {
                prevFbo.bindFramebuffer(true);
            }

            return Base64.getEncoder().encodeToString(pngBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Framebuffer getOrCreateFbo() {
        if (thumbnailFbo == null) {
            thumbnailFbo = new Framebuffer(THUMB_SIZE, THUMB_SIZE, true);
        }
        return thumbnailFbo;
    }

    public static void dispose() {
        if (thumbnailFbo != null) {
            thumbnailFbo.deleteFramebuffer();
            thumbnailFbo = null;
        }
    }
}
