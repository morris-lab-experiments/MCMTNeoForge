/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.pools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.mcmt.parallel.PosLock;

/**
 * Runs the task holding a lock on its own block and the six around it.
 *
 * <p>The right answer for a tick that reads or writes its immediate neighbours and nothing further — hoppers,
 * and anything else that moves items between adjacent containers. {@link ChunkLockPool} would also be correct
 * for those, and is far too coarse: see {@link PosLock} for the measurement that forced the distinction.
 *
 * <p>Per level, because a block position means nothing across dimensions.
 */
public final class PosLockPool implements SerDesPool {
    private final Map<Level, PosLock> locksByLevel = new ConcurrentHashMap<>();

    @Override
    public void serialise(Runnable task, BlockPos pos, Level level) {
        PosLock lock = this.locksByLevel.computeIfAbsent(level, k -> new PosLock());
        int[] held = lock.lock(pos);
        try {
            task.run();
        } finally {
            lock.unlock(held);
        }
    }

    /** Drops the lock table for a level. Called when the level unloads; unsafe while it is ticking. */
    public void forget(Level level) {
        this.locksByLevel.remove(level);
    }
}
