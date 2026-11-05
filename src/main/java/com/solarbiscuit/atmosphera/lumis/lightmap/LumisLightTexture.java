package com.solarbiscuit.atmosphera.lumis.lightmap;

/** Implemented by {@code LightTexture} through {@code LightTextureMixin}. */
public interface LumisLightTexture {
    /** Updates the lightmap on the next frame (vanilla's own flag; BadOptimizations' cache honours it too). */
    void lumis$markDirty();
}
