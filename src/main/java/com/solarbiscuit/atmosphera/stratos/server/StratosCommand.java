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
 * (back to the model) and {@code reload} (re-reads {@code stratos-server.toml} and sends the weather again). The
 * server-side reload the roadmap asked for: {@code /atmosphera reload} covers client settings only.
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
            WeatherModel.tick(ctx.getSource().getLevel());
            ctx.getSource().sendSuccess(() -> Component.literal("Stratos: settings read and the weather sent again."), true);
            return 1;
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
        dispatcher.register(root);
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
        String text = String.format(Locale.ROOT, "Stratos: %s, intensity %.2f, wind x%.2f; next %s (%.2f) in %d s",
                state.type().name().toLowerCase(Locale.ROOT), state.intensity(), state.windFactor(),
                state.next().name().toLowerCase(Locale.ROOT), state.nextIntensity(), in);
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
