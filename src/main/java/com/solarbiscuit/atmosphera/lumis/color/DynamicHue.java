package com.solarbiscuit.atmosphera.lumis.color;

import com.solarbiscuit.atmosphera.lumis.Lumis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Coloured dynamic light: held torches, lanterns and other light items (main and off hand) of the player and of nearby
 * entities, and dropped light items. Their light itself comes from CheapLighting (RatOpt merges it into terrain and
 * entity light); Lumis only adds its colour, using CheapLighting's own sources through reflection
 * ({@code DynamicLightSource}: luminance and position), so position and reach match the light exactly:
 * {@code level = (1 − distance / 7.75) × luminance}. The colour is the brightest light item the entity carries.
 * <p>
 * The nearest {@link #MAX} sources go to the terrain shader ({@code lumis_Dyn0..3}) and to entity tinting. Without
 * CheapLighting there is no dynamic light, so nothing is coloured. Render thread.
 */
public final class DynamicHue {
    public static final int MAX = 4;
    public static final float RADIUS = 7.75f;
    private static final double RANGE_SQ = 48.0 * 48.0;

    /** Per source: x, y, z (world), luminance, packed chroma. Valid up to {@link #count}. */
    static final double[] X = new double[MAX];
    static final double[] Y = new double[MAX];
    static final double[] Z = new double[MAX];
    static final int[] LUMINANCE = new int[MAX];
    static final int[] CHROMA = new int[MAX];
    static int count;

    private static Class<?> sourceClass;
    private static MethodHandle luminance;
    private static MethodHandle lx;
    private static MethodHandle ly;
    private static MethodHandle lz;
    private static boolean probed;

    private static final double[] candDist = new double[MAX];

    private DynamicHue() {
    }

    private static boolean available() {
        if (!probed) {
            probed = true;
            if (ModList.get().isLoaded("cheaplighting")) {
                try {
                    sourceClass = Class.forName("com.misanthropy.cheaplighting.DynamicLightSource");
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    luminance = lookup.findVirtual(sourceClass, "cl$getLuminance", MethodType.methodType(int.class))
                            .asType(MethodType.methodType(int.class, Object.class));
                    lx = lookup.findVirtual(sourceClass, "cl$getDynamicLightX", MethodType.methodType(double.class))
                            .asType(MethodType.methodType(double.class, Object.class));
                    ly = lookup.findVirtual(sourceClass, "cl$getDynamicLightY", MethodType.methodType(double.class))
                            .asType(MethodType.methodType(double.class, Object.class));
                    lz = lookup.findVirtual(sourceClass, "cl$getDynamicLightZ", MethodType.methodType(double.class))
                            .asType(MethodType.methodType(double.class, Object.class));
                    Lumis.LOGGER.info("Light colour: held and dropped light items coloured through CheapLighting's light sources.");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    sourceClass = null;
                    Lumis.LOGGER.warn("Light colour: CheapLighting's light sources are not readable ({}); held lights stay uncoloured.", e.toString());
                }
            }
        }
        return sourceClass != null;
    }

    /** Entities chosen by the last full scan (up to {@link #MAX}), re-read every frame. */
    private static final Entity[] CHOSEN = new Entity[MAX];
    private static int chosen;
    private static long nextScan;
    /** Full entity scans run at most this often; positions of the chosen sources are still read every frame. */
    private static final long SCAN_INTERVAL_NS = 50_000_000L;

    /**
     * Once per frame: the nearest lit sources around the camera. Picking them walks every rendered entity, so that
     * runs at 20 Hz (0.5.8); between scans the chosen entities' light and position are read again each frame, so a
     * held light never lags behind its holder.
     */
    static void update(Minecraft mc, ClientLevel level, Vec3 camera) {
        count = 0;
        if (!available()) {
            return;
        }
        long now = System.nanoTime();
        if (now >= nextScan) {
            nextScan = now + SCAN_INTERVAL_NS;
            scan(level, camera);
        }
        for (int i = 0; i < chosen; i++) {
            Entity entity = CHOSEN[i];
            if (entity.isRemoved()) {
                continue;
            }
            int lum;
            try {
                lum = (int) luminance.invokeExact((Object) entity);
                if (lum <= 0) {
                    continue;
                }
                X[count] = (double) lx.invokeExact((Object) entity);
                Y[count] = (double) ly.invokeExact((Object) entity);
                Z[count] = (double) lz.invokeExact((Object) entity);
            } catch (Throwable t) {
                continue;
            }
            LUMINANCE[count] = Math.min(15, lum);
            CHROMA[count] = chromaOf(entity);
            count++;
        }
    }

    /** The nearest {@link #MAX} lit sources within range. */
    private static void scan(ClientLevel level, Vec3 camera) {
        chosen = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!sourceClass.isInstance(entity) || entity.distanceToSqr(camera) > RANGE_SQ) {
                continue;
            }
            int lum;
            double x;
            double y;
            double z;
            try {
                lum = (int) luminance.invokeExact((Object) entity);
                if (lum <= 0) {
                    continue;
                }
                x = (double) lx.invokeExact((Object) entity);
                y = (double) ly.invokeExact((Object) entity);
                z = (double) lz.invokeExact((Object) entity);
            } catch (Throwable t) {
                continue;
            }
            double d = (x - camera.x) * (x - camera.x) + (y - camera.y) * (y - camera.y) + (z - camera.z) * (z - camera.z);
            // Keep the nearest MAX.
            int at;
            if (chosen < MAX) {
                at = chosen++;
            } else {
                at = 0;
                for (int i = 1; i < MAX; i++) {
                    if (candDist[i] > candDist[at]) {
                        at = i;
                    }
                }
                if (candDist[at] <= d) {
                    continue;
                }
            }
            candDist[at] = d;
            CHOSEN[at] = entity;
        }
        for (int i = chosen; i < MAX; i++) {
            CHOSEN[i] = null;
        }
    }

    /** Colour of the brightest light item an entity holds (or is, for dropped items); 0 = neutral. */
    private static int chromaOf(Entity entity) {
        if (entity instanceof ItemEntity item) {
            return chromaOf(item.getItem());
        }
        if (entity instanceof LivingEntity living) {
            ItemStack main = living.getMainHandItem();
            ItemStack off = living.getOffhandItem();
            return emission(main) >= emission(off) ? chromaOf(main) : chromaOf(off);
        }
        return 0;
    }

    private static int emission(ItemStack stack) {
        BlockState state = stateOf(stack);
        return state == null ? (stack.is(Items.LAVA_BUCKET) ? 15 : 0) : state.getLightEmission();
    }

    static int chromaOf(ItemStack stack) {
        BlockState state = stateOf(stack);
        if (state != null && state.getLightEmission() > 0) {
            return EmitterColors.of(state);
        }
        if (stack.is(Items.LAVA_BUCKET) || stack.is(Items.BLAZE_ROD) || stack.is(Items.BLAZE_POWDER)
                || stack.is(Items.MAGMA_CREAM)) {
            return EmitterColors.of(Blocks.LAVA.defaultBlockState());
        }
        return 0;
    }

    private static BlockState stateOf(ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem block) {
            return block.getBlock().defaultBlockState();
        }
        return null;
    }

    /** Packs a source for the {@code lumis_DynN} slot w: luminance × 65536 + Co byte × 256 + Cg byte (exact in a float). */
    static float packW(int i) {
        if (i >= count) {
            return 0f;
        }
        int chroma = CHROMA[i];
        return LUMINANCE[i] * 65536f + ((chroma >>> 8) & 0xFF) * 256f + (chroma & 0xFF);
    }
}
