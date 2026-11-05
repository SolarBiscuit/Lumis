package com.solarbiscuit.atmosphera.lumis.color;

import com.mojang.blaze3d.platform.NativeImage;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The colour of each light-emitting block state, as a <em>tint relative to a torch</em>: a torch, lantern, candle or
 * campfire is neutral (the lightmap grading already makes block light warm), a soul torch is cyan-blue, redstone red,
 * lava orange-red. Stored as the YCoCg chroma (Co, Cg) of the tint at luminance 1, quantized to signed bytes
 * (value × 63), packed as {@code (co & 0xFF) << 8 | (cg & 0xFF)}; 0 means neutral.
 * <p>
 * Rules, first match wins:
 * <ol>
 *   <li>vanilla ids (table);</li>
 *   <li>"soul" anywhere: soul colour; candles: neutral (their flame does not follow the wax colour);</li>
 *   <li>colour words in the id: dyes ({@code blue_iron_lantern}: Additional Lanterns' glass colour) and gems or
 *       minerals ({@code ruby_glowrose}, {@code iolite_lamp}: Silent's Gems). Gem and metal words are skipped for
 *       lanterns, torches, chains and campfires, where they name the frame, not the light;</li>
 *   <li>"redstone": red;</li>
 *   <li>the particle sprite: the dominant saturated hue (a hue histogram weighted by saturation × value; for plants
 *       a green stem loses to a petal colour), relative to the torch flame; grey sprites stay neutral;</li>
 *   <li>neutral.</li>
 * </ol>
 * Computed the first time a state is seen and cached by block-state id; reads are lock-free (0.5.8). Reset at login.
 */
public final class EmitterColors {
    private static final int UNKNOWN = 0;
    private static final float[] LUM = {0.2126f, 0.7152f, 0.0722f};

    private static final float[] NEUTRAL = {1f, 1f, 1f};
    private static final float[] SOUL = {0.35f, 0.85f, 1.35f};
    private static final float[] RED = {1.7f, 0.35f, 0.25f};

    private static final Map<String, float[]> VANILLA = new HashMap<>();
    /** Dye colours: always meaningful in an emitter's id (glass, flame or crystal colour). */
    private static final Map<String, float[]> DYES = new HashMap<>();
    /** Gems, minerals and metals: meaningful for lamps, crystals and flowers, not for lantern or torch frames. */
    private static final Map<String, float[]> GEMS = new HashMap<>();
    /** Dyenamics' extra dye names, only for Dyenamics blocks ("fluorescent" means something else elsewhere). */
    private static final Map<String, float[]> DYENAMICS = new HashMap<>();

    static {
        for (String id : new String[]{"torch", "wall_torch", "lantern", "campfire", "fire", "jack_o_lantern",
                "furnace", "smoker", "blast_furnace", "brewing_stand", "light"}) {
            VANILLA.put(id, NEUTRAL);
        }
        for (String id : new String[]{"soul_torch", "soul_wall_torch", "soul_lantern", "soul_campfire", "soul_fire"}) {
            VANILLA.put(id, SOUL);
        }
        for (String id : new String[]{"redstone_torch", "redstone_wall_torch", "redstone_ore", "deepslate_redstone_ore"}) {
            VANILLA.put(id, RED);
        }
        VANILLA.put("glowstone", rgb(1.05f, 1.05f, 0.8f));
        VANILLA.put("redstone_lamp", rgb(1.15f, 0.9f, 0.7f));
        VANILLA.put("sea_lantern", rgb(0.75f, 1.05f, 1.3f));
        VANILLA.put("conduit", rgb(0.7f, 1.05f, 1.3f));
        VANILLA.put("beacon", rgb(0.8f, 1.05f, 1.25f));
        VANILLA.put("end_rod", rgb(0.95f, 0.95f, 1.3f));
        VANILLA.put("end_gateway", rgb(0.7f, 0.9f, 1.3f));
        VANILLA.put("end_portal", rgb(0.7f, 0.9f, 1.3f));
        VANILLA.put("lava", rgb(1.4f, 0.7f, 0.35f));
        VANILLA.put("lava_cauldron", rgb(1.4f, 0.7f, 0.35f));
        VANILLA.put("magma_block", rgb(1.4f, 0.65f, 0.3f));
        VANILLA.put("shroomlight", rgb(1.25f, 0.8f, 0.5f));
        VANILLA.put("ochre_froglight", rgb(1.2f, 1.05f, 0.6f));
        VANILLA.put("verdant_froglight", rgb(0.75f, 1.25f, 0.7f));
        VANILLA.put("pearlescent_froglight", rgb(1.25f, 0.8f, 1.15f));
        VANILLA.put("crying_obsidian", rgb(0.9f, 0.5f, 1.55f));
        VANILLA.put("respawn_anchor", rgb(0.9f, 0.5f, 1.55f));
        VANILLA.put("nether_portal", rgb(0.9f, 0.45f, 1.6f));
        for (String id : new String[]{"amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud"}) {
            VANILLA.put(id, rgb(1.05f, 0.7f, 1.4f));
        }
        VANILLA.put("glow_lichen", rgb(1.0f, 1.15f, 0.8f));
        VANILLA.put("cave_vines", rgb(1.15f, 1.05f, 0.65f));
        VANILLA.put("cave_vines_plant", rgb(1.15f, 1.05f, 0.65f));
        VANILLA.put("sculk_catalyst", rgb(0.5f, 1.05f, 1.2f));
        VANILLA.put("sculk_sensor", rgb(0.5f, 1.05f, 1.2f));
        VANILLA.put("calibrated_sculk_sensor", rgb(0.5f, 1.05f, 1.2f));
        VANILLA.put("enchanting_table", rgb(0.9f, 0.8f, 1.3f));
        VANILLA.put("ender_chest", rgb(0.6f, 1.1f, 1.0f));
        VANILLA.put("dragon_egg", rgb(0.9f, 0.6f, 1.3f));
        VANILLA.put("sea_pickle", rgb(0.85f, 1.15f, 0.9f));

        DYES.put("white", NEUTRAL);
        DYES.put("orange", rgb(1.4f, 0.8f, 0.35f));
        DYES.put("magenta", rgb(1.3f, 0.5f, 1.25f));
        DYES.put("light_blue", rgb(0.6f, 0.95f, 1.4f));
        DYES.put("yellow", rgb(1.3f, 1.2f, 0.4f));
        DYES.put("lime", rgb(0.8f, 1.35f, 0.4f));
        DYES.put("pink", rgb(1.35f, 0.75f, 1.0f));
        DYES.put("gray", NEUTRAL);
        DYES.put("light_gray", NEUTRAL);
        DYES.put("cyan", rgb(0.4f, 1.15f, 1.25f));
        DYES.put("purple", rgb(0.9f, 0.5f, 1.5f));
        DYES.put("blue", rgb(0.4f, 0.55f, 1.6f));
        DYES.put("brown", rgb(1.2f, 0.85f, 0.6f));
        DYES.put("green", rgb(0.6f, 1.3f, 0.45f));
        DYES.put("red", rgb(1.5f, 0.4f, 0.35f));
        DYES.put("black", NEUTRAL);
        // Dyenamics colours.
        DYENAMICS.put("peach", rgb(1.4f, 0.9f, 0.7f));
        DYENAMICS.put("fluorescent", rgb(1.1f, 1.25f, 0.45f));
        DYENAMICS.put("mint", rgb(0.7f, 1.25f, 0.95f));
        DYENAMICS.put("maroon", rgb(1.3f, 0.45f, 0.45f));
        DYENAMICS.put("bubblegum", rgb(1.35f, 0.7f, 1.1f));
        DYENAMICS.put("lavender", rgb(1.0f, 0.8f, 1.35f));
        DYENAMICS.put("persimmon", rgb(1.4f, 0.65f, 0.4f));
        DYENAMICS.put("cherenkov", rgb(0.35f, 0.85f, 1.6f));
        DYENAMICS.put("rose", rgb(1.35f, 0.6f, 0.75f));
        DYENAMICS.put("wine", rgb(1.2f, 0.45f, 0.6f));
        DYENAMICS.put("icy_blue", rgb(0.75f, 1.0f, 1.35f));
        DYENAMICS.put("navy", rgb(0.5f, 0.55f, 1.5f));
        DYENAMICS.put("honey", rgb(1.35f, 1.0f, 0.45f));
        DYENAMICS.put("spring_green", rgb(0.6f, 1.35f, 0.7f));
        DYENAMICS.put("conifer", rgb(0.6f, 1.15f, 0.55f));
        DYENAMICS.put("ultramarine", rgb(0.5f, 0.55f, 1.6f));

        gem(1.5f, 0.35f, 0.45f, "ruby", "pyrope", "spinel");
        gem(1.3f, 0.3f, 0.25f, "garnet");
        gem(1.4f, 0.55f, 0.3f, "carnelian");
        gem(1.2f, 0.5f, 0.35f, "jasper");
        gem(1.4f, 0.9f, 0.4f, "topaz");
        gem(1.4f, 0.8f, 0.25f, "amber");
        gem(1.4f, 0.8f, 0.4f, "sunstone");
        gem(1.3f, 1.2f, 0.35f, "heliodor", "citrine", "yellow_diamond");
        gem(1.2f, 1.1f, 0.5f, "cats_eye");
        gem(0.9f, 1.3f, 0.35f, "peridot");
        gem(0.6f, 1.2f, 0.4f, "moldavite");
        gem(0.5f, 1.25f, 0.5f, "green_sapphire");
        gem(0.4f, 1.3f, 0.6f, "malachite", "emerald");
        gem(0.6f, 1.2f, 0.75f, "jade");
        gem(0.7f, 1.25f, 0.6f, "chrysoprase");
        gem(0.5f, 1.3f, 0.9f, "phosphophyllite");
        gem(0.4f, 1.2f, 1.15f, "turquoise");
        gem(0.5f, 1.15f, 1.3f, "aquamarine");
        gem(0.45f, 0.95f, 1.35f, "apatite");
        gem(0.6f, 1.0f, 1.3f, "euclase");
        gem(0.8f, 0.9f, 1.3f, "zircon");
        gem(0.7f, 1.1f, 1.25f, "diamond", "white_diamond", "fluorite");
        gem(0.35f, 0.55f, 1.6f, "sapphire", "lapis");
        gem(0.45f, 0.6f, 1.5f, "benitoite", "kyanite", "sodalite");
        gem(0.65f, 0.45f, 1.5f, "tanzanite");
        gem(0.75f, 0.45f, 1.5f, "iolite");
        gem(1.05f, 0.6f, 1.45f, "amethyst");
        gem(0.8f, 0.6f, 1.3f, "alexandrite");
        gem(1.15f, 0.7f, 1.25f, "lepidolite", "kunzite");
        gem(1.2f, 0.8f, 1.0f, "ametrine");
        gem(1.3f, 0.6f, 0.9f, "agate");
        gem(1.35f, 0.75f, 0.85f, "morganite", "rose_quartz");
        gem(1.4f, 0.7f, 0.6f, "coral");
        gem(0.85f, 0.95f, 1.2f, "moonstone");
        gem(1.1f, 1.0f, 0.95f, "opal", "ammolite", "pearl", "quartz", "black_diamond", "onyx", "tektite");
    }

    private static final java.util.Set<String> MATERIAL_AFTER_DYE = java.util.Set.of("nether", "sandstone", "sand");
    /** Words naming a flame (or a lamp with a flame-like light) when no colour word is present: neutral. */
    private static final java.util.Set<String> FLAME_WORDS = java.util.Set.of("torch", "lantern", "lamp", "sconce",
            "campfire", "fire", "brazier", "stove", "furnace", "oven", "smoker", "grill", "hookah", "fireplace", "kiln",
            "chandelier", "candelabra", "lightbulb", "bulb", "light", "lights");

    private record Tint(float[] rgb, String source) {
    }

    /**
     * {@code chroma + 1} per block-state id, 0 = not classified yet. Read without a lock: an {@code int} write is
     * atomic and a slot only ever goes from 0 to its final value, so a reader sees either and takes the locked path
     * for 0. Growing replaces the array under the lock and publishes it through the volatile field.
     */
    private static volatile int[] cache = new int[0];
    private static float[] torchSprite;
    private static String lastSource = "";

    private EmitterColors() {
    }

    private static float[] rgb(float r, float g, float b) {
        return new float[]{r, g, b};
    }

    private static void gem(float r, float g, float b, String... names) {
        for (String name : names) {
            GEMS.put(name, rgb(r, g, b));
        }
    }

    public static synchronized void reset() {
        cache = new int[0];
        torchSprite = null;
    }

    /** Packed chroma of a light-emitting state (0 = neutral). Any thread; locks only the first time a state is seen. */
    public static int of(BlockState state) {
        int id = Block.getId(state);
        int[] c = cache;
        if (id > 0 && id < c.length) {
            int cached = c[id];
            if (cached != UNKNOWN) {
                return cached - 1;
            }
        }
        return classifyLocked(state, id);
    }

    /** {@link #of} from NeoVoxy's mesh build threads (same as {@link #of}: the fast path needs no lock). */
    public static int ofAnyThread(BlockState state) {
        return of(state);
    }

    private static synchronized int classifyLocked(BlockState state, int id) {
        if (id <= 0) {
            return classify(state);
        }
        int[] c = cache;
        if (id >= c.length) {
            int[] grown = new int[Math.max(Block.BLOCK_STATE_REGISTRY.size(), id + 1)];
            System.arraycopy(c, 0, grown, 0, c.length);
            cache = grown;
            c = grown;
        }
        int cached = c[id];
        if (cached == UNKNOWN) {
            cached = classify(state) + 1;
            c[id] = cached;
        }
        return cached - 1;
    }

    public static float co(int packed) {
        return (byte) (packed >>> 8) / 63f;
    }

    public static float cg(int packed) {
        return (byte) packed / 63f;
    }

    /** The tint of a packed chroma at luminance 1 (inverse of {@link #pack}), into {@code out}. */
    public static void tint(int packed, float[] out) {
        float co = co(packed);
        float cg = cg(packed);
        float tmp = 1f - cg;
        out[0] = Math.max(0f, tmp + co);
        out[1] = Math.max(0f, 1f + cg);
        out[2] = Math.max(0f, tmp - co);
        float l = LUM[0] * out[0] + LUM[1] * out[1] + LUM[2] * out[2];
        if (l > 1e-3f) {
            out[0] /= l;
            out[1] /= l;
            out[2] /= l;
        }
    }

    private static int classify(BlockState state) {
        Tint t;
        try {
            t = rule(state);
        } catch (RuntimeException e) {
            t = new Tint(NEUTRAL, "error");
        }
        lastSource = t.source();
        return pack(t.rgb());
    }

    private static Tint rule(BlockState state) {
        // Flower pots: the plant inside decides (the pot's own sprite is terracotta).
        if (state.getBlock() instanceof FlowerPotBlock pot && pot.getContent() != net.minecraft.world.level.block.Blocks.AIR) {
            state = pot.getContent().defaultBlockState();
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = key.getPath();
        if ("minecraft".equals(key.getNamespace())) {
            float[] v = VANILLA.get(path);
            if (v != null) {
                return new Tint(v, "vanilla");
            }
            if (path.endsWith("candle") || path.endsWith("candle_cake")) {
                return new Tint(NEUTRAL, "candle");
            }
        }
        if (path.contains("soul")) {
            return new Tint(SOUL, "soul");
        }
        if (path.contains("candle")) {
            return new Tint(NEUTRAL, "candle");
        }
        float[] word = colourWord(path, key.getNamespace().startsWith("dyenamics") || path.startsWith("dyenamics"));
        if (word != null) {
            return new Tint(word, "word");
        }
        // Variants of vanilla lights in other mods (chipped:crying_obsidian_bricks, glowstone tiles...).
        float[] variant = vanillaInside(path);
        if (variant != null) {
            return new Tint(variant, "variant");
        }
        if (path.contains("redstone")) {
            return new Tint(RED, "redstone");
        }
        for (String token : path.split("_")) {
            if (FLAME_WORDS.contains(token)) {
                return new Tint(NEUTRAL, "flame");
            }
        }
        float[] c = spriteColour(state);
        if (c == null) {
            return new Tint(NEUTRAL, "sprite:grey");
        }
        float[] ref = torchReference();
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) {
            t[i] = c[i] / Math.max(ref[i], 0.05f);
        }
        return new Tint(normalise(t), "sprite");
    }

    /** The colour of the longest vanilla light id contained in a modded id as whole words (at least two words or 6 letters). */
    private static float[] vanillaInside(String path) {
        String padded = "_" + path + "_";
        float[] best = null;
        int bestLength = 0;
        for (Map.Entry<String, float[]> e : VANILLA.entrySet()) {
            String k = e.getKey();
            if (k.length() > bestLength && (k.length() >= 6 || k.contains("_")) && padded.contains("_" + k + "_")) {
                best = e.getValue();
                bestLength = k.length();
            }
        }
        // Plain flames (torch, lantern, fire...) are handled by the flame words.
        return best == NEUTRAL ? null : best;
    }

    /**
     * The colour named in an id: two-word names first (light_blue, rose_quartz, green_sapphire...), dyes always, gems
     * and minerals unless the block is a lantern, torch, chain or campfire (there they name the frame).
     */
    private static float[] colourWord(String path, boolean dyenamics) {
        String[] tokens = path.split("[_/]");
        boolean frame = path.endsWith("lantern") || path.endsWith("torch") || path.endsWith("chain")
                || path.endsWith("campfire");
        for (int i = 0; i < tokens.length; i++) {
            if (i + 1 < tokens.length) {
                String two = tokens[i] + "_" + tokens[i + 1];
                float[] d = DYES.get(two);
                if (d == null && dyenamics) {
                    d = DYENAMICS.get(two);
                }
                if (d != null) {
                    return d;
                }
                float[] g = GEMS.get(two);
                if (g != null && !frame) {
                    return g;
                }
            }
            float[] d = DYES.get(tokens[i]);
            if (d == null && dyenamics) {
                d = DYENAMICS.get(tokens[i]);
            }
            // "red nether bricks", "red sandstone": materials, not a dye.
            boolean material = i + 1 < tokens.length && MATERIAL_AFTER_DYE.contains(tokens[i + 1]);
            if (d != null && !material) {
                return d;
            }
            float[] g = GEMS.get(tokens[i]);
            if (g != null && !frame) {
                return g;
            }
        }
        return null;
    }

    private static float[] torchReference() {
        if (torchSprite == null) {
            float[] t = brightest(net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
            torchSprite = t != null ? t : normalise(rgb(1f, 0.85f, 0.55f));
        }
        return torchSprite;
    }

    private static NativeImage image(BlockState state) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        if (sprite == null) {
            return null;
        }
        NativeImage image = sprite.contents().getOriginalImage();
        if (image == null || image.getWidth() <= 0 || (long) image.getWidth() * image.getHeight() > 1 << 16) {
            return null;
        }
        return image;
    }

    /** Average of the brightest fifth of the opaque texels (the torch flame), luminance 1; or null. */
    private static float[] brightest(BlockState state) {
        NativeImage image = image(state);
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = Math.min(image.getHeight(), w * 4);
        float[] lum = new float[w * h];
        int[] rgb = new int[w * h];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                if ((abgr >>> 24) < 128) {
                    continue;
                }
                int r = abgr & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int b = (abgr >>> 16) & 0xFF;
                lum[count] = LUM[0] * r + LUM[1] * g + LUM[2] * b;
                rgb[count] = r << 16 | g << 8 | b;
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        float[] sorted = java.util.Arrays.copyOf(lum, count);
        java.util.Arrays.sort(sorted);
        float threshold = sorted[(int) (count * 0.8f)];
        float r = 0f;
        float g = 0f;
        float b = 0f;
        int used = 0;
        for (int i = 0; i < count; i++) {
            if (lum[i] >= threshold) {
                r += (rgb[i] >>> 16) & 0xFF;
                g += (rgb[i] >>> 8) & 0xFF;
                b += rgb[i] & 0xFF;
                used++;
            }
        }
        return used == 0 ? null : normalise(rgb(r / used, g / used, b / used));
    }

    /**
     * The dominant saturated colour of the state's particle sprite, luminance 1, or null when the sprite is grey:
     * a 12-bin hue histogram of the opaque texels weighted by saturation × value (texels below 0.25 saturation or
     * value are ignored); the heaviest bin's average colour wins. On plants a green bin gives way to any other bin
     * with at least a quarter of its weight (stems and leaves versus the petals that glow).
     */
    private static float[] spriteColour(BlockState state) {
        NativeImage image = image(state);
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = Math.min(image.getHeight(), w * 4);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        float total = 0f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                if ((abgr >>> 24) < 128) {
                    continue;
                }
                float r = (abgr & 0xFF) / 255f;
                float g = ((abgr >>> 8) & 0xFF) / 255f;
                float b = ((abgr >>> 16) & 0xFF) / 255f;
                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                float s = max <= 1e-4f ? 0f : (max - min) / max;
                total += max;
                if (s < 0.25f || max < 0.25f) {
                    continue;
                }
                float hue;
                float d = max - min;
                if (max == r) {
                    hue = ((g - b) / d + 6f) % 6f;
                } else if (max == g) {
                    hue = (b - r) / d + 2f;
                } else {
                    hue = (r - g) / d + 4f;
                }
                int bin = Math.min(11, (int) (hue * 2f));
                float wgt = s * max;
                weight[bin] += wgt;
                sum[bin][0] += r * wgt;
                sum[bin][1] += g * wgt;
                sum[bin][2] += b * wgt;
            }
        }
        int best = 0;
        for (int i = 1; i < 12; i++) {
            if (weight[i] > weight[best]) {
                best = i;
            }
        }
        // Bins 3-5 are 90-180 degrees (greens). Plants: prefer a non-green bin with real weight.
        boolean plant = state.getBlock() instanceof BushBlock || state.getBlock() instanceof FlowerPotBlock;
        if (plant && best >= 3 && best <= 5) {
            int alt = -1;
            for (int i = 0; i < 12; i++) {
                if ((i < 3 || i > 5) && weight[i] >= 0.25f * weight[best] && (alt < 0 || weight[i] > weight[alt])) {
                    alt = i;
                }
            }
            if (alt >= 0) {
                best = alt;
            }
        }
        // Too little colour overall: grey.
        if (weight[best] <= 0f || weight[best] < 0.08f * total) {
            return null;
        }
        float[] c = sum[best];
        return normalise(rgb(c[0] / weight[best], c[1] / weight[best], c[2] / weight[best]));
    }

    private static float[] normalise(float[] c) {
        float l = LUM[0] * c[0] + LUM[1] * c[1] + LUM[2] * c[2];
        if (l <= 1e-4f) {
            return NEUTRAL;
        }
        return rgb(c[0] / l, c[1] / l, c[2] / l);
    }

    /** YCoCg chroma of a luminance-1 tint, quantized; 0 when neutral. */
    private static int pack(float[] t) {
        float[] n = normalise(t);
        float co = (n[0] - n[2]) * 0.5f;
        float cg = (-n[0] + 2f * n[1] - n[2]) * 0.25f;
        // Soft cap on how coloured a light can be (pure sprite colours would wipe out whole channels): chroma length
        // m becomes tanh(m), so mild colours pass almost unchanged and the strongest stop below 1.
        float m = (float) Math.sqrt(co * co + cg * cg);
        if (m > 1e-4f) {
            float k = (float) Math.tanh(m) / m;
            co *= k;
            cg *= k;
        }
        int qco = Math.max(-127, Math.min(127, Math.round(co * 63f)));
        int qcg = Math.max(-127, Math.min(127, Math.round(cg * 63f)));
        return (qco & 0xFF) << 8 | (qcg & 0xFF);
    }

    /**
     * Writes every light-emitting block (its brightest state) with the colour it gets and why, to
     * {@code logs/lumis-light-colors.csv}, for reviewing a modpack's lights. Render thread, with a world loaded.
     */
    public static void dump() {
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("lumis-light-colors.csv");
        float[] tint = new float[3];
        int n = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("block,level,source,r,g,b\n");
            for (Block block : BuiltInRegistries.BLOCK) {
                BlockState brightest = null;
                for (BlockState s : block.getStateDefinition().getPossibleStates()) {
                    if (s.getLightEmission() > 0 && (brightest == null || s.getLightEmission() > brightest.getLightEmission())) {
                        brightest = s;
                    }
                }
                if (brightest == null) {
                    continue;
                }
                int packed = classify(brightest);
                tint(packed, tint);
                out.write(String.format(Locale.ROOT, "%s,%d,%s,%.2f,%.2f,%.2f%n", BuiltInRegistries.BLOCK.getKey(block),
                        brightest.getLightEmission(), lastSource, tint[0], tint[1], tint[2]));
                n++;
            }
        } catch (IOException e) {
            Lumis.LOGGER.warn("Could not write {}: {}", file, e.toString());
            return;
        }
        Lumis.LOGGER.info("Light colour: wrote {} light-emitting blocks to {}", n, file);
    }
}
