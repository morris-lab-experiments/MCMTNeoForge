/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The worker pool that parallel tick tasks are dispatched to.
 *
 * <p>A {@link ForkJoinPool} rather than a fixed thread pool because tick tasks nest: a level tick dispatches
 * entity ticks, which dispatch block-entity ticks. Work stealing keeps a worker that is blocked inside a nested
 * dispatch from idling, and {@link ForkJoinPool.ManagedBlocker} lets a worker that genuinely has to wait for a
 * chunk lock hand its slot to a compensation thread instead of deadlocking the pool. See {@link ManagedLock},
 * and note that "genuinely" is the whole difference — compensating for a wait that was not going to happen is
 * what once grew this pool to 1582 threads against a target of 32.
 *
 * <h2>The hard thread cap</h2>
 *
 * <p>Fixing the {@code ManagedBlocker} misuse got the worst of that back, but every contention source found
 * since — the coarse POI monitor, the chunk-load pump, the hopper position locks — has inflated the pool the
 * same way: a worker blocks, and a {@code join} elsewhere makes the pool compensate for it with a fresh thread.
 * Chasing each source with a finer lock has diminishing returns. So the pool is built with an explicit
 * {@code maximumPoolSize} — {@link MCMTConfig#getMaxPoolSize()}, by default the parallelism target itself — and a
 * {@code saturate} predicate that returns {@code true}, meaning "at the cap, let the blocking task wait rather
 * than throw {@code RejectedExecutionException}". Every place MCMT blocks a worker (a {@code synchronized}
 * monitor, a {@link ManagedLock}, the {@code ServerChunkCache} pump) is released by a thread that is itself
 * making progress and is not one of ours waiting on a pool task, so capping compensation costs parallelism
 * during contention but cannot deadlock.
 *
 * <p>The pool is created lazily on first use and torn down when the server stops, so a client that never starts
 * an integrated server never pays for the threads.
 */
public final class MCMTThreadPool {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private static volatile ForkJoinPool pool;

    /** The {@code maximumPoolSize} the live pool was built with, for {@code /mcmt stats}. Zero when none exists. */
    private static volatile int maxPoolSize;

    private MCMTThreadPool() {}

    /**
     * The pool, creating it if it does not exist yet. Sized from {@link MCMTConfig#getParallelism()} at creation
     * time; a later config change needs {@link #restart()} to take effect.
     */
    public static ForkJoinPool get() {
        ForkJoinPool p = pool;
        if (p == null) {
            synchronized (MCMTThreadPool.class) {
                p = pool;
                if (p == null) {
                    p = create(MCMTConfig.getParallelism());
                    pool = p;
                }
            }
        }
        return p;
    }

    private static ForkJoinPool create(int parallelism) {
        int maxThreads = Math.max(parallelism, MCMTConfig.getMaxPoolSize());
        maxPoolSize = maxThreads;
        LOGGER.info("MCMT: starting tick worker pool, parallelism {}, hard thread cap {}", parallelism, maxThreads);
        return new ForkJoinPool(
                parallelism,
                p -> new MCMTWorkerThread(p, "MCMT-Worker-" + THREAD_ID.getAndIncrement()),
                (thread, throwable) -> LOGGER.error("MCMT: uncaught exception on {}", thread.getName(), throwable),
                false,
                parallelism,
                maxThreads,
                1,
                // At the cap, run the blocking task's caller straight through rather than reject it: everything
                // MCMT blocks a worker on is released by a thread that is itself progressing, so waiting is safe.
                p -> true,
                60L,
                TimeUnit.SECONDS);
    }

    /** True when the calling thread is one of our workers. An {@code instanceof} check; safe on any hot path. */
    public static boolean isPoolThread() {
        return Thread.currentThread() instanceof MCMTWorkerThread;
    }

    /** True when a pool exists. Lets callers avoid creating one just to ask about it. */
    public static boolean isStarted() {
        return pool != null;
    }

    /** The configured worker count, or zero when no pool has been created. */
    public static int getParallelism() {
        ForkJoinPool p = pool;
        return p == null ? 0 : p.getParallelism();
    }

    /**
     * Threads the pool has actually started, or zero when no pool has been created.
     *
     * <p>Worth reporting separately from {@link #getParallelism()} because the two can diverge, and when they do
     * it is the interesting fact about the server. A {@code ForkJoinPool} adds threads beyond its parallelism
     * target to replace workers that have blocked; {@link #getMaxPoolSize()} is the ceiling that growth is now
     * held to. A number sitting at the ceiling means workers are blocking often enough that the pool would grow
     * past it if allowed — contention worth investigating, but bounded.
     */
    public static int getPoolSize() {
        ForkJoinPool p = pool;
        return p == null ? 0 : p.getPoolSize();
    }

    /** The hard ceiling on {@link #getPoolSize()} for the live pool, or zero when no pool has been created. */
    public static int getMaxPoolSize() {
        return pool == null ? 0 : maxPoolSize;
    }

    /** Tasks submitted but not yet finished, or zero when no pool has been created. */
    public static long getQueuedTaskCount() {
        ForkJoinPool p = pool;
        return p == null ? 0L : p.getQueuedSubmissionCount() + p.getQueuedTaskCount();
    }

    /** Discards the current pool so the next {@link #get()} builds one at the currently configured size. */
    public static synchronized void restart() {
        shutdown();
        get();
    }

    /**
     * Stops the pool and waits briefly for workers to drain. Called on server stop. A task still running after
     * the grace period is logged and abandoned rather than interrupted, because interrupting a half-finished
     * tick would corrupt world state more surely than leaking a thread.
     */
    public static synchronized void shutdown() {
        ForkJoinPool p = pool;
        if (p == null) {
            return;
        }
        pool = null;
        maxPoolSize = 0;
        p.shutdown();
        try {
            if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("MCMT: tick worker pool did not drain within 5s; abandoning {} running task(s)", p.getActiveThreadCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
