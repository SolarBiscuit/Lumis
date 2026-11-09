package com.solarbiscuit.atmosphera.lumis;

import com.solarbiscuit.atmosphera.lumis.atmosphere.LightShafts;
import com.solarbiscuit.atmosphera.lumis.lightmap.LightmapGrader;
import com.solarbiscuit.atmosphera.lumis.shadow.SunShadows;
import com.solarbiscuit.atmosphera.lumis.sun.LightFeatures;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Lumis.MOD_ID)
public final class Lumis {
    public static final String MOD_ID = "lumis";
    public static final Logger LOGGER = LoggerFactory.getLogger("Lumis");
    /**
     * Set when the game starts shutting down. AtmospheraLib frees every GL object then, but Minecraft keeps ticking
     * while it saves the world, so Lumis stops its GL work (binding the freed textures logged thousands of GL errors).
     */
    public static volatile boolean stopping;

    public Lumis() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("Lumis is client-only; inactive on this dedicated server.");
            return;
        }
        LumisConfig.register();
        LightFeatures.register();
        SunShadows.register();
        LightShafts.register();
        com.solarbiscuit.atmosphera.lumis.color.ColoredLight.register();
        com.solarbiscuit.atmosphera.lumis.weather.WetSheen.register();
        com.solarbiscuit.atmosphera.lumis.post.LensEffects.register();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, Lumis::onRenderTick);
        MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent e) -> stopping = true);
    }

    /** Once per rendered frame, before the world is drawn: the sun model every later consumer reads. */
    private static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || stopping) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        SunModel.update(mc.level, event.renderTickTime);
        LightmapGrader.checkDirty(mc);
        SunShadows.update(mc);
        LightShafts.update(mc);
        com.solarbiscuit.atmosphera.lumis.color.ColoredLight.update(mc);
        com.solarbiscuit.atmosphera.lumis.weather.WetSheen.update(mc);
    }
}
