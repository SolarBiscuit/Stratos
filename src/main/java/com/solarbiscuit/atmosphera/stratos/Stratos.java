package com.solarbiscuit.atmosphera.stratos;

import com.solarbiscuit.atmosphera.stratos.server.StratosCommand;
import com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig;
import com.solarbiscuit.atmosphera.stratos.server.WeatherModel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stratos: weather for the Atmosphera suite.
 * <ul>
 *   <li><b>Server</b> (0.1.0 "Front"): each rain spell gets a kind (drizzle, light rain, rain, heavy rain; storms in
 *   thunder) and an intensity, seeded so the same world repeats them, and a forecast: what comes next and when, from
 *   vanilla's own weather timers. It is stated to AtmospheraLib's Weather service, which sends it to the clients, so
 *   Atmosphera's clouds, Gale's wind, Torrente's rain rings and Stratos's rain all follow the same weather. The singleplayer
 *   world's integrated server runs the same code.</li>
 *   <li><b>Client</b> (0.2.0 "Fall"): a GPU rain renderer in place of vanilla's rain streaks.</li>
 * </ul>
 * Optional on both sides. Gameplay stays vanilla: crops, fire, mob spawning and "is it raining" follow vanilla's
 * weather; Stratos's kinds are what you see and feel (clouds, wind, rain).
 * <p>
 * Copyright (C) 2026 SolarBiscuit. All Rights Reserved (see LICENSE).
 */
@Mod(Stratos.MOD_ID)
public final class Stratos {
    public static final String MOD_ID = "stratos";
    public static final Logger LOGGER = LoggerFactory.getLogger("Stratos");

    public Stratos() {
        StratosServerConfig.register();
        WeatherModel.register();
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> StratosCommand.register(e.getDispatcher()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.solarbiscuit.atmosphera.stratos.client.StratosClient.init();
        } else {
            LOGGER.info("Stratos on a dedicated server: the weather model only (no rendering).");
        }
    }
}
