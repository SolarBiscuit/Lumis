package com.solarbiscuit.atmosphera.lumis.color;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;

/**
 * A {@link MultiBufferSource} whose vertex consumers multiply every vertex colour by a tint: how entities, block
 * entities and held items get the colour of the light they stand in, without touching vanilla's shaders. Only used
 * when the tint is visibly not neutral, so everything else keeps the fast paths. With Embeddium (0.9.0) the wrapper
 * also takes Embeddium's whole-block vertex writes ({@link TintedFast}), so a tinted entity keeps its fast path too.
 * <p>
 * One instance, reused (0.5.8): entities, block entities and hands render one after another on the render thread, and
 * a nested tint request (a passenger, an item in a hand) keeps the outer one ({@link ColoredLight#tint}). The wrapping
 * consumers come from a pool that restarts with each {@link #reset}; vanilla never keeps a consumer past the render
 * call that asked for it.
 */
public final class TintedBuffers implements MultiBufferSource {
    static final TintedBuffers INSTANCE = new TintedBuffers();

    private MultiBufferSource delegate;
    private float r;
    private float g;
    private float b;
    private final ArrayList<Tinted> pool = new ArrayList<>();
    private int used;

    /** Embeddium's vertex writer API is present (Xenon / Embeddium installed): use {@link TintedFast}. */
    private static final boolean FAST = hasWriterApi();

    private TintedBuffers() {
    }

    private static boolean hasWriterApi() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter", false,
                    TintedBuffers.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static int clampByte(float v) {
        return clamp(v);
    }

    /** Starts tinting another object. Render thread. */
    TintedBuffers reset(MultiBufferSource delegate, float r, float g, float b) {
        this.delegate = delegate;
        this.r = r;
        this.g = g;
        this.b = b;
        used = 0;
        return this;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        Tinted t;
        if (used < pool.size()) {
            t = pool.get(used);
        } else {
            t = FAST ? TintedFast.create() : new Tinted();
            pool.add(t);
        }
        used++;
        // Always ask the delegate: getBuffer may end the previous batch, so its result is never cached.
        t.d = delegate.getBuffer(type);
        t.r = r;
        t.g = g;
        t.b = b;
        return t;
    }

    private static int clamp(float v) {
        return v <= 0f ? 0 : v >= 255f ? 255 : (int) v;
    }

    static class Tinted implements VertexConsumer {
        VertexConsumer d;
        float r;
        float g;
        float b;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            d.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            d.color(clamp(red * r), clamp(green * g), clamp(blue * b), alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            d.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            d.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            d.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            d.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            d.endVertex();
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                           int overlay, int light, float nx, float ny, float nz) {
            d.vertex(x, y, z, Math.min(1f, red * r), Math.min(1f, green * g), Math.min(1f, blue * b), alpha, u, v,
                    overlay, light, nx, ny, nz);
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            d.defaultColor(clamp(red * r), clamp(green * g), clamp(blue * b), alpha);
        }

        @Override
        public void unsetDefaultColor() {
            d.unsetDefaultColor();
        }
    }
}
