package com.solarbiscuit.atmosphera.stratos.server;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

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
    public static ForgeConfigSpec.BooleanValue sandstormEffects;
    private static ForgeConfigSpec spec;
    /** 0.4.4: kept from Forge's load event, so {@code /stratos reload} can re-read the file itself. */
    private static volatile ModConfig config;

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
        sandstormEffects = b.comment("0.4.0: in a sandstorm (a rain spell over a dry sandy biome), walking into the wind is "
                + "up to 12% slower and hostile mobs within 64 blocks of the player lose up to 40% of their follow range. "
                + "Off: sandstorms are only seen.").define("sandstorm_effects", true);
        b.pop();
        spec = b.build();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec,
                com.solarbiscuit.atmosphera.lib.options.AtmosPaths.relative("stratos-server.toml"));
        FMLJavaModLoadingContext.get().getModEventBus().addListener((ModConfigEvent.Loading e) -> {
            if (e.getConfig().getSpec() == spec) {
                config = e.getConfig();
            }
        });
    }

    /**
     * 0.4.4 (B1): re-reads stratos-server.toml from disk now. Forge's file watcher did not apply a saved edit in the
     * 2026-11-16 server test (no reload logged), so {@code /stratos reload} no longer relies on it. Values out of range
     * are corrected and written back, as Forge does. Returns false when the file is not loaded or could not be read.
     */
    public static boolean reloadFromDisk() {
        ModConfig c = config;
        if (c == null || !(c.getConfigData() instanceof CommentedFileConfig file)) {
            return false;
        }
        try {
            file.load();
            if (spec.correct(file) > 0) {
                file.save();
            }
            spec.afterReload();
            return true;
        } catch (RuntimeException e) {
            com.solarbiscuit.atmosphera.stratos.Stratos.LOGGER.warn("Could not reload stratos-server.toml: {}", e.toString());
            return false;
        }
    }

    public static boolean loaded() {
        return spec != null && spec.isLoaded();
    }
}
