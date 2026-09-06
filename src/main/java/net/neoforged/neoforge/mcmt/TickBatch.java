/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt;

import java.util.concurrent.Phaser;
import net.neoforged.neoforge.mcmt.parallel.RunnableManagedBlocker;

/**
 * A group of tick tasks that must all finish before the code that dispatched them may continue.
 *
 * <p>The tick-wide barrier in {@link MCMT} answers "has everything this server tick finished?", which is the
 * wrong question inside a level. {@code Level.tickBlockEntities} dispatches its block entities and then has to
 * wait for <em>its own</em> before it clears the ticking flag and lets queued additions through — it must not
 * wait for other levels, and other levels must not wait for it.
 *
 * <p>So each such loop opens a batch, dispatches into it, and closes it. The batch is handed around as a local
 * variable rather than looked up by level, which keeps the per-object dispatch cost to a field read.
 *
 * <p>A batch is used by exactly one dispatching thread and any number of workers.
 */
public final class TickBatch {
    /** The batch handed out when MCMT is off, on which every operation is a no-op and dispatch runs inline. */
    static final TickBatch INLINE = new TickBatch(null);

    private final Phaser phaser;

    TickBatch(Phaser phaser) {
        this.phaser = phaser;
    }

    /** True when tasks in this batch run inline on the dispatching thread. */
    public boolean isInline() {
        return this.phaser == null;
    }

    /** Registers one task. Must be paired with exactly one {@link #taskFinished()}. */
    void taskStarted() {
        this.phaser.register();
    }

    /** Marks one registered task complete. */
    void taskFinished() {
        this.phaser.arriveAndDeregister();
    }

    /**
     * Blocks until every task registered on this batch has finished.
     *
     * <p>The waiting thread is itself usually a pool worker — a level tick waiting for its block entities — so
     * the wait goes through a {@link RunnableManagedBlocker}. Without that, enough levels waiting at once would
     * consume every worker the pool is allowed and the tasks they are waiting for would never be picked up.
     */
    void await() {
        if (this.phaser == null) {
            return;
        }
        RunnableManagedBlocker.runManaged(this.phaser::arriveAndAwaitAdvance);
    }
}
