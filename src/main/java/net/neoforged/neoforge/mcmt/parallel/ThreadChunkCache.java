/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.Arrays;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A per-worker, single-epoch view of {@code ServerChunkCache}'s recently-used chunk cache.
 *
 * <p>{@code ServerChunkCache} keeps one shared four-slot cache. Under MCMT, 32 workers evict each other's
 * entries on every miss, so it almost never hits and nearly every {@code getChunk} call falls through to the
 * chunk-load lock: a 32-worker profile puts about 72% of engaged worker time on that lock. Give each worker
 * its own slots instead, so a worker's own working set stays cached and a hit skips the lock entirely.
 *
 * <p>An entry stays valid until the next {@code ServerChunkCache.clearCache()}, exactly as a shared-cache
 * entry does. {@code ServerChunkCache} bumps a version counter there, and an entry records the version it was
 * stored at, so the whole cache drops when the counter moves. Only non-null {@code FULL} chunks are cached: a
 * {@code FULL} chunk cannot be unloaded or downgraded during a parallel tick phase, because only
 * {@code processUnloads} removes one and it runs on the server thread between phases and always calls
 * {@code clearCache()} afterwards. So a version-matched hit always names a live chunk.
 *
 * <p>Single-owner: only the worker that owns an instance touches it, so it needs no synchronization.
 */
public final class ThreadChunkCache {
    private static final int SLOTS = 16;

    private final ChunkCacheEntry[] entries = new ChunkCacheEntry[SLOTS];
    private long version = Long.MIN_VALUE;
    private int cursor;

    /**
     * Returns the cached chunk for {@code pos} at {@code status}, or {@code null} on a miss or when this
     * cache was filled in an older epoch than {@code currentVersion}.
     */
    @Nullable
    public ChunkAccess get(long pos, ChunkStatus status, long currentVersion) {
        if (this.version != currentVersion) {
            return null;
        }

        for (ChunkCacheEntry entry : this.entries) {
            if (entry != null && entry.matches(pos, status)) {
                return entry.chunk();
            }
        }

        return null;
    }

    /**
     * Records {@code chunk} for {@code pos} as valid at {@code currentVersion}, ignoring anything but a
     * non-null {@code FULL} chunk. Resets the ring when the epoch has moved on.
     *
     * <p>Pass the version you read <em>before</em> the lookup that produced {@code chunk}, not a freshly read
     * one. If {@code clearCache()} ran while you were resolving the chunk, the stale version makes this store
     * a no-op on the next read rather than publishing an entry into an epoch it was not validated in.
     */
    public void put(long pos, ChunkStatus status, @Nullable ChunkAccess chunk, long currentVersion) {
        if (chunk == null || status != ChunkStatus.FULL) {
            return;
        }

        if (this.version != currentVersion) {
            Arrays.fill(this.entries, null);
            this.version = currentVersion;
            this.cursor = 0;
        }

        this.entries[this.cursor] = new ChunkCacheEntry(pos, status, chunk);
        this.cursor = (this.cursor + 1) % SLOTS;
    }
}
