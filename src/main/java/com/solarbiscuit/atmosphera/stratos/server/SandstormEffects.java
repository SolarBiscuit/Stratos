package com.solarbiscuit.atmosphera.stratos.server;

import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.lib.world.Weather;
import com.solarbiscuit.atmosphera.stratos.Sandstorm;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Sandstorm effects (0.4.0, server setting {@code weather.sandstorm_effects}, on by default): while a rain spell is a
 * sandstorm where a player stands (a dry sandy biome under open sky, {@link Sandstorm}), walking into the wind slows
 * them a little (up to 12% at a full storm, a transient movement-speed modifier), and hostile mobs within 64 blocks of
 * them lose part of their follow range (up to 40%), so they find players later in the blowing sand. No damage, no
 * blindness; nothing is saved (transient modifiers), and everything comes off when the storm ends, the player leaves
 * it, or the setting is turned off. Checked every 10 ticks.
 * <p>
 * Server thread only: the static working sets below are reused by each level's tick in turn.
 */
public final class SandstormEffects {
    private static final UUID SLOW = UUID.fromString("6f3c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f");
    private static final UUID SENSE = UUID.fromString("7a4d2e3f-9b5c-4d6e-8f70-1b2c3d4e5f60");
    private static final String NAME = "Stratos sandstorm";
    /**
     * Each player's position at the last check, per level (0.4.2). It was one map for all levels, and each level's
     * tick pruned it to its own players: the Nether and End, which tick on the same game tick, erased the overworld's
     * players, so there was never a last position and the slowdown never applied.
     */
    private static final Map<ResourceKey<Level>, Map<UUID, Vec3>> LAST = new HashMap<>();
    /** The slowdown changes in steps of this much (0.4.2), so the modifier (and its packet) is not re-sent per check. */
    private static final double SLOW_STEP = 0.02;
    private static final Set<LivingEntity> KEEP = new HashSet<>();
    private static final Set<UUID> SEEN = new HashSet<>();
    private static final Map<ServerLevel, Set<LivingEntity>> AFFECTED = new WeakHashMap<>();
    private static final WindVector WIND = new WindVector();
    private static final Map<ServerLevel, Boolean> LOGGED = new WeakHashMap<>();

    private SandstormEffects() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && e.side.isServer() && e.level instanceof ServerLevel level
                    && level.getGameTime() % 10L == 0L) {
                tick(level);
            }
        });
        // 0.4.2: nothing carries over to the next world started in the same game (singleplayer).
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> LAST.clear());
    }

    private static void tick(ServerLevel level) {
        boolean on = StratosServerConfig.loaded() && StratosServerConfig.sandstormEffects.get();
        float strength = 0f;
        if (on && level.isRaining() && level.dimensionType().hasSkyLight()) {
            Weather.State state = Weather.published(level.dimension());
            strength = (state != null ? Sandstorm.strength(state.type()) : 0.75f) * Math.min(1f, level.getRainLevel(1f));
        }
        Map<UUID, Vec3> lastPositions = LAST.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Set<LivingEntity> keep = KEEP;
        Set<UUID> seen = SEEN;
        keep.clear();
        seen.clear();
        boolean any = false;
        for (ServerPlayer player : level.players()) {
            seen.add(player.getUUID());
            BlockPos pos = player.blockPosition();
            boolean storm = strength > 0.01f && !player.isSpectator() && level.canSeeSky(pos.above())
                    && Sandstorm.sandy(level.getBiome(pos), pos);
            Vec3 now = player.position();
            Vec3 last = lastPositions.put(player.getUUID(), now);
            double slow = 0.0;
            if (storm) {
                any = true;
                Wind.sample(level, now.x, now.y, now.z, 1f, WIND);
                if (last != null) {
                    double dx = now.x - last.x;
                    double dz = now.z - last.z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.05) {
                        // Into the wind: the walk's direction against the direction the wind blows towards.
                        double into = -(dx * WIND.dirX + dz * WIND.dirZ) / len;
                        slow = -0.12 * strength * Math.max(0.0, Math.min(1.0, into)) * (0.6 + 0.4 * WIND.strength);
                        slow = Math.round(slow / SLOW_STEP) * SLOW_STEP;
                    }
                }
                for (Monster monster : level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(64.0))) {
                    // 0.4.2: a mob near two players is set once.
                    if (keep.add(monster)) {
                        apply(monster, Attributes.FOLLOW_RANGE, SENSE, -0.4 * strength);
                    }
                }
            }
            apply(player, Attributes.MOVEMENT_SPEED, SLOW, slow);
        }
        lastPositions.keySet().retainAll(seen);
        seen.clear();
        Set<LivingEntity> previous = AFFECTED.computeIfAbsent(level, l -> Collections.newSetFromMap(new WeakHashMap<>()));
        for (LivingEntity entity : previous) {
            if (!keep.contains(entity)) {
                apply(entity, Attributes.FOLLOW_RANGE, SENSE, 0.0);
            }
        }
        previous.clear();
        previous.addAll(keep);
        keep.clear();
        if (any != LOGGED.getOrDefault(level, false)) {
            LOGGED.put(level, any);
            Stratos.LOGGER.info("[sandstorm] {}: effects {} (strength {})", level.dimension().location(), any ? "on" : "off",
                    String.format(java.util.Locale.ROOT, "%.2f", strength));
        }
    }

    /** Sets (or removes, for 0) this mod's transient modifier on an attribute, touching it only when it changes. */
    private static void apply(LivingEntity entity, Attribute attribute, UUID id, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier current = instance.getModifier(id);
        if (Math.abs(amount) < 1e-3) {
            if (current != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (current != null && Math.abs(current.getAmount() - amount) < 1e-3) {
            return;
        }
        if (current != null) {
            instance.removeModifier(id);
        }
        instance.addTransientModifier(new AttributeModifier(id, NAME, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }
}
