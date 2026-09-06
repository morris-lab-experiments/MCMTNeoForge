/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.pools;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.mcmt.parallel.ChunkLock;

/**
 * Runs the task holding a lock on the square of chunks around it.
 *
 * <p>The default answer for anything that mutates the world near itself. Two such ticks close together are
 * serialised; two far apart still run at the same time, which is the whole point.
 *
 * <p>The lock is per level, because a chunk position means nothing across dimensions.
 */
public final class ChunkLockPool implements SerDesPool {
    private final java.util.Map<Level, ChunkLock> locksByLevel = new java.util.concurrent.ConcurrentHashMap<>();
    private final int radius;

    /**
     * @param radius how many chunks out from the ticking object to claim. One is enough for anything that
     *               reaches into its immediate neighbours, which covers pistons and most machinery.
     */
    public ChunkLockPool(int radius) {
        this.radius = radius;
    }

    @Override
    public void serialise(Runnable task, BlockPos pos, Level level) {
        ChunkLock lock = this.locksByLevel.computeIfAbsent(level, k -> new ChunkLock());
        long[] held = lock.lock(pos, this.radius);
        try {
            task.run();
        } finally {
            lock.unlock(held);
        }
    }

    /** Drops the lock objects for a level. Called when the level unloads; unsafe while it is ticking. */
    public void forget(Level level) {
        ChunkLock lock = this.locksByLevel.remove(level);
        if (lock != null) {
            lock.clear();
        }
    }
}
