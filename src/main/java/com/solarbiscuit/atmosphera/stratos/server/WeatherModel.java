package com.solarbiscuit.atmosphera.stratos.server;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.mix;
import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.unit;

import com.solarbiscuit.atmosphera.lib.world.Weather;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

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
    /** 0.4.2: the name of the per-level saved spell ({@code data/stratos_weather.dat} in the level's folder). */
    private static final String DATA = "stratos_weather";

    /**
     * The current spell of a level: when it started and whether it was raining. Saved with the level (0.4.2): before,
     * it lived only in memory, so a server restart in the middle of a spell gave it a new start and a new key, and the
     * spell changed kind, intensity and rain cells.
     */
    private static final class Spell extends SavedData {
        long start;
        /**
         * 0.4.1: the hash key of the spell's kind: the forecast's change time when rain came on time (so the kind
         * forecast is the kind that falls; it hashed the change time while the spell hashed the tick rain was seen),
         * else the start.
         */
        long key;
        /** The change time last forecast while clear. */
        long forecastAt = -1L;
        boolean raining;
        Weather.Type override;
        float overrideIntensity;

        static Spell load(CompoundTag tag) {
            Spell spell = new Spell();
            spell.start = tag.getLong("start");
            spell.key = tag.getLong("key");
            spell.forecastAt = tag.contains("forecastAt") ? tag.getLong("forecastAt") : -1L;
            spell.raining = tag.getBoolean("raining");
            if (tag.contains("override")) {
                try {
                    spell.override = Weather.Type.valueOf(tag.getString("override"));
                    spell.overrideIntensity = tag.getFloat("overrideIntensity");
                } catch (IllegalArgumentException unknown) {
                    spell.override = null;
                }
            }
            return spell;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLong("start", start);
            tag.putLong("key", key);
            tag.putLong("forecastAt", forecastAt);
            tag.putBoolean("raining", raining);
            if (override != null) {
                tag.putString("override", override.name());
                tag.putFloat("overrideIntensity", overrideIntensity);
            }
            return tag;
        }
    }

    private static Spell spell(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Spell::load, Spell::new, DATA);
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
                // The spell is saved data: it goes with the level. Nobody is left in it, so no clients to tell.
                Weather.withdraw(level.dimension());
            }
        });
    }

    /** Forces a kind for the rest of the current spell (null: back to the model). Server thread. */
    public static void override(ServerLevel level, Weather.Type type, float intensity) {
        Spell spell = spell(level);
        spell.override = type;
        spell.overrideIntensity = intensity;
        spell.setDirty();
        tick(level);
    }

    static void tick(ServerLevel level) {
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()
                || !StratosServerConfig.loaded() || !StratosServerConfig.model.get()) {
            if (Weather.published(level.dimension()) != null) {
                // 0.4.2 (lib 0.10.3): the clients are told too, instead of keeping the last weather.
                Weather.withdraw(level);
            }
            return;
        }
        if (!(level.getLevelData() instanceof ServerLevelData data)) {
            return;
        }
        long now = level.getGameTime();
        Spell spell = spell(level);
        boolean raining = data.isRaining();
        if (raining != spell.raining || spell.start == 0L) {
            spell.raining = raining;
            spell.start = now;
            spell.key = raining && spell.forecastAt >= 0 && Math.abs(now - spell.forecastAt) <= 40L ? spell.forecastAt : now;
            // 0.2.1: only the end of the rain clears a held kind (a /stratos set right as rain began was lost).
            if (!raining) {
                spell.override = null;
            }
            spell.setDirty();
        }
        // 0.4.2: a dimension that shares the overworld's weather (DerivedLevelData: vanilla's and most mods' extra sky
        // dimensions) hashes as the overworld, so it gets the same kind for the same spell.
        ResourceKey<Level> owner = data instanceof DerivedLevelData ? Level.OVERWORLD : level.dimension();
        long seed = level.getSeed() ^ owner.location().hashCode();
        // 0.4.2: with doWeatherCycle off vanilla's timers stand still, so "now + timer" grew every second: the state
        // was re-sent to every player each second and the forecast never came. Nothing changes then: no forecast.
        boolean cycle = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
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
            intensity = stormIntensity(seed, spell.key);
        } else {
            long h = mix(seed ^ spell.key * 0x9E3779B97F4A7C15L);
            type = kind(unit(h));
            intensity = intensityOf(type, unit(mix(h + 1)));
        }
        // The forecast: when this spell ends (vanilla's timers) and, for a coming rain, its kind already.
        long changeAt;
        Weather.Type next;
        float nextIntensity;
        long stormAt = -1L;
        int thunderTime = data.getThunderTime();
        boolean thundering = data.isThundering();
        if (!cycle) {
            changeAt = -1L;
            next = Weather.Type.CLEAR;
            nextIntensity = 0f;
        } else if (raining) {
            changeAt = now + Math.max(1, data.getRainTime());
            next = Weather.Type.CLEAR;
            nextIntensity = 0f;
            // 0.4.1: thunder due within this spell (vanilla's thunder timer runs out before the rain's).
            if (!thundering && thunderTime > 0 && thunderTime < data.getRainTime()) {
                stormAt = now + thunderTime;
            }
        } else if (data.getClearWeatherTime() > 0) {
            // 0.4.1: a /weather clear hold: vanilla draws new timers when it ends, so the next rain is not known.
            changeAt = -1L;
            next = Weather.Type.CLEAR;
            nextIntensity = 0f;
        } else {
            int wait = Math.max(1, data.getRainTime());
            changeAt = now + wait;
            if (spell.forecastAt != changeAt) {
                spell.forecastAt = changeAt;
                spell.setDirty();
            }
            if (stormCertain(wait, thunderTime, thundering)) {
                next = Weather.Type.STORM;
                nextIntensity = stormIntensity(seed, changeAt);
            } else {
                long h = mix(seed ^ changeAt * 0x9E3779B97F4A7C15L);
                next = kind(unit(h));
                nextIntensity = intensityOf(next, unit(mix(h + 1)));
            }
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
                mix(seed ^ spell.start), contrast, stormAt));
    }

    /**
     * 0.4.1: whether the rain due in {@code wait} ticks certainly starts as a thunderstorm, from vanilla's thunder
     * timer: it keeps its state until the timer runs out, then holds the new state at least 3600 ticks (thunder) or
     * 12000 (no thunder). Unknown cases are not storms (the hashed kind stands).
     */
    static boolean stormCertain(int wait, int thunderTime, boolean thundering) {
        if (thunderTime <= 0) {
            return false;
        }
        if (thunderTime > wait) {
            return thundering;
        }
        // The timer runs out first and flips the state.
        return !thundering && wait - thunderTime < 3600;
    }

    private static float stormIntensity(long seed, long key) {
        return 0.9f + 0.1f * unit(mix(seed ^ key * 0x9E3779B97F4A7C15L ^ 0x57L));
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
}
