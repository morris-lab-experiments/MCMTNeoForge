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
 * <p>The pool is created lazily on first use and torn down when the server stops, so a client that never starts
 * an integrated server never pays for the threads.
 */
public final class MCMTThreadPool {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private static volatile ForkJoinPool pool;

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
        LOGGER.info("MCMT: starting tick worker pool with parallelism {}", parallelism);
        return new ForkJoinPool(
                parallelism,
                p -> new MCMTWorkerThread(p, "MCMT-Worker-" + THREAD_ID.getAndIncrement()),
                (thread, throwable) -> LOGGER.error("MCMT: uncaught exception on {}", thread.getName(), throwable),
                false);
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
     * <p>Worth reporting separately from {@link #getParallelism()} because the two can diverge wildly, and when
     * they do it is the interesting fact about the server. A {@code ForkJoinPool} adds threads beyond its
     * parallelism target to replace workers that have blocked, so a design that blocks workers on pool-internal
     * waits inflates without bound: an earlier revision of {@code TickBatch} reached 1582 threads against a
     * target of 32, which cost more in scheduling than the parallelism was worth. A number here far above the
     * target means something is blocking workers on work the pool itself still has to do.
     */
    public static int getPoolSize() {
        ForkJoinPool p = pool;
        return p == null ? 0 : p.getPoolSize();
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
