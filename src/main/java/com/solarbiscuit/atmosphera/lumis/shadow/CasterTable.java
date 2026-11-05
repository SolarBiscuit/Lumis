package com.solarbiscuit.atmosphera.lumis.shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Whether a block state casts a sun shadow, and how tall it is: 1..16 (sixteenths of a block, so slabs, farmland and
 * snow layers do not shadow their own top face), or {@link #NONE}. Computed the first time a state is seen and cached
 * by block-state id. Render thread only; reset at login, when the ids are reassigned.
 * <p>
 * Casters: leaves, opaque full blocks, and blocks with a collision shape that covers at least half the footprint and
 * stops skylight. Not casters: air, fluids, glass, plants, and thin things such as fences and torches (a whole column
 * of shade under a fence post would look wrong in a 2.5D heightfield).
 */
public final class CasterTable {
    public static final int NONE = 17;
    private static final int UNKNOWN = 0;

    private static byte[] table = new byte[0];

    private CasterTable() {
    }

    public static void reset() {
        table = new byte[0];
    }

    /** Caster height in sixteenths (1..16), or {@link #NONE}. */
    public static int of(BlockState state) {
        int id = Block.getId(state);
        if (id < 0 || (id == 0 && !state.isAir())) {
            // Unregistered, or ids not assigned yet (an empty IdMapper answers 0 for everything): do not cache.
            return classify(state);
        }
        if (id >= table.length) {
            int size = Math.max(Block.BLOCK_STATE_REGISTRY.size(), id + 1);
            byte[] grown = new byte[size];
            System.arraycopy(table, 0, grown, 0, table.length);
            table = grown;
        }
        int cached = table[id];
        if (cached == UNKNOWN) {
            cached = classify(state);
            table[id] = (byte) cached;
        }
        return cached;
    }

    private static int classify(BlockState state) {
        try {
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                return NONE;
            }
            if (state.getBlock() instanceof LeavesBlock) {
                return 16;
            }
            // canOcclude alone is not enough: slabs (vanilla and RatSlabs' terrain slabs) occlude too, and counting a
            // bottom slab as a full block put its own top face in shadow.
            if (state.canOcclude() && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                return 16;
            }
            if (state.propagatesSkylightDown(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                return NONE;
            }
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return NONE;
            }
            AABB box = shape.bounds();
            if ((box.maxX - box.minX) * (box.maxZ - box.minZ) < 0.5 || box.maxY <= 0.0) {
                return NONE;
            }
            return Math.max(1, Math.min(16, (int) Math.ceil(box.maxY * 16.0 - 1e-4)));
        } catch (RuntimeException e) {
            // Modded blocks that need a real level for their shape: treat as not casting.
            return NONE;
        }
    }
}
