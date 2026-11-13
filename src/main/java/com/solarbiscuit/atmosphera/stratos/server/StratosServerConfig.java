package com.solarbiscuit.atmosphera.stratos.server;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * {@code config/Atmosphera/stratos-server.toml}: the weather model's settings. Common config (Forge's COMMON type), so
 * the integrated server of a singleplayer world reads it too. Nothing here touches client classes.
 */
public final class StratosServerConfig {
    public static ForgeConfigSpec.BooleanValue model;
    public static ForgeConfigSpec.DoubleValue drizzle;
    public static ForgeConfigSpec.DoubleValue lightRain;
    public static ForgeConfigSpec.DoubleValue heavyRain;
    public static ForgeConfigSpec.DoubleValue contrast;
    private static ForgeConfigSpec spec;

    private StratosServerConfig() {
    }

    public static void register() {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Stratos's weather model (server side; in singleplayer, the world's own server).").push("weather");
        model = b.comment("Decide a kind and intensity for each rain spell and send them to clients. Off: clients "
                + "fall back to vanilla's rain and thunder levels.").define("model", true);
        drizzle = b.comment("Share of rain spells that are drizzle (the rest is split as below; rain takes what is left).")
                .defineInRange("drizzle_share", 0.2, 0.0, 1.0);
        lightRain = b.comment("Share of rain spells that are light rain.").defineInRange("light_rain_share", 0.3, 0.0, 1.0);
        heavyRain = b.comment("Share of rain spells that are heavy rain. Thunder is always a storm.")
                .defineInRange("heavy_rain_share", 0.15, 0.0, 1.0);
        contrast = b.comment("How patchy the rain is: 0 the same everywhere, 1 heavy and light cells side by side "
                + "(about a kilometre across, drifting with the spell).").defineInRange("regional_contrast", 0.35, 0.0, 1.0);
        b.pop();
        spec = b.build();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec, "Atmosphera/stratos-server.toml");
    }

    public static boolean loaded() {
        return spec != null && spec.isLoaded();
    }
}
