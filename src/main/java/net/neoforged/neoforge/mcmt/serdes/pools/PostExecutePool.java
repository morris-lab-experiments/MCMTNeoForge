/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.pools;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Defers the task to the end of the server tick, where it runs on the server thread with nothing else in flight.
 *
 * <p>For work that genuinely cannot happen concurrently with anything — structural changes to the world's own
 * bookkeeping rather than to blocks. Deferring changes <em>when</em> the tick happens, not just how it is
 * ordered, so this is a last resort rather than a default.
 *
 * <p>The queue is drained by {@code MCMT.postTick} after the tick barrier.
 */
public final class PostExecutePool implements SerDesPool {
    private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

    @Override
    public void serialise(Runnable task, BlockPos pos, Level level) {
        this.queued.add(task);
    }

    /**
     * Runs everything queued this tick, on the calling thread. Tasks queued by these tasks run too, so a task
     * that re-queues itself will spin — that is a bug in the task, not something to defend against here.
     */
    public void drain() {
        Runnable task;
        while ((task = this.queued.poll()) != null) {
            task.run();
        }
    }

    /** How many tasks are waiting. Diagnostic only. */
    public int pending() {
        return this.queued.size();
    }
}
