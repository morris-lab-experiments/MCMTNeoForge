/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.BlockPos;

/**
 * Mutual exclusion scoped to a block and its six face-neighbours.
 *
 * <h2>Why not {@link ChunkLock}</h2>
 *
 * <p>A chunk is far too coarse for a tick that reaches one block. Hoppers are the case that forced this:
 * chunk-locking them is correct but serialises every hopper in a chunk against every other, and a hopper array
 * is precisely the thing people build. Measured, that cost 100x — 20 ticks per second against 1957 with MCMT
 * off — because a radius-1 chunk lock claims nine chunks and the load had 256 hoppers in each.
 *
 * <p>What a hopper actually shares with another hopper is a <em>container</em>, at one block position: the one
 * above it, and the one it faces. Both lie in its six-neighbourhood, so locking that neighbourhood serialises
 * exactly the pairs that can touch the same container and no others. Two hoppers three blocks apart stop
 * waiting for each other.
 *
 * <h2>Stripes</h2>
 *
 * <p>The locks are a fixed table indexed by a hash of the position, not a map keyed on it. A map would grow an
 * entry for every block position ever ticked, which over a running world is unbounded; {@link ChunkLock} gets
 * away with it only because chunk positions are so much rarer. The cost of striping is that two unrelated
 * positions can collide and serialise when they did not need to. That is a small loss of parallelism, never a
 * loss of correctness — and correctness in the other direction is guaranteed, because one position always
 * hashes to one stripe, so two ticks sharing a position always contend.
 *
 * <p>Deadlock is avoided exactly as in {@link ChunkLock}: the stripes are sorted and deduplicated before any is
 * taken, so all callers acquire in the same order.
 */
public final class PosLock {
    /**
     * Power of two, so the index is a mask rather than a modulo.
     *
     * <p>Sized against the shape this exists for. A 64x64 hopper array is 4096 positions; with 4096 stripes
     * that lands on 2611 distinct ones, so a third of the hoppers would share a stripe with another and
     * serialise against it for no reason. 16384 takes the same array to 3627 — collisions become rare
     * enough to stop mattering — for roughly 800 kB of locks per level.
     */
    private static final int STRIPES = 16384;
    private static final int MASK = STRIPES - 1;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public PosLock() {
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * Which stripe a block position falls in.
     *
     * <p>The coordinates are mixed rather than packed-and-masked. A packed {@code BlockPos} carries Y in its
     * low twelve bits, so masking one is very nearly reading Y alone: measured, a 64x64 hopper array laid flat
     * at one height collapses to a <em>single</em> stripe, which is the exact shape this is meant to speed up.
     * This is the finalising mix from MurmurHash3, which spreads all three coordinates across the word.
     */
    private static int stripe(int x, int y, int z) {
        long h = (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xC2B2AE3D27D4EB4FL ^ (long) z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (int) h & MASK;
    }

    /**
     * Locks {@code pos} and its six face-neighbours. Pass the result to {@link #unlock}.
     *
     * <p>Seven positions collapse to at most seven stripes, usually seven distinct ones; duplicates are dropped
     * so a stripe is never taken twice in one call. The lock is reentrant, so a duplicate would be harmless,
     * but {@link #unlock} would then release it once too few.
     */
    public int[] lock(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        int[] stripes = {
                stripe(x, y, z),
                stripe(x - 1, y, z),
                stripe(x + 1, y, z),
                stripe(x, y - 1, z),
                stripe(x, y + 1, z),
                stripe(x, y, z - 1),
                stripe(x, y, z + 1)
        };

        Arrays.sort(stripes);

        // Compact in place, dropping the duplicates the sort has made adjacent.
        int distinct = 0;
        for (int i = 0; i < stripes.length; i++) {
            if (i == 0 || stripes[i] != stripes[i - 1]) {
                stripes[distinct++] = stripes[i];
            }
        }
        int[] held = distinct == stripes.length ? stripes : Arrays.copyOf(stripes, distinct);

        for (int index : held) {
            // A worker that genuinely has to wait must tell the pool, or enough of them waiting at once stalls
            // it. ManagedLock costs nothing when the lock is free, which here is the overwhelming majority.
            ManagedLock.lock(this.locks[index]);
        }
        return held;
    }

    /** Releases what {@link #lock} returned, in reverse order. */
    public void unlock(int[] held) {
        for (int i = held.length - 1; i >= 0; i--) {
            this.locks[held[i]].unlock();
        }
    }
}
