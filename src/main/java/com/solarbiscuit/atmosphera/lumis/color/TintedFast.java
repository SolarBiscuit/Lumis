package com.solarbiscuit.atmosphera.lumis.color;

import net.caffeinemc.mods.sodium.api.vertex.attributes.CommonVertexAttribute;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The tinting consumer on Embeddium's fast path (0.9.0, audit R7): Embeddium writes entity models as whole blocks of
 * vertices through {@link VertexBufferWriter#push} when the consumer supports it, and falls back to one call per
 * vertex attribute when it does not, which is what a tinted entity cost before. This one takes the block, copies it
 * to scratch memory, multiplies the colour attribute (ABGR bytes) and pushes it on to the real consumer.
 * <p>
 * Loaded only when Embeddium (Xenon) is installed: {@link TintedBuffers} creates it through {@link #create}, so its
 * interfaces are never resolved otherwise.
 */
final class TintedFast extends TintedBuffers.Tinted implements VertexBufferWriter {
    static TintedBuffers.Tinted create() {
        return new TintedFast();
    }

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormatDescription format) {
        VertexBufferWriter out = VertexBufferWriter.of(d);
        if (!format.containsElement(CommonVertexAttribute.COLOR)) {
            out.push(stack, ptr, count, format);
            return;
        }
        int stride = format.stride();
        long bytes = (long) count * stride;
        long copy = stack.nmalloc(8, (int) bytes);
        MemoryUtil.memCopy(ptr, copy, bytes);
        long p = copy + format.getElementOffset(CommonVertexAttribute.COLOR);
        for (int i = 0; i < count; i++, p += stride) {
            int c = MemoryUtil.memGetInt(p);
            int red = TintedBuffers.clampByte((c & 0xFF) * r);
            int green = TintedBuffers.clampByte((c >>> 8 & 0xFF) * g);
            int blue = TintedBuffers.clampByte((c >>> 16 & 0xFF) * b);
            MemoryUtil.memPutInt(p, c & 0xFF000000 | blue << 16 | green << 8 | red);
        }
        out.push(stack, copy, count, format);
    }

    @Override
    public boolean canUseIntrinsics() {
        VertexBufferWriter w = VertexBufferWriter.tryOf(d);
        return w != null && w.canUseIntrinsics();
    }

    @Override
    public boolean isFullWriter() {
        VertexBufferWriter w = VertexBufferWriter.tryOf(d);
        return w != null && w.isFullWriter();
    }
}
