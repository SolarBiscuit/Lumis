package com.solarbiscuit.atmosphera.lumis.color;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Light colours from resource packs (0.9.0): {@code assets/lumis/light_colors/*.json}, read at every resource reload.
 * They win over Lumis's own rules ({@link EmitterColors}). Format:
 * <pre>{@code
 * {
 *   "colors": {
 *     "somemod:green_lamp": "#55FF66",     // the light's colour; white is a torch's (no tint)
 *     "somemod:*": "#FFD9A0",               // every light of a mod (or any id prefix ending in *)
 *     "somemod:shadow_lamp": "dark",        // dark light: dims instead of colouring
 *     "minecraft:glowstone": "neutral"      // no tint
 *   }
 * }
 * }</pre>
 * Packs higher in the list win over lower ones for the same id; an exact id wins over a prefix, and a longer prefix
 * over a shorter one. A changed set recolours the world (the colour volume restarts).
 */
public final class PackColors extends SimplePreparableReloadListener<PackColors.Table> {
    /** Value for "dark" entries. */
    static final float[] DARK = new float[0];

    record Table(Map<ResourceLocation, float[]> exact, List<Map.Entry<String, float[]>> prefixes, int files) {
        static final Table EMPTY = new Table(Map.of(), List.of(), 0);

        float[] find(ResourceLocation id) {
            float[] c = exact.get(id);
            if (c != null || prefixes.isEmpty()) {
                return c;
            }
            String s = id.toString();
            for (Map.Entry<String, float[]> e : prefixes) {   // longest first
                if (s.startsWith(e.getKey())) {
                    return e.getValue();
                }
            }
            return null;
        }

        int size() {
            return exact.size() + prefixes.size();
        }
    }

    private static volatile Table table = Table.EMPTY;

    /** The pack colour of a block, null when no pack names it; {@link #DARK} for dark light. */
    static float[] find(ResourceLocation id) {
        return table.find(id);
    }

    @Override
    protected Table prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, float[]> exact = new HashMap<>();
        Map<String, float[]> prefix = new HashMap<>();
        int files = 0;
        Map<ResourceLocation, List<Resource>> stacks = manager.listResourceStacks("light_colors",
                rl -> Lumis.MOD_ID.equals(rl.getNamespace()) && rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, List<Resource>> stack : new java.util.TreeMap<>(stacks).entrySet()) {
            for (Resource resource : stack.getValue()) {   // lowest pack first, so higher packs overwrite
                files++;
                try (Reader reader = resource.openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject colors = root.has("colors") ? root.getAsJsonObject("colors") : root;
                    for (Map.Entry<String, JsonElement> e : colors.entrySet()) {
                        float[] c = parse(e.getValue().getAsString());
                        if (c == null) {
                            Lumis.LOGGER.warn("Light colours {} ({}): {} has an unknown colour \"{}\" (use #RRGGBB, neutral "
                                    + "or dark)", stack.getKey(), resource.sourcePackId(), e.getKey(), e.getValue());
                            continue;
                        }
                        String key = e.getKey().trim().toLowerCase(Locale.ROOT);
                        if (key.endsWith("*")) {
                            prefix.put(key.substring(0, key.length() - 1), c);
                        } else {
                            ResourceLocation id = ResourceLocation.tryParse(key);
                            if (id == null) {
                                Lumis.LOGGER.warn("Light colours {} ({}): \"{}\" is not a block id", stack.getKey(),
                                        resource.sourcePackId(), e.getKey());
                                continue;
                            }
                            exact.put(id, c);
                        }
                    }
                } catch (Exception ex) {
                    Lumis.LOGGER.warn("Light colours {} ({}) could not be read: {}", stack.getKey(), resource.sourcePackId(),
                            ex.toString());
                }
            }
        }
        List<Map.Entry<String, float[]>> prefixes = new ArrayList<>(prefix.entrySet());
        prefixes.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return new Table(Map.copyOf(exact), List.copyOf(prefixes), files);
    }

    @Override
    protected void apply(Table loaded, ResourceManager manager, ProfilerFiller profiler) {
        Table old = table;
        table = loaded;
        Lumis.LOGGER.info("Light colours from resource packs: {} entries in {} file(s).", loaded.size(), loaded.files());
        if (!sameTable(old, loaded)) {
            ColoredLight.recolour();
        }
    }

    private static boolean sameTable(Table a, Table b) {
        if (a.exact().size() != b.exact().size() || a.prefixes().size() != b.prefixes().size()) {
            return false;
        }
        for (Map.Entry<ResourceLocation, float[]> e : a.exact().entrySet()) {
            if (!java.util.Arrays.equals(e.getValue(), b.exact().get(e.getKey()))) {
                return false;
            }
        }
        for (int i = 0; i < a.prefixes().size(); i++) {
            if (!a.prefixes().get(i).getKey().equals(b.prefixes().get(i).getKey())
                    || !java.util.Arrays.equals(a.prefixes().get(i).getValue(), b.prefixes().get(i).getValue())) {
                return false;
            }
        }
        return true;
    }

    /** "#RRGGBB", "RRGGBB", "#RGB", "neutral" or "dark"; null when unknown. */
    static float[] parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("dark")) {
            return DARK;
        }
        if (v.equals("neutral") || v.equals("none")) {
            return new float[]{1f, 1f, 1f};
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        } else if (v.startsWith("0x")) {
            v = v.substring(2);
        }
        if (v.length() == 3) {
            v = "" + v.charAt(0) + v.charAt(0) + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2);
        }
        if (v.length() != 6) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(v, 16);
            return new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
