package com.solarbiscuit.atmosphera.lumis.lightmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.lib.options.AtmosPaths;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-dimension light profiles from {@code config/Atmosphera/lumis-dimensions.json}, keyed by dimension id
 * with a {@code default} entry. The file is written with defaults when missing and re-read when it changes (checked
 * at most once a second). Render thread.
 */
public final class DimensionProfiles {
    /**
     * @param blockKelvin colour temperature of block light
     * @param skyTint     whether sky light follows the time of day
     * @param sun         whether the directional sun/moon model runs
     * @param shadows     whether sun shadows run (Lumis 0.2)
     * @param caveMin     extra minimum brightness for this dimension, added to the option
     */
    public record Profile(int blockKelvin, boolean skyTint, boolean sun, boolean shadows, float caveMin) {
        static final Profile OVERWORLD = new Profile(2900, true, true, true, 0f);

        static Profile fromJson(JsonObject o, Profile fallback) {
            return new Profile(
                    o.has("block_kelvin") ? o.get("block_kelvin").getAsInt() : fallback.blockKelvin,
                    o.has("sky_tint") ? o.get("sky_tint").getAsBoolean() : fallback.skyTint,
                    o.has("sun") ? o.get("sun").getAsBoolean() : fallback.sun,
                    o.has("shadows") ? o.get("shadows").getAsBoolean() : fallback.shadows,
                    o.has("cave_min") ? o.get("cave_min").getAsFloat() : fallback.caveMin);
        }
    }

    private static final String DEFAULTS = """
            {
              "_comment": "Lumis light per dimension id; 'default' covers every dimension not listed. block_kelvin: colour temperature of block light. sky_tint: sky light follows the time of day. sun: directional sun/moon shading. shadows: sun shadows (Lumis 0.2). cave_min: extra minimum brightness (0..0.2), added to the option.",
              "default": { "block_kelvin": 2900, "sky_tint": true, "sun": true, "shadows": true, "cave_min": 0.0 },
              "minecraft:overworld": { "block_kelvin": 2900, "sky_tint": true, "sun": true, "shadows": true, "cave_min": 0.0 },
              "minecraft:the_nether": { "block_kelvin": 2400, "sky_tint": false, "sun": false, "shadows": false, "cave_min": 0.0 },
              "minecraft:the_end": { "block_kelvin": 3600, "sky_tint": false, "sun": false, "shadows": false, "cave_min": 0.0 }
            }
            """;

    private static Map<String, Profile> profiles = Map.of();
    private static Profile fallback = Profile.OVERWORLD;
    private static long checkedAt;
    private static long modified = -1;
    private static Level lastLevel;
    private static Profile lastProfile = Profile.OVERWORLD;

    private DimensionProfiles() {
    }

    public static Profile get(Level level) {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 1000) {
            checkedAt = now;
            if (reloadIfChanged()) {
                lastLevel = null;
            }
        }
        if (level != lastLevel) {
            lastLevel = level;
            lastProfile = profiles.getOrDefault(level.dimension().location().toString(), fallback);
        }
        return lastProfile;
    }

    private static Path file() {
        return AtmosPaths.file("lumis-dimensions.json");
    }

    private static boolean reloadIfChanged() {
        Path file = file();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
            }
            long time = Files.getLastModifiedTime(file).toMillis();
            if (time == modified) {
                return false;
            }
            modified = time;
            parse(Files.readString(file, StandardCharsets.UTF_8));
            Lumis.LOGGER.info("Loaded {} dimension light profile(s) from {}.", profiles.size(), file.getFileName());
        } catch (IOException | RuntimeException e) {
            if (modified != Long.MIN_VALUE) {
                Lumis.LOGGER.warn("Could not read {} ({}); using the built-in profiles.", file, e.toString());
            }
            modified = Long.MIN_VALUE;
            parse(DEFAULTS);
        }
        return true;
    }

    private static void parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Profile def = root.has("default") && root.get("default").isJsonObject()
                ? Profile.fromJson(root.getAsJsonObject("default"), Profile.OVERWORLD) : Profile.OVERWORLD;
        Map<String, Profile> next = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("_") || "default".equals(e.getKey()) || !e.getValue().isJsonObject()) {
                continue;
            }
            next.put(e.getKey(), Profile.fromJson(e.getValue().getAsJsonObject(), def));
        }
        fallback = def;
        profiles = Map.copyOf(next);
    }
}
