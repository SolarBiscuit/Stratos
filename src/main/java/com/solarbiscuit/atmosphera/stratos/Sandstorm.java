package com.solarbiscuit.atmosphera.stratos;

import com.solarbiscuit.atmosphera.lib.world.Weather;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Sandstorms (0.4.0): a rain spell over a dry sandy biome, where vanilla draws no rain, blows sand instead. Shared by
 * the client's sand streaks and the server's optional effects, so both agree where a storm is. Common code (no client
 * classes).
 */
public final class Sandstorm {
    private static final TagKey<Biome> IS_DESERT = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_desert"));
    private static final TagKey<Biome> IS_SANDY = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_sandy"));

    /** Sand colours, linear 0..1: desert sand and badlands' red sand. */
    public static final float[] DESERT = {0.85f, 0.76f, 0.6f};
    public static final float[] BADLANDS = {0.84f, 0.65f, 0.49f};

    private Sandstorm() {
    }

    /** Whether rain spells over this biome are sandstorms: deserts, sandy and badlands biomes, or any hot dry one. */
    public static boolean sandy(Holder<Biome> biome, BlockPos pos) {
        if (biome.is(IS_DESERT) || biome.is(IS_SANDY) || biome.is(BiomeTags.IS_BADLANDS)) {
            return true;
        }
        Biome value = biome.value();
        return value.getPrecipitationAt(pos) == Biome.Precipitation.NONE && value.getBaseTemperature() > 1.0f;
    }

    /** The sand colour for a biome (badlands red sand, else desert sand). */
    public static float[] colour(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_BADLANDS) ? BADLANDS : DESERT;
    }

    /** How strong a sandstorm the weather kind makes, 0..1 (drizzle a dust haze, a storm the strongest). */
    public static float strength(Weather.Type type) {
        return switch (type) {
            case CLEAR -> 0f;
            case DRIZZLE -> 0.3f;
            case LIGHT_RAIN -> 0.5f;
            case RAIN -> 0.75f;
            case HEAVY_RAIN -> 0.95f;
            case STORM -> 1f;
        };
    }
}
