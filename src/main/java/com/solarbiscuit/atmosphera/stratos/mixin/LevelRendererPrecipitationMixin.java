package com.solarbiscuit.atmosphera.stratos.mixin;

import com.solarbiscuit.atmosphera.stratos.client.RainRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While Stratos draws the rain, vanilla's {@code renderSnowAndRain} skips its rain columns: the precipitation it reads
 * for each column comes back as none where it would be rain. Snow columns are drawn as before, and so is everything
 * outside this method (splash particles and the rain sound come from {@code tickRain}). Optional ({@code require = 0}):
 * if another mod replaces the method, vanilla's rain stays and Stratos's draws over it.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPrecipitationMixin {
    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"),
            require = 0)
    private Biome.Precipitation stratos$skipRain(Biome biome, BlockPos pos) {
        Biome.Precipitation precipitation = biome.getPrecipitationAt(pos);
        return precipitation == Biome.Precipitation.RAIN && RainRenderer.replacesVanilla()
                ? Biome.Precipitation.NONE : precipitation;
    }
}
