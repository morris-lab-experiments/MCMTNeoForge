/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt;

import java.util.Arrays;
import java.util.concurrent.ForkJoinTask;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;

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
 *
 * <h2>Why this holds tasks rather than a {@link java.util.concurrent.Phaser}</h2>
 *
 * <p>It used to be a {@code Phaser}, waited on through a {@code ManagedBlocker} so that a worker parked on a
 * sub-barrier would not consume one of the pool's permitted threads. That is the textbook use of
 * {@code ManagedBlocker}, and it deadlocks without it — but the compensation it buys is a <em>new thread</em>,
 * every time, and the thread that caused it is one of ours waiting on tasks in the very same pool. Measured at
 * {@code paraMax=32} on a heavy world: <b>1582 live workers on 32 cores</b>, only about thirteen of them
 * runnable in any sample, and a tick time no better than single-threaded. The parallelism did not degrade
 * because of lock contention — sampling found essentially none — but because the pool had inflated to the point
 * where scheduling it cost more than the work.
 *
 * <p>Holding the {@link ForkJoinTask}s and joining them removes the reason to compensate at all. A pool worker
 * that joins runs other pending tasks while it waits, so waiting for a batch <em>is</em> helping to complete it,
 * and no thread is ever idled by the wait. {@code join} carries the same happens-before edge the phaser did:
 * everything a task did is visible to whoever joins it.
 */
public final class TickBatch {
    /** The batch handed out when MCMT is off, on which every operation is a no-op and dispatch runs inline. */
    static final TickBatch INLINE = new TickBatch(false);

    /** Sized for a level's block entities, which is the largest of the three batches by an order of magnitude. */
    private static final int INITIAL_CAPACITY = 512;

    private final boolean parallel;
    private ForkJoinTask<?>[] tasks;
    private int count;

    TickBatch(boolean parallel) {
        this.parallel = parallel;
        this.tasks = parallel ? new ForkJoinTask<?>[INITIAL_CAPACITY] : null;
    }

    /** True when tasks in this batch run inline on the dispatching thread. */
    public boolean isInline() {
        return !this.parallel;
    }

    /**
     * Queues one task on the pool and records it so {@link #await()} can wait for it.
     *
     * <p>The array is grown before the task is queued, so that a task the pool has accepted is always recorded:
     * one that ran but was never recorded would not be waited for, and the loop that dispatched it would carry
     * on while it was still touching the world.
     */
    void dispatch(Runnable body) {
        if (this.count == this.tasks.length) {
            this.tasks = Arrays.copyOf(this.tasks, this.count * 2);
        }
        ForkJoinTask<?> task = ForkJoinTask.adapt(body);
        // If the pool refuses the task it never runs, so it must not be recorded; letting this propagate
        // undispatched is what the callers' catch blocks are for.
        MCMTThreadPool.get().execute(task);
        this.tasks[this.count++] = task;
    }

    /**
     * Runs and waits until every task dispatched into this batch has finished.
     *
     * <p>Quietly, because each task body already catches and reports its own failure — a throw reaching here
     * would mean MCMT's own dispatch broke, and cancelling the rest of the level's ticks in response would do
     * more damage than continuing.
     */
    void await() {
        if (!this.parallel) {
            return;
        }
        for (int i = 0; i < this.count; i++) {
            this.tasks[i].quietlyJoin();
            this.tasks[i] = null;
        }
        this.count = 0;
    }
}
