/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.SerDesRegistry;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
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
 * <p>All four hooks now dispatch to the pool.
 *
 * <h2>The barrier</h2>
 *
 * <p>Synchronisation is a single {@link Phaser} per tick, following JMT-MCMT. {@link #preTick} creates it and
 * registers the server thread as one party. Each dispatch registers another party and the task deregisters when
 * it finishes. {@link #postTick} then arrives and waits for the phase to advance, which cannot happen until
 * every task has deregistered. So the barrier is "the server thread waits for all outstanding tick work",
 * expressed without a counter and a condition variable.
 *
 * <p>The phaser also carries the memory visibility the whole design rests on: everything a worker wrote to the
 * world before its {@code arriveAndDeregister} happens-before everything the server thread reads after its
 * {@code arriveAndAwaitAdvance}.
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
     * How many level ticks have actually been handed to the pool, as opposed to run inline. This is the one
     * number that answers "is MCMT doing anything?", so it is worth a counter rather than being inferred from
     * tick timings. The other hooks get their own as they are switched over.
     */
    private static final AtomicLong dispatchedLevelTicks = new AtomicLong();

    /** As {@link #dispatchedLevelTicks}, for block entities. */
    private static final AtomicLong dispatchedBlockEntityTicks = new AtomicLong();

    /** As {@link #dispatchedLevelTicks}, for entities. */
    private static final AtomicLong dispatchedEntityTicks = new AtomicLong();

    /** As {@link #dispatchedLevelTicks}, for chunk/environment ticks. */
    private static final AtomicLong dispatchedChunkTicks = new AtomicLong();

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

    // Per-tick state. Everything below is written by the server thread inside preTick and read back by it in
    // postTick, so plain fields are enough except where a worker also touches them.

    /**
     * The barrier for this tick, or null when this tick is running inline. Created by {@link #preTick} with the
     * server thread pre-registered as one party.
     */
    private static Phaser phaser;

    /**
     * Whether this tick may dispatch at all, decided once in {@link #preTick}. Config is runtime-mutable, and a
     * hook that consulted {@link MCMTConfig#disabled} directly could find it flipped halfway through a tick and
     * dispatch against a phaser that {@link #postTick} is no longer going to wait on. Snapshotting it removes
     * that whole class of race.
     */
    private static boolean dispatchThisTick;

    /**
     * One level handed to the pool this tick. {@code nanos} is written by the worker that ran the tick and read
     * by the server thread after the barrier; the phaser supplies the happens-before, so it needs no volatile.
     */
    private static final class Dispatch {
        final ServerLevel level;
        long nanos;
        boolean failed;

        Dispatch(ServerLevel level) {
            this.level = level;
        }
    }

    /**
     * The levels dispatched this tick, in world order. The list itself is only ever touched from the server
     * thread: {@link #callTick} is called from the {@code tickChildren} loop and {@link #postTick} drains it
     * after the barrier.
     */
    private static final List<Dispatch> dispatchedLevels = new ArrayList<>();

    /**
     * The first level tick to throw this tick, rethrown on the server thread by {@link #postTick}.
     *
     * <p>Vanilla turns a throwing level tick into a {@code ReportedException} that kills the server, and it has
     * to stay that way: a world that failed halfway through its tick has inconsistent state, and carrying on
     * would corrupt the save rather than merely crash. The exception surfaces at the barrier instead of at the
     * call site because that is the first moment the server thread is in a position to receive it.
     */
    private static final AtomicReference<ReportedException> levelTickFailure = new AtomicReference<>();

    /** Records the duration of one level's tick where the server keeps its per-dimension timings. */
    private static ObjLongConsumer<ServerLevel> tickTimeRecorder;

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

    /**
     * Called at the top of {@code MinecraftServer.tickChildren}, before the level loop. Opens the barrier this
     * tick's work registers against.
     *
     * @param recorder where to file each level's tick duration, so the server's per-dimension timings stay
     *                 truthful once the loop stops measuring them itself
     */
    public static void preTick(MinecraftServer server, ObjLongConsumer<ServerLevel> recorder) {
        if (currentServer != null && currentServer != server) {
            LOGGER.warn("MCMT: a second MinecraftServer started ticking while {} was mid-tick; disabling MCMT", currentServer);
            MCMTConfig.disabled = true;
        }
        currentServer = server;
        tickTimeRecorder = recorder;
        tickStartNanos = System.nanoTime();
        ticking.set(true);

        dispatchedLevels.clear();
        levelTickFailure.set(null);
        dispatchThisTick = !MCMTConfig.disabled;
        phaser = dispatchThisTick ? new Phaser(1) : null;
    }

    /**
     * Called at the bottom of {@code MinecraftServer.tickChildren}, after the level loop. Waits for every
     * dispatched level tick, then fires the {@code LevelTickEvent.Post} events that were deferred past the
     * barrier, and finally rethrows whatever a worker crashed with.
     */
    public static void postTick(MinecraftServer server, BooleanSupplier haveTime) {
        if (currentServer != server) {
            return;
        }

        Phaser barrier = phaser;
        if (barrier != null) {
            barrier.arriveAndAwaitAdvance();
            phaser = null;
            dispatchThisTick = false;

            // Deferred from the dispatch loop. NeoForge's own Post listeners mutate level state and every mod
            // listener assumes the server thread, so these are fired here, serially, in world order — see Q3 in
            // MCMT-PLAN.md. The dispatch loop fired the matching Pre events before handing the levels off, so
            // the observable Pre/Post ordering is unchanged from vanilla.
            for (Dispatch dispatch : dispatchedLevels) {
                // A level whose tick threw gets no Post, matching vanilla: there the ReportedException
                // propagates out of the loop before fireLevelTickPost is reached.
                if (!dispatch.failed) {
                    EventHooks.fireLevelTickPost(dispatch.level, haveTime);
                }
                recordLevelTickTime(dispatch.level, dispatch.nanos);
            }
            dispatchedLevels.clear();

            // Ticks that could not run concurrently with anything were deferred to here, where the server
            // thread is the only thing running. See PostExecutePool.
            SerDesRegistry.postExecutePool().drain();
        }

        ticking.set(false);
        currentServer = null;
        tickTimeRecorder = null;

        tickTimes[tickTimePos] = System.nanoTime() - tickStartNanos;
        tickTimePos = (tickTimePos + 1) % tickTimes.length;
        tickTimeFill = Math.min(tickTimeFill + 1, tickTimes.length);

        ReportedException failure = levelTickFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    /** True while we are between {@link #preTick} and {@link #postTick}. */
    public static boolean isTicking() {
        return ticking.get();
    }

    private static void recordLevelTickTime(ServerLevel level, long nanos) {
        ObjLongConsumer<ServerLevel> recorder = tickTimeRecorder;
        if (recorder != null) {
            recorder.accept(level, nanos);
        }
    }

    // The tick hooks
    // --------------

    /**
     * Hook H1. Ticks one level; from {@code MinecraftServer.tickChildren}.
     *
     * <p>In the parallel path the tick is handed to the pool and this returns immediately, so the caller must
     * not assume the level has ticked when it returns — {@link #postTick} is where that becomes true. The
     * caller has already fired this level's {@code LevelTickEvent.Pre}; the matching {@code Post} is deferred
     * to the barrier.
     */
    public static void callTick(ServerLevel level, BooleanSupplier haveTime, MinecraftServer server) {
        if (!dispatchThisTick || MCMTConfig.disableWorld || currentServer != server) {
            long start = System.nanoTime();
            try {
                level.tick(haveTime);
                // Inside the try, so a throwing tick skips it: vanilla propagates the ReportedException out of
                // tickChildren before it ever reaches fireLevelTickPost.
                EventHooks.fireLevelTickPost(level, haveTime);
            } finally {
                recordLevelTickTime(level, System.nanoTime() - start);
            }
            return;
        }

        Dispatch dispatch = new Dispatch(level);
        dispatchedLevels.add(dispatch);

        String task = beginTrace("LevelTick", level);
        Phaser barrier = phaser;
        barrier.register();
        try {
            MCMTThreadPool.get().execute(() -> {
                long start = System.nanoTime();
                runningLevelTicks.incrementAndGet();
                try {
                    level.tick(haveTime);
                } catch (Throwable throwable) {
                    dispatch.failed = true;
                    CrashReport report = CrashReport.forThrowable(throwable, "Exception ticking world");
                    level.fillReportDetails(report);
                    levelTickFailure.compareAndSet(null, new ReportedException(report));
                } finally {
                    dispatch.nanos = System.nanoTime() - start;
                    runningLevelTicks.decrementAndGet();
                    endTrace(task);
                    barrier.arriveAndDeregister();
                }
            });
            dispatchedLevelTicks.incrementAndGet();
        } catch (Throwable throwable) {
            // The pool refused the task — it is shutting down, or out of memory. The party we just registered
            // would otherwise never arrive and postTick would block on the barrier forever, hanging the server.
            barrier.arriveAndDeregister();
            endTrace(task);
            dispatch.failed = true;
            throw throwable;
        }
    }

    /**
     * Hook H2. Opens a batch for one level's entity ticks; from {@code ServerLevel.tick}, before the entity
     * loop. Closed by {@link #finishEntityTicks} before {@code tickBlockEntities}, so entity and block-entity
     * ticks never overlap within a level.
     */
    public static TickBatch startEntityTicks(ServerLevel level) {
        if (!dispatchThisTick || MCMTConfig.disableEntity) {
            return TickBatch.INLINE;
        }
        return new TickBatch(true);
    }

    /**
     * Hook H2. Ticks one entity into the batch; from the {@code entityTickList.forEach} lambda in
     * {@code ServerLevel.tick}.
     *
     * <p>{@code ticker} is the level's cached guarded-tick consumer, which wraps {@code tickNonPassenger} in
     * {@code guardEntityTick}'s crash reporting. It is passed in rather than reconstructed here so that the hot
     * path allocates nothing.
     */
    public static void callEntityTick(TickBatch batch, Consumer<Entity> ticker, Entity entity, ServerLevel level) {
        // Portal transit is decided per entity rather than per class, so it cannot live in a SerDes filter. An
        // entity mid-portal is about to be removed from this level and added to another, which touches two
        // levels' entity managers at once; the dispatching thread is the only safe place for that.
        if (batch.isInline() || entity.portalProcess != null) {
            ticker.accept(entity);
            return;
        }

        Class<?> type = entity.getClass();
        SerDesPool pool = SerDesRegistry.poolFor(SerDesHookType.ENTITY_TICK, type);

        String task = beginTrace("EntityTick", entity);
        try {
            batch.dispatch(() -> {
                runningEntityTicks.incrementAndGet();
                try {
                    if (pool == null) {
                        ticker.accept(entity);
                    } else {
                        pool.serialise(() -> ticker.accept(entity), entity.blockPosition(), level);
                    }
                } catch (Throwable throwable) {
                    // Same reasoning as runBlockEntityTick: demote rather than crash. guardEntityTick already
                    // absorbs most of what an entity tick can throw, so reaching here means MCMT's own doing.
                    SerDesRegistry.demote(type, throwable);
                    LOGGER.error("MCMT: exception ticking entity {} at {}", type.getName(), entity.blockPosition(), throwable);
                } finally {
                    runningEntityTicks.decrementAndGet();
                    endTrace(task);
                }
            });
            dispatchedEntityTicks.incrementAndGet();
        } catch (Throwable throwable) {
            endTrace(task);
            throw throwable;
        }
    }

    /** Hook H2. Waits for every entity dispatched into {@code batch}; from {@code ServerLevel.tick}. */
    public static void finishEntityTicks(TickBatch batch) {
        batch.await();
    }

    /**
     * Hook H2b. Guards one mob's attempt to pick up one item; wraps the {@code pickUpItem} call sites in
     * {@code Mob.aiStep} and {@code Raider}'s banner goal.
     *
     * <p>Mob loot pickup is a read-modify-write across two entities: the mob reads the item's stack, takes some
     * of it, and discards the item if it emptied. Two mobs reaching one item — which they can do from ~2.6
     * blocks apart, because the pickup box is the mob's bounding box inflated by one — both pass the pre-checks,
     * both take the stack, and the item ends up in two hands and still on the ground. The harness measured it
     * creating roughly one item per 400–500 ticks.
     *
     * <p>This cannot be fixed by routing the mob to a pool: the lock would be on the <em>mob's</em> position,
     * and two mobs 2.6 blocks apart have disjoint locked regions. The scope that works is the <em>item's</em>
     * position — every mob claiming that item locks the same block, wherever the mobs themselves are — and it
     * has to be taken here, part-way through the tick, rather than around the whole tick as a filter would.
     *
     * <p>The {@code isRemoved} re-check inside the lock is the point of the whole thing: the mob that loses the
     * race acquires the lock, sees the item already gone, and does nothing. It uses {@link SerDesRegistry}'s
     * block-position lock table, the same one {@link ItemEntityFilter} routes item-entity ticks to, so a pickup
     * and a merge that touch one item also serialise.
     *
     * @param item    the item this mob is about to try to pick up
     * @param attempt the vanilla pickup, including its guard conditions — run at most once, and only if the item
     *                is still present when the lock is held
     */
    public static void guardItemPickup(ItemEntity item, Runnable attempt) {
        if (!dispatchThisTick || MCMTConfig.disableEntity) {
            attempt.run();
            return;
        }
        SerDesRegistry.posLockPool().serialise(() -> {
            if (!item.isRemoved()) {
                attempt.run();
            }
        }, item.blockPosition(), item.level());
    }

    /**
     * Hook H3. Opens a batch for one level's block-entity ticks; from the top of {@code Level.tickBlockEntities}.
     *
     * <p>Block entities need their own barrier rather than the tick-wide one, because the loop that dispatches
     * them has to know when <em>its</em> block entities are done — it clears {@code tickingBlockEntities}
     * immediately afterwards, which lets queued additions through. Waiting for the whole server tick there
     * would deadlock: the server tick is waiting for this level.
     */
    public static TickBatch startBlockEntityTicks(Level level) {
        if (!dispatchThisTick || MCMTConfig.disableBlockEntity || !(level instanceof ServerLevel)) {
            return TickBatch.INLINE;
        }
        return new TickBatch(true);
    }

    /** Hook H3. Ticks one block entity into the batch; from the loop in {@code Level.tickBlockEntities}. */
    public static void callBlockEntityTick(TickBatch batch, TickingBlockEntity blockEntity, Level level) {
        Class<?> type = blockEntity.mcmtTickedType();
        SerDesPool pool = SerDesRegistry.poolFor(SerDesHookType.BLOCK_ENTITY_TICK, type);

        if (batch.isInline()) {
            runBlockEntityTick(blockEntity, level, type, pool);
            return;
        }

        String task = beginTrace("BlockEntityTick", blockEntity);
        try {
            batch.dispatch(() -> {
                runningBlockEntityTicks.incrementAndGet();
                try {
                    runBlockEntityTick(blockEntity, level, type, pool);
                } finally {
                    runningBlockEntityTicks.decrementAndGet();
                    endTrace(task);
                }
            });
            dispatchedBlockEntityTicks.incrementAndGet();
        } catch (Throwable throwable) {
            endTrace(task);
            throw throwable;
        }
    }

    /**
     * Runs one block-entity tick, through its pool if it has one, and demotes its class if it throws.
     *
     * <p>Swallowing the exception is deliberate and is what makes MCMT survivable on a modded server. A tick
     * that throws here has almost certainly lost a race rather than hit a logic bug — the same code has run
     * single-threaded for years. Crashing the server would punish the owner for MCMT's choice; continuing to
     * run the class in parallel would keep corrupting the world. Demoting it to a chunk lock does neither.
     */
    private static void runBlockEntityTick(TickingBlockEntity blockEntity, Level level, Class<?> type, SerDesPool pool) {
        try {
            if (pool == null) {
                blockEntity.tick();
            } else {
                pool.serialise(blockEntity::tick, blockEntity.getPos(), level);
            }
        } catch (Throwable throwable) {
            SerDesRegistry.demote(type, throwable);
            LOGGER.error("MCMT: exception ticking block entity {} at {}", type.getName(), blockEntity.getPos(), throwable);
        }
    }

    /**
     * Hook H3. Waits for every block entity dispatched into {@code batch}; from the bottom of
     * {@code Level.tickBlockEntities}, before the ticking flag is cleared.
     */
    public static void finishBlockEntityTicks(TickBatch batch) {
        batch.await();
    }

    /**
     * Hook H4. Opens a batch for one level's chunk ticks; from {@code ServerChunkCache.tickChunks}, before the
     * spawn-and-tick loop.
     */
    public static TickBatch startChunkTicks(ServerLevel level) {
        if (!dispatchThisTick || MCMTConfig.disableEnvironment) {
            return TickBatch.INLINE;
        }
        return new TickBatch(true);
    }

    /**
     * Hook H4. Runs one chunk's environment tick into the batch; from {@code ServerChunkCache.tickChunks}.
     *
     * <p>This is random ticks, precipitation, ice and lightning: the highest block-mutation rate in the game,
     * and the reason a chunk tick is dispatched by position rather than treated like the other hooks. Fire,
     * fluids and farmland all write across chunk borders, so the work is genuinely overlapping and the safety
     * comes from the same place a block entity's does — the chunk locks under {@code setBlock}'s neighbours.
     *
     * <p>{@code NaturalSpawner.spawnForChunk} deliberately stays on the calling thread: it consults a
     * whole-level mob cap that only makes sense evaluated serially.
     */
    public static void callTickChunk(TickBatch batch, ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        if (batch.isInline()) {
            level.tickChunk(chunk, randomTickSpeed);
            return;
        }

        String task = beginTrace("ChunkTick", chunk);
        try {
            batch.dispatch(() -> {
                runningChunkTicks.incrementAndGet();
                try {
                    level.tickChunk(chunk, randomTickSpeed);
                } catch (Throwable throwable) {
                    // No class to demote — a chunk tick is not one object's code — so this is logged and the
                    // tick abandoned. Losing one chunk's random ticks for one tick is survivable; taking the
                    // server down for it is not.
                    LOGGER.error("MCMT: exception ticking chunk {} in {}", chunk.getPos(), level.dimension().location(), throwable);
                } finally {
                    runningChunkTicks.decrementAndGet();
                    endTrace(task);
                }
            });
            dispatchedChunkTicks.incrementAndGet();
        } catch (Throwable throwable) {
            endTrace(task);
            throw throwable;
        }
    }

    /** Hook H4. Waits for every chunk dispatched into {@code batch}; from {@code ServerChunkCache.tickChunks}. */
    public static void finishChunkTicks(TickBatch batch) {
        batch.await();
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

    /** How many level ticks have been handed to the pool since startup. Zero means nothing is parallelised. */
    public static long getDispatchedLevelTicks() {
        return dispatchedLevelTicks.get();
    }

    /** How many block-entity ticks have been handed to the pool since startup. */
    public static long getDispatchedBlockEntityTicks() {
        return dispatchedBlockEntityTicks.get();
    }

    /** How many entity ticks have been handed to the pool since startup. */
    public static long getDispatchedEntityTicks() {
        return dispatchedEntityTicks.get();
    }

    /** How many chunk/environment ticks have been handed to the pool since startup. */
    public static long getDispatchedChunkTicks() {
        return dispatchedChunkTicks.get();
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
