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
        config.section("rain", "Rain", "Rain: Stratos draws the rain on the GPU in place of vanilla's streaks (snow stays vanilla's).");
        rain = config.featureSwitch("enabled", true, "Stratos rain",
                "Rain falls as fine streaks drawn on the graphics card, as heavy as the weather is (drizzle to "
                        + "downpour), slanted by the wind and stopped by roofs. Off under a shaderpack (it draws its own).");
        density = config.percent("density", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Rain density",
                "How many streaks fall at a given intensity (100% for the look; lower costs less).");
        opacity = config.percent("opacity", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Rain opacity", "How visible each streak is.");
        config.register();
        // 0.3.1: the weather model's server settings, shown to the host of a singleplayer or LAN world.
        AtmosConfig server = AtmosConfig.serverView(Stratos.MOD_ID, "Stratos");
        server.wrapToggle(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.model, "weather.model",
                "Weather model (server)", "Decide a kind and intensity for each rain spell in this world. Off: vanilla's "
                        + "rain and thunder levels.");
        server.wrapPercent(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.drizzle, 0.0, 1.0,
                "weather.drizzle_share", "Drizzle share", "Share of rain spells that are drizzle.");
        server.wrapPercent(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.lightRain, 0.0, 1.0,
                "weather.light_rain_share", "Light rain share", "Share of rain spells that are light rain.");
        server.wrapPercent(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.heavyRain, 0.0, 1.0,
                "weather.heavy_rain_share", "Heavy rain share", "Share of rain spells that are heavy rain (thunder is always a storm).");
        server.wrapPercent(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.contrast, 0.0, 1.0,
                "weather.regional_contrast", "Regional contrast", "How patchy the rain is: 0% the same everywhere, 100% "
                        + "heavy and light cells side by side.");
        server.wrapToggle(com.solarbiscuit.atmosphera.stratos.server.StratosServerConfig.sandstormEffects,
                "weather.sandstorm_effects", "Sandstorm effects (server)", "In a sandstorm (rain over a dry sandy biome), "
                        + "walking into the wind is up to 12% slower and hostile mobs nearby lose up to 40% of their follow "
                        + "range. Off: sandstorms are only seen.");
        RainRenderer.register();
    }
}
