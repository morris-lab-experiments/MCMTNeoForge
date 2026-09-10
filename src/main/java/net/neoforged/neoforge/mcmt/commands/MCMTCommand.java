/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.Vec3;
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
                "0-256 (below 4 is rounded up), needs /mcmt restart"));
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
        SETTINGS.put("poolMaxThreads", new Setting(
                () -> Integer.toString(MCMTConfig.poolMaxThreads),
                value -> {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("expected a number, got '" + value + "'");
                    }
                    if (parsed < 0 || parsed > 1024) {
                        throw new IllegalArgumentException("expected 0-1024, got " + parsed);
                    }
                    MCMTConfig.poolMaxThreads = parsed;
                },
                "0-1024 (0 = same as the parallelism target), needs /mcmt restart"));
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
                .then(Commands.literal("testplayer")
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawnTestPlayers(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> spawnTestPlayers(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("remove").executes(MCMTCommand::removeTestPlayers))
                        .then(Commands.literal("open")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(MCMTCommand::testPlayerOpen))))
                        .then(Commands.literal("click")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(-999))
                                                .then(Commands.argument("button", IntegerArgumentType.integer(0, 8))
                                                        .then(Commands.argument("type", StringArgumentType.word())
                                                                .executes(MCMTCommand::testPlayerClick))))))
                        .then(Commands.literal("close")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(MCMTCommand::testPlayerClose)))
                        .then(Commands.literal("use")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> testPlayerUse(ctx, Direction.UP)))))
                        .then(Commands.literal("drop")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(ctx -> testPlayerDrop(ctx, false))
                                        .then(Commands.literal("all").executes(ctx -> testPlayerDrop(ctx, true)))))
                        .then(Commands.literal("moveto")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(MCMTCommand::testPlayerMoveTo))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(MCMTCommand::testPlayerReset))))
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
                ? MCMTThreadPool.getParallelism() + " target, " + MCMTThreadPool.getPoolSize() + "/"
                        + MCMTThreadPool.getMaxPoolSize() + " threads, " + MCMTThreadPool.getQueuedTaskCount() + " queued"
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

    /**
     * Test players spawned by {@code /mcmt testplayer spawn}, so {@code remove} can take them away again.
     * Only ever touched from the command thread, which is the server thread.
     */
    private static final List<ServerPlayer> TEST_PLAYERS = new ArrayList<>();

    /**
     * Puts one or more headless players into the world, so player-gated vanilla code actually runs.
     *
     * <p>A surprising amount of Minecraft does nothing at all when nobody is connected, and the divergence
     * harnesses connect nobody. {@code BaseSpawner.serverTick} returns immediately unless
     * {@code Level.hasNearbyAlivePlayer} finds someone inside {@code RequiredPlayerRange}; natural mob
     * spawning is driven off player positions; a villager only becomes willing to trade when a player opens
     * the menu. Every one of those is a tick path MCMT parallelises and no scenario has ever exercised.
     *
     * <p>The player is real — a {@link ServerPlayer} placed through {@code PlayerList.placeNewPlayer}, so it
     * lands in {@code level.players()} where {@code hasNearbyAlivePlayer} looks. What is fake is the socket:
     * the connection sits on an in-memory Netty {@link EmbeddedChannel}, the same arrangement
     * {@code GameTestHelper.makeMockServerPlayerInLevel} uses, so no network stack is involved.
     *
     * <p>An {@code EmbeddedChannel} keeps everything written to it in an outbound buffer forever, and the
     * server writes chunk and entity-tracking packets to this player every tick. Over a sprint of tens of
     * thousands of ticks that is an unbounded leak that would look like a scenario fault rather than a
     * harness one, so a discarding handler is installed at the head of the pipeline: outbound messages are
     * released and their promises completed, and nothing accumulates.
     *
     * <p>This is a diagnostic for the test harnesses, not a gameplay feature. It requires the same admin
     * permission as the rest of {@code /mcmt}.
     */
    private static int spawnTestPlayers(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        net.minecraft.world.phys.Vec3 at = source.getPosition();

        int spawned = 0;

        for (int i = 0; i < count; i++) {
            GameProfile profile = new GameProfile(UUID.randomUUID(), "mcmt-test-" + TEST_PLAYERS.size());
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation()) {
                @Override
                public boolean isSpectator() {
                    // hasNearbyAlivePlayer filters spectators out, so a spectating test player would be
                    // invisible to exactly the code this exists to reach.
                    return false;
                }

                @Override
                public boolean isCreative() {
                    return true;
                }
            };

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            channel.pipeline().addFirst("mcmt-discard-outbound", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext handlerCtx, Object msg, ChannelPromise promise) {
                    // Outbound handlers run tail to head, so sitting at the head means every packet is
                    // dropped here rather than piling up in the channel's outbound queue.
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            });

            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.teleportTo(level, at.x, at.y, at.z, player.getYRot(), player.getXRot());
            TEST_PLAYERS.add(player);
            spawned++;
        }

        int total = spawned;
        source.sendSuccess(
                () -> Component.literal(
                        "Spawned " + total + " headless test player(s); " + TEST_PLAYERS.size() + " now present. "
                                + "Spawners, natural spawning and trading are live in their vicinity."),
                true);
        return total;
    }

    /**
     * Resolves a test player by its index in {@link #TEST_PLAYERS}, or reports why it could not.
     *
     * <p>Returns {@code null} rather than throwing, so every caller reports a failure the harness can see. A
     * scripted scenario that silently interacted with nobody would pass while proving nothing, which is the
     * failure mode this whole command set exists to avoid.
     */
    private static ServerPlayer testPlayer(CommandContext<CommandSourceStack> ctx) {
        int index = IntegerArgumentType.getInteger(ctx, "index");

        if (index >= TEST_PLAYERS.size()) {
            ctx.getSource()
                    .sendFailure(Component.literal("No test player at index " + index + " (" + TEST_PLAYERS.size()
                            + " present). Use /mcmt testplayer spawn first."));
            return null;
        }

        return TEST_PLAYERS.get(index);
    }

    /**
     * Reports an interaction's outcome, loudly when it failed.
     *
     * <p>A rejected interaction returns 0, so a driving script sees a non-successful command result instead of
     * carrying on as though the click had landed. An interaction the server quietly ignored leaves every total
     * unchanged, and a conservation check would then read green.
     */
    private static int report(CommandSourceStack source, TestPlayerActions.Outcome outcome) {
        if (outcome.ok()) {
            source.sendSuccess(() -> Component.literal(outcome.detail()), true);
            return 1;
        }

        source.sendFailure(Component.literal("interaction rejected: " + outcome.detail()));
        return 0;
    }

    private static int testPlayerOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = testPlayer(ctx);

        if (player == null) {
            return 0;
        }

        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return report(ctx.getSource(), TestPlayerActions.openContainer(player, pos));
    }

    private static int testPlayerClick(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = testPlayer(ctx);

        if (player == null) {
            return 0;
        }

        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        int button = IntegerArgumentType.getInteger(ctx, "button");
        ClickType type;

        try {
            type = ClickType.valueOf(StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            ctx.getSource()
                    .sendFailure(Component.literal(
                            "unknown click type; expected one of PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL"));
            return 0;
        }

        return report(ctx.getSource(), TestPlayerActions.containerClick(player, slot, button, type));
    }

    private static int testPlayerClose(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = testPlayer(ctx);
        return player == null ? 0 : report(ctx.getSource(), TestPlayerActions.closeContainer(player));
    }

    private static int testPlayerUse(CommandContext<CommandSourceStack> ctx, Direction face) throws CommandSyntaxException {
        ServerPlayer player = testPlayer(ctx);

        if (player == null) {
            return 0;
        }

        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return report(ctx.getSource(), TestPlayerActions.useBlock(player, pos, face, InteractionHand.MAIN_HAND));
    }

    private static int testPlayerDrop(CommandContext<CommandSourceStack> ctx, boolean wholeStack) {
        ServerPlayer player = testPlayer(ctx);
        return player == null ? 0 : report(ctx.getSource(), TestPlayerActions.dropSelected(player, wholeStack));
    }

    private static int testPlayerMoveTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = testPlayer(ctx);

        if (player == null) {
            return 0;
        }

        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return report(ctx.getSource(), TestPlayerActions.placeAt(player, Vec3.atCenterOf(pos)));
    }

    private static int testPlayerReset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = testPlayer(ctx);
        return player == null ? 0 : report(ctx.getSource(), TestPlayerActions.reset(player));
    }

    /** Disconnects every player {@code /mcmt testplayer spawn} created. */
    private static int removeTestPlayers(CommandContext<CommandSourceStack> ctx) {
        int removed = TEST_PLAYERS.size();

        for (ServerPlayer player : TEST_PLAYERS) {
            player.connection.disconnect(Component.literal("MCMT test player removed"));
        }

        TEST_PLAYERS.clear();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " test player(s)."), true);
        return removed;
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
