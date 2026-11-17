package com.solarbiscuit.atmosphera.stratos.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.solarbiscuit.atmosphera.lib.world.Weather;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * {@code /stratos} (server, operators): {@code status} (the level's weather and forecast), {@code set <kind>
 * [intensity]} (holds a kind for the rest of the rain spell; start rain first with {@code /weather rain}), {@code auto}
 * (back to the model) and {@code reload} (re-reads {@code stratos-server.toml} and sends the weather again; 0.4.4: the
 * file is read here, Forge's file watcher was not reliable). The server-side reload the roadmap asked for: {@code /atmosphera reload}
 * covers client settings only.
 */
public final class StratosCommand {
    private StratosCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("stratos").requires(s -> s.hasPermission(2));
        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(Commands.literal("auto").executes(ctx -> {
            WeatherModel.override(ctx.getSource().getLevel(), null, 0f);
            ctx.getSource().sendSuccess(() -> Component.literal("Stratos: the weather follows the model again."), true);
            return 1;
        }));
        root.then(Commands.literal("reload").executes(ctx -> {
            // 0.4.4 (B1): the file is re-read here; the 2026-11-16 server test saw Forge's watcher miss a saved edit.
            boolean read = StratosServerConfig.reloadFromDisk();
            WeatherModel.tick(ctx.getSource().getLevel());
            ctx.getSource().sendSuccess(() -> Component.literal(read
                    ? "Stratos: stratos-server.toml re-read and the weather sent again."
                    : "Stratos: could not re-read stratos-server.toml (see the log); current settings applied and the weather sent again."), true);
            return read ? 1 : 0;
        }));
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        for (Weather.Type type : Weather.Type.values()) {
            if (type == Weather.Type.CLEAR) {
                continue;
            }
            String name = type.name().toLowerCase(Locale.ROOT);
            set.then(Commands.literal(name)
                    .executes(ctx -> set(ctx.getSource(), type, WeatherModel.intensityOf(type, 0.5f)))
                    .then(Commands.argument("intensity", FloatArgumentType.floatArg(0f, 1f))
                            .executes(ctx -> set(ctx.getSource(), type, FloatArgumentType.getFloat(ctx, "intensity")))));
        }
        root.then(set);
        // 0.4.1: /stratos forecast <seconds> [storm]: clear now, rain due in that many seconds (a thunderstorm with
        // "storm"), through vanilla's own timers, so the forecast the clients see is the real one (tests, map makers).
        root.then(Commands.literal("forecast").then(Commands.argument("seconds",
                com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 3600))
                .executes(ctx -> forecast(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seconds"), false))
                .then(Commands.literal("storm").executes(ctx -> forecast(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seconds"), true)))));
        dispatcher.register(root);
    }

    private static int forecast(CommandSourceStack source, int seconds, boolean storm) {
        ServerLevel level = source.getLevel();
        if (!(level.getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData data)) {
            return 0;
        }
        // 0.4.2: a dimension that shares the overworld's weather ignores these setters, so say so instead of a success.
        if (data instanceof net.minecraft.world.level.storage.DerivedLevelData) {
            source.sendFailure(Component.literal("Stratos: this dimension shares the overworld's weather; "
                    + "run /stratos forecast in the overworld."));
            return 0;
        }
        int ticks = seconds * 20;
        data.setClearWeatherTime(0);
        data.setRaining(false);
        data.setRainTime(ticks);
        data.setThundering(false);
        // Thunder turns on 5 s before the rain (so the rain starts as a storm), or long after it.
        data.setThunderTime(storm ? Math.max(1, ticks - 100) : ticks + 24000);
        WeatherModel.tick(level);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Stratos: clear now; %s in %d s.",
                storm ? "a thunderstorm" : "rain", seconds)), true);
        return 1;
    }

    private static int set(CommandSourceStack source, Weather.Type type, float intensity) {
        ServerLevel level = source.getLevel();
        if (!level.isRaining()) {
            source.sendFailure(Component.literal("Stratos: it is not raining here; start rain with /weather rain first."));
            return 0;
        }
        WeatherModel.override(level, type, intensity);
        String text = String.format(Locale.ROOT, "Stratos: %s at %.2f for the rest of this spell.",
                type.name().toLowerCase(Locale.ROOT), intensity);
        source.sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Weather.State state = Weather.published(level.dimension());
        if (state == null) {
            source.sendSuccess(() -> Component.literal("Stratos: no weather model in this level (no sky, or model off)."), false);
            return 1;
        }
        long in = state.changeAt() < 0 ? -1 : (state.changeAt() - level.getGameTime()) / 20;
        String next = in < 0 ? "next change unknown"
                : String.format(Locale.ROOT, "next %s (%.2f) in %d s",
                        state.next().name().toLowerCase(Locale.ROOT), state.nextIntensity(), in);
        // 0.4.1: thunder due within the current spell.
        if (state.stormAt() >= 0) {
            next += String.format(Locale.ROOT, "; thunder in %d s", (state.stormAt() - level.getGameTime()) / 20);
        }
        String text = String.format(Locale.ROOT, "Stratos: %s, intensity %.2f, wind x%.2f; %s",
                state.type().name().toLowerCase(Locale.ROOT), state.intensity(), state.windFactor(), next);
        source.sendSuccess(() -> Component.literal(text), false);
        // 0.4.0: whether the spell is a sandstorm where the command's source stands.
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        if (state.type() != Weather.Type.CLEAR
                && com.solarbiscuit.atmosphera.stratos.Sandstorm.sandy(level.getBiome(pos), pos)) {
            String sand = String.format(Locale.ROOT, "Stratos: a sandstorm here (strength %.2f)",
                    com.solarbiscuit.atmosphera.stratos.Sandstorm.strength(state.type()));
            source.sendSuccess(() -> Component.literal(sand), false);
        }
        return 1;
    }
}
