package com.solarbiscuit.atmosphera.stratos.server;

import com.solarbiscuit.atmosphera.lib.world.Weather;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The server's weather model (0.1.0 "Front"). Once a second for every level with a sky:
 * <ul>
 *   <li>vanilla's own timers say whether it rains or thunders and when that ends ({@code rainTime},
 *   {@code thunderTime}, {@code clearWeatherTime}); gameplay keeps following them;</li>
 *   <li>each spell gets a kind and an intensity from a hash of the world seed and the game time the spell started, so
 *   the same world repeats the same weather, and the next spell's kind is known in advance (the forecast);</li>
 *   <li>the state goes to the library's {@link Weather} service, which sends it to the clients in the level whenever
 *   it changes and to players as they arrive.</li>
 * </ul>
 * A {@code /stratos set} override holds a kind until the spell ends (tests, map makers).
 */
public final class WeatherModel {
    private static final Map<ServerLevel, Spell> SPELLS = new HashMap<>();

    /** The current spell of a level: when it started and whether it was raining. */
    private static final class Spell {
        long start;
        boolean raining;
        Weather.Type override;
        float overrideIntensity;
    }

    private WeatherModel() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && e.side.isServer() && e.level instanceof ServerLevel level
                    && level.getGameTime() % 20L == 0L) {
                tick(level);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                SPELLS.remove(level);
                Weather.withdraw(level.dimension());
            }
        });
    }

    /** Forces a kind for the rest of the current spell (null: back to the model). Server thread. */
    public static void override(ServerLevel level, Weather.Type type, float intensity) {
        Spell spell = SPELLS.computeIfAbsent(level, l -> new Spell());
        spell.override = type;
        spell.overrideIntensity = intensity;
        tick(level);
    }

    static void tick(ServerLevel level) {
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()
                || !StratosServerConfig.loaded() || !StratosServerConfig.model.get()) {
            if (Weather.published(level.dimension()) != null) {
                Weather.withdraw(level.dimension());
            }
            return;
        }
        if (!(level.getLevelData() instanceof ServerLevelData data)) {
            return;
        }
        long now = level.getGameTime();
        Spell spell = SPELLS.computeIfAbsent(level, l -> new Spell());
        boolean raining = data.isRaining();
        if (raining != spell.raining || spell.start == 0L) {
            spell.raining = raining;
            spell.start = now;
            // 0.2.1: only the end of the rain clears a held kind (a /stratos set right as rain began was lost).
            if (!raining) {
                spell.override = null;
            }
        }
        long seed = level.getSeed() ^ level.dimension().location().hashCode();
        Weather.Type type;
        float intensity;
        if (!raining) {
            type = Weather.Type.CLEAR;
            intensity = 0f;
        } else if (spell.override != null) {
            type = spell.override;
            intensity = spell.overrideIntensity;
        } else if (data.isThundering()) {
            type = Weather.Type.STORM;
            intensity = 0.9f + 0.1f * unit(mix(seed ^ spell.start * 0x9E3779B97F4A7C15L ^ 0x57L));
        } else {
            long h = mix(seed ^ spell.start * 0x9E3779B97F4A7C15L);
            type = kind(unit(h));
            intensity = intensityOf(type, unit(mix(h + 1)));
        }
        // The forecast: when this spell ends (vanilla's timers) and, for a coming rain, its kind already.
        long changeAt;
        Weather.Type next;
        float nextIntensity;
        if (raining) {
            changeAt = now + Math.max(1, data.getRainTime());
            next = Weather.Type.CLEAR;
            nextIntensity = 0f;
        } else {
            int wait = data.getClearWeatherTime() > 0 ? data.getClearWeatherTime() : data.getRainTime();
            changeAt = now + Math.max(1, wait);
            long h = mix(seed ^ changeAt * 0x9E3779B97F4A7C15L);
            next = kind(unit(h));
            nextIntensity = intensityOf(next, unit(mix(h + 1)));
        }
        float wind = switch (type) {
            case CLEAR -> 1f;
            case DRIZZLE -> 0.85f;
            case LIGHT_RAIN -> 0.95f;
            case RAIN -> 1.05f;
            case HEAVY_RAIN -> 1.2f;
            case STORM -> 1.35f;
        };
        float contrast = raining ? StratosServerConfig.contrast.get().floatValue() : 0f;
        Weather.publish(level, "Stratos", new Weather.State(type, intensity, wind, next, nextIntensity, changeAt,
                mix(seed ^ spell.start), contrast));
    }

    /** A rain kind from a uniform number, by the configured shares. */
    private static Weather.Type kind(float u) {
        float drizzle = StratosServerConfig.drizzle.get().floatValue();
        float light = StratosServerConfig.lightRain.get().floatValue();
        float heavy = StratosServerConfig.heavyRain.get().floatValue();
        if (u < drizzle) {
            return Weather.Type.DRIZZLE;
        }
        if (u < drizzle + light) {
            return Weather.Type.LIGHT_RAIN;
        }
        if (u > 1f - heavy) {
            return Weather.Type.HEAVY_RAIN;
        }
        return Weather.Type.RAIN;
    }

    /** Intensity within a kind's band (0.8 is ordinary rain, as the library's default gives it). */
    static float intensityOf(Weather.Type type, float u) {
        return switch (type) {
            case CLEAR -> 0f;
            case DRIZZLE -> 0.3f + 0.15f * u;
            case LIGHT_RAIN -> 0.5f + 0.15f * u;
            case RAIN -> 0.72f + 0.13f * u;
            case HEAVY_RAIN -> 0.9f + 0.1f * u;
            case STORM -> 0.9f + 0.1f * u;
        };
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static float unit(long h) {
        return (h >>> 40) / (float) (1L << 24);
    }
}
