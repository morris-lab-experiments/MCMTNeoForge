/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.pools;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * A way of running a tick that cannot simply be let loose on a worker.
 *
 * <p>A pool is the "how" of re-serialisation, a {@link net.neoforged.neoforge.mcmt.serdes.filter.SerDesFilter
 * filter} is the "which". Filters decide that pistons need serialising; a pool decides that serialising means
 * taking a lock on the surrounding chunks.
 *
 * <p>Implementations are shared between all levels and all workers, so they must be thread-safe.
 */
public interface SerDesPool {
    /**
     * Runs {@code task}, applying whatever constraint this pool exists to impose.
     *
     * <p>The task may run on the calling thread (most pools), or be deferred to the end of the tick
     * ({@link PostExecutePool}). It must not be dropped.
     *
     * @param pos where in the world the ticking object sits, for pools that scope their constraint by position
     */
    void serialise(Runnable task, BlockPos pos, Level level);
}
