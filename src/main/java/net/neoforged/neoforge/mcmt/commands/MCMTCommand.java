/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.mcmt.MCMT;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;
import net.neoforged.neoforge.mcmt.serdes.SerDesRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@code /mcmt} — inspect and retune multi-core tick processing without restarting the server.
 *
 * <p>Subcommands:
 *
 * <ul>
 * <li>{@code /mcmt stats} — what is switched on, and how much is in flight right now.
 * <li>{@code /mcmt perf} — recent tick durations.
 * <li>{@code /mcmt config} — list every setting; {@code /mcmt config <key>} reads one;
 * {@code /mcmt config <key> <value>} writes one, taking effect on the next tick.
 * <li>{@code /mcmt save} — persist the live settings to the config file.
 * <li>{@code /mcmt reload} — discard the live settings and re-read the config file.
 * <li>{@code /mcmt restart} — rebuild the worker pool, picking up a changed parallelism setting.
 * </ul>
 *
 * <p>Changes made through {@code config} are live but in-memory: they are lost on restart unless {@code save}
 * is used. That is deliberate — it makes it safe to flip a hook off while chasing a crash without permanently
 * editing the server's configuration.
 */
@ApiStatus.Internal
public final class MCMTCommand {
    private MCMTCommand() {}

    /**
     * One runtime-settable configuration entry.
     *
     * @param read  renders the current value
     * @param write parses and applies a new value, throwing {@link IllegalArgumentException} on bad input
     * @param hint  what values are accepted, shown in the usage message
     */
    private record Setting(Supplier<String> read, Consumer<String> write, String hint) {}

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    private static void bool(String key, BooleanSupplier read, Consumer<Boolean> write) {
        SETTINGS.put(key, new Setting(
                () -> Boolean.toString(read.getAsBoolean()),
                value -> write.accept(parseBoolean(value)),
                "true|false"));
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false, got '" + value + "'");
    }

    static {
        bool("disabled", () -> MCMTConfig.disabled, v -> MCMTConfig.disabled = v);
        bool("disableWorld", () -> MCMTConfig.disableWorld, v -> MCMTConfig.disableWorld = v);
        bool("disableEntity", () -> MCMTConfig.disableEntity, v -> MCMTConfig.disableEntity = v);
        bool("disableBlockEntity", () -> MCMTConfig.disableBlockEntity, v -> MCMTConfig.disableBlockEntity = v);
        bool("disableEnvironment", () -> MCMTConfig.disableEnvironment, v -> MCMTConfig.disableEnvironment = v);
        bool("disableChunkProvider", () -> MCMTConfig.disableChunkProvider, v -> MCMTConfig.disableChunkProvider = v);
        bool("chunkLockModded", () -> MCMTConfig.chunkLockModded, v -> MCMTConfig.chunkLockModded = v);
        bool("opsTracing", () -> MCMTConfig.opsTracing, v -> MCMTConfig.opsTracing = v);

        // paraMax and paraMaxMode only take effect on the next pool build, so nudge the user towards /mcmt restart.
        SETTINGS.put("paraMax", new Setting(
                () -> Integer.toString(MCMTConfig.paraMax),
                value -> {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("expected a number, got '" + value + "'");
                    }
                    if (parsed < 0 || parsed > 256) {
                        throw new IllegalArgumentException("expected 0-256, got " + parsed);
                    }
                    MCMTConfig.paraMax = parsed;
                },
                "0-256, needs /mcmt restart"));
        SETTINGS.put("paraMaxMode", new Setting(
                () -> MCMTConfig.paraMaxMode.name(),
                value -> {
                    try {
                        MCMTConfig.paraMaxMode = MCMTConfig.ParaMaxMode.valueOf(value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("expected one of "
                                + Arrays.toString(MCMTConfig.ParaMaxMode.values()) + ", got '" + value + "'");
                    }
                },
                "STANDARD|OVERRIDE|REDUCTION, needs /mcmt restart"));
    }

    private static final SuggestionProvider<CommandSourceStack> KEYS = (ctx, builder) -> SharedSuggestionProvider.suggest(SETTINGS.keySet(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("mcmt")
                .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("stats").executes(MCMTCommand::stats))
                .then(Commands.literal("perf").executes(MCMTCommand::perf))
                .then(Commands.literal("save").executes(MCMTCommand::save))
                .then(Commands.literal("reload").executes(MCMTCommand::reload))
                .then(Commands.literal("restart").executes(MCMTCommand::restart))
                .then(Commands.literal("config")
                        .executes(MCMTCommand::listConfig)
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(KEYS)
                                .executes(MCMTCommand::getConfig)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(MCMTCommand::setConfig)))));
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        line(source, "MCMT", MCMTConfig.disabled ? "disabled" : "enabled");
        line(source, "Pool", MCMTThreadPool.isStarted()
                ? MCMTThreadPool.getParallelism() + " target, " + MCMTThreadPool.getPoolSize() + " threads, "
                        + MCMTThreadPool.getQueuedTaskCount() + " queued"
                : "not started");
        line(source, "Parallel hooks", describeHooks());
        line(source, "In flight", MCMT.getRunningLevelTicks() + " level, "
                + MCMT.getRunningEntityTicks() + " entity, "
                + MCMT.getRunningBlockEntityTicks() + " block entity, "
                + MCMT.getRunningChunkTicks() + " chunk");
        line(source, "Dispatched", MCMT.getDispatchedLevelTicks() + " level, "
                + MCMT.getDispatchedEntityTicks() + " entity, "
                + MCMT.getDispatchedBlockEntityTicks() + " block entity, "
                + MCMT.getDispatchedChunkTicks() + " chunk ticks since startup");
        Set<Class<?>> demoted = SerDesRegistry.autoDemoted();
        if (!demoted.isEmpty()) {
            line(source, "Auto-demoted", demoted.size() + " class(es) chunk-locked after throwing");
            for (Class<?> type : demoted) {
                line(source, "  ", type.getName());
            }
        }
        if (MCMTConfig.opsTracing) {
            line(source, "Traced tasks", Integer.toString(MCMT.getCurrentTasks().size()));
        }
        return 1;
    }

    private static String describeHooks() {
        StringBuilder sb = new StringBuilder();
        appendHook(sb, "world", !MCMTConfig.disableWorld);
        appendHook(sb, "entity", !MCMTConfig.disableEntity);
        appendHook(sb, "blockEntity", !MCMTConfig.disableBlockEntity);
        appendHook(sb, "environment", !MCMTConfig.disableEnvironment);
        appendHook(sb, "chunkProvider", !MCMTConfig.disableChunkProvider);
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static void appendHook(StringBuilder sb, String name, boolean on) {
        if (on) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(name);
        }
    }

    private static int perf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int recorded = MCMT.getRecordedTickCount();
        if (recorded == 0) {
            source.sendSuccess(() -> Component.literal("No ticks recorded yet."), false);
            return 0;
        }
        line(source, "Ticks recorded", Integer.toString(recorded));
        line(source, "Mean", String.format(Locale.ROOT, "%.3f ms", MCMT.getMeanTickTimeMillis()));
        line(source, "Max", String.format(Locale.ROOT, "%.3f ms", MCMT.getMaxTickTimeMillis()));
        return 1;
    }

    private static int listConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SETTINGS.forEach((key, setting) -> line(source, key, setting.read().get()));
        return SETTINGS.size();
    }

    private static int getConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        Setting setting = SETTINGS.get(key);
        if (setting == null) {
            return unknownKey(source, key);
        }
        line(source, key, setting.read().get());
        return 1;
    }

    private static int setConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value").trim();
        Setting setting = SETTINGS.get(key);
        if (setting == null) {
            return unknownKey(source, key);
        }
        try {
            setting.write().accept(value);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(key + ": " + e.getMessage() + " (expected " + setting.hint() + ")"));
            return 0;
        }
        String applied = setting.read().get();
        source.sendSuccess(() -> Component.literal(key + " = " + applied + " (not saved; use /mcmt save to persist)"), true);
        return 1;
    }

    private static int unknownKey(CommandSourceStack source, String key) {
        source.sendFailure(Component.literal("Unknown setting '" + key + "'. Use /mcmt config to list them."));
        return 0;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        // Fold in whatever AutoFilter learnt this session, so a class that had to be demoted stays demoted
        // across a restart. Doing it here rather than at demotion time keeps MCMT from editing the owner's
        // config file without being asked.
        int learnt = SerDesRegistry.persistAutoDemotions();
        MCMTConfig.save();
        ctx.getSource().sendSuccess(
                () -> Component.literal("MCMT configuration written to disk"
                        + (learnt > 0 ? ", including " + learnt + " auto-demoted class(es)." : ".")),
                true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MCMTConfig.bake();
        ctx.getSource().sendSuccess(() -> Component.literal("MCMT configuration re-read from disk."), true);
        return 1;
    }

    private static int restart(CommandContext<CommandSourceStack> ctx) {
        MCMTThreadPool.restart();
        int workers = MCMTThreadPool.getParallelism();
        ctx.getSource().sendSuccess(() -> Component.literal("MCMT worker pool restarted with " + workers + " workers."), true);
        return 1;
    }

    private static void line(CommandSourceStack source, String label, String value) {
        source.sendSuccess(() -> Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }
}
