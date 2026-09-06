/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.serdes.filter.AutoFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.ConfigFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.DefaultFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.EntityFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.PistonFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.SerDesFilter;
import net.neoforged.neoforge.mcmt.serdes.filter.VanillaFilter;
import net.neoforged.neoforge.mcmt.serdes.pools.ChunkLockPool;
import net.neoforged.neoforge.mcmt.serdes.pools.PostExecutePool;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import net.neoforged.neoforge.mcmt.serdes.pools.SingleExecutionPool;

/**
 * Answers the one question the tick hooks ask: may this class run free, and if not, how should it be run?
 *
 * <p>Filters are consulted in priority order and the first opinion wins:
 *
 * <ol>
 * <li>{@link PistonFilter} and {@link EntityFilter} — vanilla classes known to reach outside themselves. Not
 * overridable, because overriding them does not make them safe.
 * <li>{@link ConfigFilter} — the server owner's white and black lists.
 * <li>{@link AutoFilter} — classes that have already thrown once while running in parallel.
 * <li>{@link VanillaFilter} — everything else in {@code net.minecraft} runs free.
 * <li>{@link DefaultFilter} — modded code of unknown thread-safety; chunk-locked by default.
 * </ol>
 *
 * <p>The answer is cached per {@code (hook, class)}, so the hot path is a single map lookup rather than a walk
 * down the filter chain. {@link #invalidate()} clears the cache; it has to be called whenever anything a filter
 * consults changes, which is why config reload and {@code AutoFilter} demotion both go through here.
 */
public final class SerDesRegistry {
    private SerDesRegistry() {}

    /**
     * One chunk of radius: enough to cover a tick that reaches into its immediate neighbours, which is what
     * pistons and nearly all machinery do. A larger radius would serialise far more than necessary.
     */
    private static final SerDesPool CHUNK_LOCK = new ChunkLockPool(1);

    /** Available for filters that need whole-server serialisation; nothing routes here by default yet. */
    private static final SerDesPool SINGLE = new SingleExecutionPool();

    /** Drained by {@code MCMT.postTick}. */
    private static final PostExecutePool POST_EXECUTE = new PostExecutePool();

    private static final AutoFilter AUTO = new AutoFilter(CHUNK_LOCK);

    private static final List<SerDesFilter> FILTERS = List.of(
            new PistonFilter(CHUNK_LOCK),
            new EntityFilter(SINGLE),
            new ConfigFilter(CHUNK_LOCK),
            AUTO,
            new VanillaFilter(),
            new DefaultFilter(CHUNK_LOCK));

    /**
     * Cached decisions. {@link SerDesFilter#FREE} is stored rather than null so that "runs free" is a cache
     * hit like any other, instead of falling through the filter chain again on every tick.
     */
    private static final Map<SerDesHookType, Map<Class<?>, SerDesPool>> CACHE = new ConcurrentHashMap<>();

    /**
     * How instances of {@code type} should be run under {@code hook}, or null when they may run unconstrained.
     *
     * <p>This is called once per ticking object per tick, so the common path is deliberately one
     * {@code computeIfAbsent} on an already-populated map.
     */
    public static SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        SerDesPool pool = CACHE.computeIfAbsent(hook, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> resolve(hook, k));
        return pool == SerDesFilter.FREE ? null : pool;
    }

    private static SerDesPool resolve(SerDesHookType hook, Class<?> type) {
        for (SerDesFilter filter : FILTERS) {
            SerDesPool pool = filter.poolFor(hook, type);
            if (pool != null) {
                return pool;
            }
        }
        // DefaultFilter always answers, so this is unreachable; treat a future filter-chain edit that breaks
        // that as "be careful" rather than "run unlocked".
        return CHUNK_LOCK;
    }

    /**
     * Records that a tick of {@code type} threw while running in parallel, so it is chunk-locked from now on.
     * Called by the tick hooks when they catch something.
     */
    public static void demote(Class<?> type, Throwable cause) {
        if (AUTO.demote(type, cause)) {
            invalidate();
        }
    }

    /** The classes {@link AutoFilter} has demoted, for {@code /mcmt stats}. */
    public static Set<Class<?>> autoDemoted() {
        return AUTO.demoted();
    }

    /**
     * Folds what {@link AutoFilter} has learnt into the configured blacklists, so that {@code /mcmt save} makes
     * it permanent. Split by supertype because a class is either an entity or a block entity, never both.
     *
     * @return how many classes were newly added
     */
    public static int persistAutoDemotions() {
        int added = 0;
        for (Class<?> type : AUTO.demoted()) {
            Set<Class<?>> target = SerDesHookType.ENTITY_TICK.targets(type)
                    ? MCMTConfig.entityBlackList
                    : MCMTConfig.blockEntityBlackList;
            if (target.add(type)) {
                added++;
            }
        }
        return added;
    }

    /** The queue drained on the server thread after the tick barrier. */
    public static PostExecutePool postExecutePool() {
        return POST_EXECUTE;
    }

    /** Whole-server serialisation, for filters that need it. */
    public static SerDesPool singleExecutionPool() {
        return SINGLE;
    }

    /** Forgets every cached decision. Call after anything a filter consults changes. */
    public static void invalidate() {
        CACHE.clear();
    }
}
