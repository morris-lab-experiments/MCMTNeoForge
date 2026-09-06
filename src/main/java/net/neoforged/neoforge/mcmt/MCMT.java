/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single point every multi-core tick hook in the patched Minecraft sources calls into.
 *
 * <p>Corresponds to JMT-MCMT's {@code ASMHookTerminator}. Where JMT reached these methods through ASM
 * transformers, we call them directly from the patched sources; the intent is the same, and so is the rule that
 * every hook site stays a one-line call-out so the patch diffs remain readable.
 *
 * <p>The hook sites, in the order a tick reaches them:
 *
 * <ol>
 * <li>{@link #preTick} / {@link #postTick} bracket {@code MinecraftServer.tickChildren}, opening and closing the
 * barrier that everything dispatched during the tick is registered against.
 * <li>{@link #callTick} dispatches one {@code ServerLevel.tick}.
 * <li>{@link #callEntityTick} dispatches one entity tick, from {@code ServerLevel.tick}.
 * <li>{@link #callBlockEntityTick} dispatches one block-entity tick, from {@code Level.tickBlockEntities}.
 * <li>{@link #callTickChunk} dispatches one chunk/environment tick, from {@code ServerChunkCache.tickChunks}.
 * </ol>
 *
 * <p><b>Current state: every {@code callX} runs its task inline on the calling thread.</b> The hook sites are
 * wired and the configuration, command and statistics plumbing is live, but no work is handed to the pool yet.
 * Parallel dispatch arrives one hook at a time, starting with {@link #callTick}.
 */
public final class MCMT {
    private static final Logger LOGGER = LogManager.getLogger();

    private MCMT() {}

    /** The server whose tick we are currently inside, or null between ticks. Guards against two servers at once. */
    private static volatile MinecraftServer currentServer;

    /** True between {@link #preTick} and {@link #postTick}. */
    private static final AtomicBoolean ticking = new AtomicBoolean();

    // Live task counters, surfaced by /mcmt stats.
    private static final AtomicInteger runningLevelTicks = new AtomicInteger();
    private static final AtomicInteger runningEntityTicks = new AtomicInteger();
    private static final AtomicInteger runningBlockEntityTicks = new AtomicInteger();
    private static final AtomicInteger runningChunkTicks = new AtomicInteger();

    /**
     * Names of the tasks currently in flight, populated only while {@link MCMTConfig#opsTracing} is on. Read by
     * {@link #populateCrashReport()}, which is the whole point: a concurrency crash is far easier to diagnose
     * when the report says which four entities were mid-tick.
     */
    private static final Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    /** Rolling buffer of the last 32 tick durations in nanoseconds, for {@code /mcmt perf}. */
    private static final long[] tickTimes = new long[32];
    private static int tickTimePos;
    private static int tickTimeFill;
    private static long tickStartNanos;

    // Hook H5: thread identity
    // ------------------------

    /**
     * True when the calling thread is an MCMT worker.
     *
     * <p>Minecraft asks "am I on the server thread?" in a great many places, and answers it by comparing against
     * a single stored {@code Thread}. Under MCMT a worker running a tick is, for every purpose that check exists
     * to serve, on the server thread. So {@code BlockableEventLoop.isSameThread} and friends accept a pool
     * thread as well, and this is the predicate they use.
     */
    public static boolean isPoolThread() {
        return MCMTThreadPool.isPoolThread();
    }

    /** True when parallel dispatch is switched off entirely and every hook should run inline. */
    public static boolean isDisabled() {
        return MCMTConfig.disabled;
    }

    // Hook H1: the server tick barrier
    // --------------------------------

    /** Called at the top of {@code MinecraftServer.tickChildren}, before the level loop. */
    public static void preTick(MinecraftServer server) {
        if (currentServer != null && currentServer != server) {
            LOGGER.warn("MCMT: a second MinecraftServer started ticking while {} was mid-tick; disabling MCMT", currentServer);
            MCMTConfig.disabled = true;
            return;
        }
        currentServer = server;
        tickStartNanos = System.nanoTime();
        ticking.set(true);
    }

    /** Called at the bottom of {@code MinecraftServer.tickChildren}, after the level loop. */
    public static void postTick(MinecraftServer server) {
        if (currentServer != server) {
            return;
        }
        ticking.set(false);
        currentServer = null;

        tickTimes[tickTimePos] = System.nanoTime() - tickStartNanos;
        tickTimePos = (tickTimePos + 1) % tickTimes.length;
        tickTimeFill = Math.min(tickTimeFill + 1, tickTimes.length);
    }

    /** True while we are between {@link #preTick} and {@link #postTick}. */
    public static boolean isTicking() {
        return ticking.get();
    }

    // The tick hooks
    // --------------

    /** Hook H1. Ticks one level; from {@code MinecraftServer.tickChildren}. */
    public static void callTick(ServerLevel level, BooleanSupplier haveTime, MinecraftServer server) {
        if (MCMTConfig.disabled || MCMTConfig.disableWorld) {
            level.tick(haveTime);
            return;
        }
        String task = beginTrace("LevelTick", level);
        runningLevelTicks.incrementAndGet();
        try {
            level.tick(haveTime);
        } finally {
            runningLevelTicks.decrementAndGet();
            endTrace(task);
        }
    }

    /**
     * Hook H2. Ticks one entity; from the {@code entityTickList.forEach} lambda in {@code ServerLevel.tick}.
     *
     * <p>{@code ticker} is the level's cached guarded-tick consumer, which wraps {@code tickNonPassenger} in
     * {@code guardEntityTick}'s crash reporting. It is passed in rather than reconstructed here so that the hot
     * path allocates nothing.
     */
    public static void callEntityTick(Consumer<Entity> ticker, Entity entity, ServerLevel level) {
        if (MCMTConfig.disabled || MCMTConfig.disableEntity) {
            ticker.accept(entity);
            return;
        }
        String task = beginTrace("EntityTick", entity);
        runningEntityTicks.incrementAndGet();
        try {
            ticker.accept(entity);
        } finally {
            runningEntityTicks.decrementAndGet();
            endTrace(task);
        }
    }

    /** Hook H3. Ticks one block entity; from {@code Level.tickBlockEntities}. */
    public static void callBlockEntityTick(TickingBlockEntity blockEntity, Level level) {
        if (MCMTConfig.disabled || MCMTConfig.disableBlockEntity || !(level instanceof ServerLevel)) {
            blockEntity.tick();
            return;
        }
        String task = beginTrace("BlockEntityTick", blockEntity);
        runningBlockEntityTicks.incrementAndGet();
        try {
            blockEntity.tick();
        } finally {
            runningBlockEntityTicks.decrementAndGet();
            endTrace(task);
        }
    }

    /** Hook H4. Runs one chunk's environment tick; from {@code ServerChunkCache.tickChunks}. */
    public static void callTickChunk(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        if (MCMTConfig.disabled || MCMTConfig.disableEnvironment) {
            level.tickChunk(chunk, randomTickSpeed);
            return;
        }
        String task = beginTrace("ChunkTick", chunk);
        runningChunkTicks.incrementAndGet();
        try {
            level.tickChunk(chunk, randomTickSpeed);
        } finally {
            runningChunkTicks.decrementAndGet();
            endTrace(task);
        }
    }

    // Operation tracing
    // -----------------

    /** Records a task as in flight, when tracing is on. Returns the name to pass to {@link #endTrace}, or null. */
    private static String beginTrace(String kind, Object subject) {
        if (!MCMTConfig.opsTracing) {
            return null;
        }
        String name = kind + ": " + subject + "@" + System.identityHashCode(subject);
        currentTasks.add(name);
        return name;
    }

    private static void endTrace(String name) {
        if (name != null) {
            currentTasks.remove(name);
        }
    }

    // Diagnostics
    // -----------

    public static int getRunningLevelTicks() {
        return runningLevelTicks.get();
    }

    public static int getRunningEntityTicks() {
        return runningEntityTicks.get();
    }

    public static int getRunningBlockEntityTicks() {
        return runningBlockEntityTicks.get();
    }

    public static int getRunningChunkTicks() {
        return runningChunkTicks.get();
    }

    /** The names of the tasks currently in flight. Empty unless {@link MCMTConfig#opsTracing} is on. */
    public static Set<String> getCurrentTasks() {
        return currentTasks;
    }

    /** Mean of the recorded tick durations, in milliseconds, or zero before the first tick completes. */
    public static double getMeanTickTimeMillis() {
        int fill = tickTimeFill;
        if (fill == 0) {
            return 0.0D;
        }
        long total = 0L;
        for (int i = 0; i < fill; i++) {
            total += tickTimes[i];
        }
        return total / (double) fill / 1_000_000.0D;
    }

    /** Longest recorded tick duration in milliseconds, or zero before the first tick completes. */
    public static double getMaxTickTimeMillis() {
        int fill = tickTimeFill;
        long max = 0L;
        for (int i = 0; i < fill; i++) {
            max = Math.max(max, tickTimes[i]);
        }
        return max / 1_000_000.0D;
    }

    /** How many tick durations the rolling buffer currently holds. */
    public static int getRecordedTickCount() {
        return tickTimeFill;
    }

    /**
     * The MCMT section of a crash report. Registered as a crash callable, because when a parallel tick crashes
     * the first two questions are always "was MCMT even on?" and "what else was running at the time?".
     */
    public static String populateCrashReport() {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("\t\tDisabled: ").append(MCMTConfig.disabled).append('\n');
        sb.append("\t\tParallelism: ").append(MCMTThreadPool.isStarted() ? MCMTThreadPool.getParallelism() : "pool not started").append('\n');
        sb.append("\t\tWorld ticks parallel: ").append(!MCMTConfig.disableWorld).append('\n');
        sb.append("\t\tEntity ticks parallel: ").append(!MCMTConfig.disableEntity).append('\n');
        sb.append("\t\tBlock entity ticks parallel: ").append(!MCMTConfig.disableBlockEntity).append('\n');
        sb.append("\t\tChunk ticks parallel: ").append(!MCMTConfig.disableEnvironment).append('\n');
        sb.append("\t\tConcurrent chunk provider: ").append(!MCMTConfig.disableChunkProvider).append('\n');
        sb.append("\t\tChunk-lock modded classes: ").append(MCMTConfig.chunkLockModded).append('\n');
        sb.append("\t\tIn flight: ")
                .append(runningLevelTicks.get()).append(" level, ")
                .append(runningEntityTicks.get()).append(" entity, ")
                .append(runningBlockEntityTicks.get()).append(" block entity, ")
                .append(runningChunkTicks.get()).append(" chunk\n");
        if (MCMTConfig.opsTracing) {
            sb.append("\t\tRunning tasks:\n");
            for (String task : currentTasks) {
                sb.append("\t\t\t").append(task).append('\n');
            }
        } else {
            sb.append("\t\tRunning tasks: not recorded (set opsTracing = true to capture these)\n");
        }
        return sb.toString();
    }
}
