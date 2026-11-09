package com.solarbiscuit.atmosphera.lumis;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;
import com.solarbiscuit.atmosphera.lumis.atmosphere.LightShafts;
import com.solarbiscuit.atmosphera.lumis.shadow.SunShadows;

/** {@code config/SolConfig/atmosphera/lumis-client.toml} and the "Lumis (light)" Video Settings page. */
public final class LumisConfig {
    public static AtmosConfig.FeatureSwitch lightmap;
    public static AtmosOption<Double> blockWarmth;
    public static AtmosOption<Double> skyTint;
    public static AtmosOption<Double> caveMin;
    public static AtmosOption<Double> flicker;
    public static AtmosConfig.FeatureSwitch sunShading;
    public static AtmosOption<Double> sunStrength;
    public static AtmosOption<Double> sunBoost;
    public static AtmosOption<Double> shadeDepth;
    public static AtmosOption<Double> shadeTint;
    public static AtmosOption<Double> moon;
    public static AtmosOption<Boolean> lodShading;
    public static AtmosConfig.FeatureSwitch entityLight;
    public static AtmosOption<Double> entityStrength;
    public static AtmosConfig.FeatureSwitch shadows;
    public static AtmosOption<SunShadows.Quality> shadowQuality;
    public static AtmosOption<Double> shadowStrength;
    public static AtmosOption<Double> shadowSoftness;
    public static AtmosOption<Boolean> entityShadows;
    public static AtmosConfig.FeatureSwitch lightShafts;
    public static AtmosOption<LightShafts.Mode> shaftMode;
    public static AtmosOption<LightShafts.Quality> shaftQuality;
    public static AtmosOption<Double> shaftStrength;
    public static AtmosOption<Double> shaftMist;
    public static AtmosOption<Integer> shaftDistance;
    public static AtmosOption<Double> shaftAnisotropy;
    public static AtmosOption<Double> shaftContrast;
    public static AtmosOption<Boolean> shaftSunRays;
    public static AtmosOption<Integer> shaftDebug;
    public static AtmosOption<Double> shaftHeightTint;
    public static AtmosOption<Double> shaftSkyCookie;
    public static AtmosOption<Double> shaftBlockGlow;
    public static AtmosOption<Boolean> shaftTemporal;
    public static AtmosOption<Double> shaftUnderwater;
    public static AtmosConfig.FeatureSwitch coloredLight;
    public static AtmosOption<com.solarbiscuit.atmosphera.lumis.color.ColoredLight.Quality> colorQuality;
    public static AtmosOption<Double> colorStrength;
    public static AtmosOption<Double> colorSaturation;
    public static AtmosOption<Double> colorDark;
    public static AtmosOption<Boolean> colorDump;
    public static AtmosConfig.FeatureSwitch wetSheen;
    public static AtmosOption<Double> wetStrength;
    public static AtmosConfig.FeatureSwitch lens;
    public static AtmosOption<Double> lensBloom;
    public static AtmosOption<Double> lensFlare;
    public static AtmosOption<Double> lensVibrance;
    public static AtmosOption<Double> lensVignette;

    private LumisConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Lumis.MOD_ID, "Lumis (light)");
        config.section("lightmap", "Light colour: the colour of block light and sky light, for everything that is lit.");
        lightmap = config.featureSwitch("enabled", true, "Light colour",
                "Warmer torch and lamp light and a sky light that follows the time of day (amber dawn, rose dusk, "
                        + "blue night). Brightness stays vanilla. Also reaches entities and distant LODs.");
        blockWarmth = config.percent("block_warmth", 0.35, 0.0, 1.0, ChangeTier.LIVE, "Block light warmth",
                "How much block light takes the colour temperature of the dimension profile (warm in the Overworld).");
        skyTint = config.percent("sky_tint", 0.6, 0.0, 1.0, ChangeTier.LIVE, "Sky light tint",
                "How strongly sky light follows the time of day.");
        caveMin = config.percent("cave_min", 0.0, 0.0, 0.2, ChangeTier.LIVE, "Minimum brightness",
                "Lifts the darkest light (caves, night) to at least this level. Off while the darkness effect is on.");
        flicker = config.percent("flicker", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Torch flicker",
                "How much block light flickers. 100% is vanilla, 0% is steady.");

        config.section("sun", "Sun shading: the side of a block that faces the sun is lit, the other side is in shade.");
        sunShading = config.featureSwitch("shading", true, "Sun shading",
                "Sunlit faces get a warm boost and faces turned away from the sun a cooler shade, on terrain and "
                        + "distant Voxy LODs. Only the sky-lit part changes; torch light is left alone.");
        sunStrength = config.percent("strength", 1.0, 0.0, 1.0, ChangeTier.LIVE, "Sun shading strength",
                "How strong the whole effect is.");
        sunBoost = config.percent("sun_boost", 0.12, 0.0, 0.5, ChangeTier.LIVE, "Sunlit boost",
                "Extra brightness on faces turned to the sun.");
        shadeDepth = config.percent("shade_depth", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Shade depth",
                "How dark faces turned away from the sun get: 50% takes 30% off their sky light, 100% takes 60% off.");
        shadeTint = config.percent("shade_tint", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Shade colour",
                "How blue the shade is (light from the sky instead of the sun).");
        moon = config.percent("moon", 0.25, 0.0, 1.0, ChangeTier.LIVE, "Moonlight",
                "Strength of the same effect at night, lit from the moon.");
        lodShading = config.toggle("lod_shading", true, ChangeTier.LIVE, "Sun shading on Voxy LODs",
                "The same shading on distant Voxy LOD terrain, so there is no lighting step where the LODs begin. "
                        + "Costs about 0.07 ms of GPU time at 1440p on a fast card (more than the shading nearby).");
        config.group();
        entityLight = config.featureSwitch("entity_light", true, "Entity sun light",
                "Mobs, players and block entities are lit from the sun's side instead of vanilla's fixed directions.");
        entityStrength = config.percent("entity_strength", 0.7, 0.0, 1.0, ChangeTier.LIVE, "Entity sun light amount",
                "How far the entity light turns towards the sun.");

        config.section("shadows", "Sun shadows: terrain, trees and buildings shade what lies behind them from the sun.");
        shadows = config.featureSwitch("enabled", true, "Sun shadows",
                "Shadows of terrain, trees and buildings, from the sun and the moon, without a shadow pass: a height "
                        + "map of the world around you, rebuilt only when the sun or the camera moved. Needs sun shading. "
                        + "No shadows on distant Voxy LODs, and overhangs shade straight down.");
        shadowQuality = config.choice("quality", SunShadows.Quality.MEDIUM, ChangeTier.LIVE, "Shadow distance",
                "LOW: about 110 blocks, 1 MB of video memory. MEDIUM: about 240 blocks, 5 MB. "
                        + "HIGH: about 490 blocks, 20 MB, and smoother motion at sunrise and sunset.");
        shadowStrength = config.percent("strength", 1.0, 0.0, 1.0, ChangeTier.LIVE, "Shadow strength",
                "How much sunlight a shadow takes away (as much as on faces turned from the sun at 100%).");
        shadowSoftness = config.percent("softness", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Shadow softness",
                "How soft shadow edges get with the distance to what casts them.");
        entityShadows = config.toggle("entity_shadows", true, ChangeTier.LIVE, "Shadows on mobs",
                "Mobs and players standing in a sun shadow are darker too.");

        config.section("light_shafts", "Light shafts: sunbeams through trees and gaps, in a thin mist.");
        lightShafts = config.featureSwitch("enabled", true, "Light shafts",
                "Visible sunbeams where light falls through leaves, gaps and overhangs, and a thin mist near the "
                        + "ground that the sun lights up. Off in caves, underwater and under Fabulous graphics.");
        shaftMode = config.choice("mode", LightShafts.Mode.AUTO, ChangeTier.LIVE, "Light shaft type",
                "AUTO: real beams in the air while sun shadows are on, screen rays otherwise. RADIAL: always the "
                        + "cheaper screen rays from the sun (best for weak graphics cards).");
        shaftQuality = config.choice("quality", LightShafts.Quality.MEDIUM, ChangeTier.LIVE, "Light shaft quality",
                "LOW and MEDIUM work at a quarter of the screen width (8 and 12 steps); HIGH at half (16 steps), "
                        + "sharper beams for about four times the cost.");
        shaftStrength = config.percent("strength", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Light shaft strength",
                "How bright the beams are.");
        shaftMist = config.percent("mist", 0.3, 0.0, 1.0, ChangeTier.LIVE, "Mist",
                "How thick the air is: more mist makes beams fuller and veils distant terrain.");
        shaftDistance = config.slider("distance", 128, 64, 256, 16, ChangeTier.LIVE, "Light shaft distance",
                "How far from you beams are traced, in blocks (also limited by the shadow distance).");
        shaftAnisotropy = config.percent("forward_scatter", 0.75, 0.0, 0.95, ChangeTier.LIVE, "Glow towards the sun",
                "How much brighter the beams are when you look towards the sun.");
        shaftContrast = config.percent("beam_contrast", 0.6, 0.0, 0.9, ChangeTier.LIVE, "Beam contrast",
                "Dims air that is lit all the way (the even glow towards the sun) while keeping beams that cross "
                        + "shade, so separate rays stand out. 0 is the plain glow.");
        shaftSunRays = config.toggle("sun_rays", true, ChangeTier.LIVE, "Rays from the sun",
                "Adds streaks radiating from the sun where leaves, trees or hills cover part of it (on top of the "
                        + "beams in the air; RADIAL mode always uses them).");
        shaftHeightTint = config.percent("height_tint", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Air colour by height",
                "Mist near the ground is a little warmer (more at sunrise and sunset), high air a little cooler. "
                        + "0 keeps one colour.");
        shaftSkyCookie = config.percent("sky_cookie", 0.0, 0.0, 1.0, ChangeTier.LIVE, "Beams over open land",
                "An invisible broken cloud layer, drifting with the wind, that breaks up sunlight in the mist, so faint "
                        + "beams show over open land too (where nothing else cuts the light). Mist only: it casts no "
                        + "shadows on the ground. 0 is off.");
        shaftBlockGlow = config.percent("block_glow", 0.5, 0.0, 2.0, ChangeTier.LIVE, "Light glow in the mist",
                "Lanterns, torches, lava and other lights glow in the mist in their own colour, mostly at night. "
                        + "Follows the game's light, so the glow stops at walls. Needs coloured light and sun shadows "
                        + "(AUTO light shafts). 0 is off.");
        shaftTemporal = config.toggle("temporal", true, ChangeTier.LIVE, "Temporal light shafts",
                "Each frame marches half the steps at a shifting offset and blends with the frames before (the camera's "
                        + "motion is followed), for smoother beams at lower cost. Off: every frame marches all steps.");
        shaftUnderwater = config.percent("underwater", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Underwater light shafts",
                "With your head under water: sunbeams through the water, coloured by its depth and broken up by "
                        + "moving caustics from the surface. Uses Torrente's water colour when it is installed. Needs sun "
                        + "shadows (AUTO light shafts). 0 is off.");
        shaftDebug = config.slider("debug", 0, 0, 3, 1, ChangeTier.LIVE, "Light shaft debug view",
                "For testing: 1 march length / depth / sky (radial: open-sky share / falloff), 2 sun visibility in the "
                        + "air, 3 raw light. 0 is off.");

        config.section("colored_light", "Light colour per light source: soul fire is blue, redstone red, lava orange.");
        coloredLight = config.featureSwitch("enabled", true, "Coloured block light",
                "Block light takes the colour of the blocks that give it (torches stay as they are). Only the colour "
                        + "changes; how far light reaches is still the game's own. Nothing is added to chunk building.");
        colorQuality = config.choice("quality", com.solarbiscuit.atmosphera.lumis.color.ColoredLight.Quality.MEDIUM,
                ChangeTier.LIVE, "Coloured light range",
                "How far around you light is coloured. LOW: about 25 blocks (1 MB of video memory). MEDIUM: about 50 "
                        + "blocks (8 MB). HIGH: about 100 blocks across, 50 up and down (32 MB).");
        colorStrength = config.percent("strength", 1.0, 0.0, 1.5, ChangeTier.LIVE, "Light colour strength",
                "How strongly coloured light tints what it falls on.");
        colorSaturation = config.percent("saturation", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Light colour saturation",
                "How vivid the light colours are.");
        colorDark = config.percent("dark_light", 0.75, 0.0, 1.0, ChangeTier.LIVE, "Dark light",
                "How much the light of black lanterns (Additional Lanterns) darkens what it falls on instead of "
                        + "lighting it white. Mobs still spawn by the game's own light level. 0 gives them plain light.");
        colorDump = config.toggle("dump_colors", false, ChangeTier.LIVE, "List light colours (for pack makers)",
                "Writes every light-emitting block, the colour it gets and why to logs/lumis-light-colors.csv once, "
                        + "when a world is loaded.");

        config.section("wet", "Wet surfaces: rain darkens the ground and makes it reflect the sky.");
        wetSheen = config.featureSwitch("enabled", true, "Wet surfaces in rain",
                "While it rains, ground, roofs and walls the rain reaches turn a little darker and reflect the sky, with "
                        + "rain rings near you. They dry over about a minute after the rain stops. Not under roofs or "
                        + "trees, not in snow.");
        wetStrength = config.percent("strength", 1.0, 0.0, 1.5, ChangeTier.LIVE, "Wetness",
                "How dark and shiny wet surfaces get.");

        config.section("lens", "Lens and colour: bloom, a lens flare from the sun, vibrance and a vignette over the picture.");
        lens = config.featureSwitch("enabled", true, "Lens and colour effects",
                "Effects on the finished picture, hand included. Off under shaderpacks that do their own bloom and "
                        + "colour grading.");
        lensBloom = config.percent("bloom", 0.5, 0.0, 2.0, ChangeTier.LIVE, "Bloom",
                "Bright things glow softly into their surroundings: the sun, lava, lanterns and glowstone at night, "
                        + "sunlit snow. The sky itself does not bloom. 0 is off.");
        lensFlare = config.percent("lens_flare", 0.5, 0.0, 2.0, ChangeTier.LIVE, "Lens flare",
                "Looking towards the sun: coloured ghosts across the screen, a faint halo and a starburst. Fades as "
                        + "leaves, hills or skybox clouds cover the sun. 0 is off.");
        lensVibrance = config.percent("vibrance", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Vibrance",
                "Like NVIDIA's digital vibrance: above 50% dull colours become more vivid while already vivid ones "
                        + "change little; below 50% colours fade evenly towards grey. 50% leaves colours as they are.");
        lensVignette = config.percent("vignette", 0.0, 0.0, 1.0, ChangeTier.LIVE, "Vignette",
                "Darkens the corners of the picture. 0 is off.");
        config.register();
    }
}
