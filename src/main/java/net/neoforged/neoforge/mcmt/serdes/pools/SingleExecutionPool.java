/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.pools;

import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.mcmt.parallel.ManagedLock;

/**
 * Runs the task holding one global lock, so no two tasks routed here ever overlap.
 *
 * <p>The blunt instrument, for ticks whose effects are not bounded by position at all — something that walks a
 * global registry, or moves an object between dimensions. Position-scoped locking cannot help there, so the
 * only safe answer is one at a time.
 */
public final class SingleExecutionPool implements SerDesPool {
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void serialise(Runnable task, BlockPos pos, Level level) {
        ManagedLock.lock(this.lock);
        try {
            task.run();
        } finally {
            this.lock.unlock();
        }
    }
}
