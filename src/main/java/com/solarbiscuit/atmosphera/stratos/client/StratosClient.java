package com.solarbiscuit.atmosphera.stratos.client;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;
import com.solarbiscuit.atmosphera.stratos.Stratos;

/**
 * Stratos's client part: the "Stratos" settings page ({@code config/Atmosphera/stratos-client.toml}) and the rain
 * renderer. Never loaded on a dedicated server.
 */
public final class StratosClient {
    public static AtmosConfig.FeatureSwitch rain;
    public static AtmosOption<Double> density;
    public static AtmosOption<Double> opacity;

    private StratosClient() {
    }

    public static void init() {
        AtmosConfig config = AtmosConfig.create(Stratos.MOD_ID, "Stratos");
        config.section("rain", "Rain: Stratos draws the rain on the GPU in place of vanilla's streaks (snow stays vanilla's).");
        rain = config.featureSwitch("enabled", true, "Stratos rain",
                "Rain falls as fine streaks drawn on the graphics card, as heavy as the weather is (drizzle to "
                        + "downpour), slanted by the wind and stopped by roofs. Off under a shaderpack (it draws its own).");
        density = config.percent("density", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Rain density",
                "How many streaks fall at a given intensity (100% for the look; lower costs less).");
        opacity = config.percent("opacity", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Rain opacity", "How visible each streak is.");
        config.register();
        RainRenderer.register();
    }
}
