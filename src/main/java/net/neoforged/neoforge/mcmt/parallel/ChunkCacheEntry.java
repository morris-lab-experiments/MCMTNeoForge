/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One slot of {@code ServerChunkCache}'s recently-used chunk cache.
 *
 * <p>Vanilla keeps that cache as three parallel arrays — position, status, chunk — which is fine on a single
 * thread and unsafe on several. The failure is worse than a crash: a reader can match on a position that has
 * already been overwritten and come away with a <em>different</em> chunk than it asked for, then read and write
 * blocks in it. Silent world corruption.
 *
 * <p>Folding the three into one immutable object makes each slot a single reference, so a reader either sees a
 * whole entry or a whole other entry, never half of each. Slots then live in an {@code AtomicReferenceArray}.
 * Two writers can still interleave and lose an entry, but a lost cache entry only costs a lookup — the next
 * read falls through to {@code getVisibleChunkIfPresent}, which is already safe off-thread.
 */
public record ChunkCacheEntry(long pos, ChunkStatus status, @Nullable ChunkAccess chunk) {
    /** True when this entry answers a lookup for {@code pos} at {@code status}. */
    public boolean matches(long pos, ChunkStatus status) {
        return this.pos == pos && this.status == status;
    }
}
