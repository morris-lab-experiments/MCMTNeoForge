/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;

/**
 * Runs a potentially blocking task in a way a {@link ForkJoinPool} understands.
 *
 * <p>A {@code ForkJoinPool} keeps only as many live workers as its parallelism setting. If a worker blocks on a
 * lock without telling the pool, that slot is simply gone, and enough of them blocking at once stalls the pool
 * entirely. Wrapping the blocking section in a {@link ForkJoinPool.ManagedBlocker} lets the pool spin up a
 * compensation thread for the duration, so the remaining work keeps flowing.
 *
 * <p>Use this for anything that waits: acquiring a chunk lock, joining another tick's future, waiting on a
 * chunk load. Ported from JMT-MCMT.
 */
public final class RunnableManagedBlocker implements ForkJoinPool.ManagedBlocker {
    private final BooleanSupplier task;
    private boolean done;

    /** Wraps a task that reports whether it finished; it is retried until it returns true. */
    public RunnableManagedBlocker(BooleanSupplier task) {
        this.task = task;
    }

    /** Wraps a task that always finishes in one call. */
    public RunnableManagedBlocker(Runnable task) {
        this.task = () -> {
            task.run();
            return true;
        };
    }

    /**
     * Runs {@code task} inside a managed block, so that a {@link ForkJoinPool} worker blocking here does not
     * starve the pool. Outside a pool worker this just runs the task.
     */
    public static void runManaged(Runnable task) {
        try {
            ForkJoinPool.managedBlock(new RunnableManagedBlocker(task));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running a managed tick task", e);
        }
    }

    @Override
    public boolean block() throws InterruptedException {
        if (!done) {
            done = task.getAsBoolean();
        }
        return done;
    }

    @Override
    public boolean isReleasable() {
        return done;
    }
}
