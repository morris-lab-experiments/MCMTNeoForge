/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Position-scoped mutual exclusion: a tick can claim the square of chunks around itself for its duration.
 *
 * <p>This is how MCMT serialises the ticks that cannot safely run together without serialising everything. A
 * piston tick mutates blocks around itself, so two pistons within reach of each other must not tick at once —
 * but two pistons a thousand blocks apart never interact, and there is no reason to make one wait for the
 * other. Locking a radius of chunks expresses exactly that.
 *
 * <p>Deadlock is avoided by always taking the locks in ascending order of packed chunk position. Two callers
 * with overlapping squares therefore contend on their lowest shared chunk first, and one of them wins outright
 * rather than each holding part of what the other needs. Any total order would do; the packed long is simply
 * the cheapest one available.
 *
 * <p>Ported from JMT-MCMT, with the neighbour positions computed properly rather than by adding packed longs
 * (which carries across the x/z boundary in the packing and quietly locks the wrong chunks).
 */
public final class ChunkLock {
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Locks the square of chunks of the given radius around {@code pos}. Pass the result to {@link #unlock}. */
    public long[] lock(BlockPos pos, int radius) {
        return this.lock(new ChunkPos(pos), radius);
    }

    /** Locks the square of chunks of the given radius around {@code centre}. Pass the result to {@link #unlock}. */
    public long[] lock(ChunkPos centre, int radius) {
        int side = 1 + radius * 2;
        long[] targets = new long[side * side];
        int next = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                targets[next++] = ChunkPos.asLong(centre.x + dx, centre.z + dz);
            }
        }

        // Consistent acquisition order is the whole deadlock argument; see the class javadoc.
        Arrays.sort(targets);
        for (long target : targets) {
            ReentrantLock lock = this.locks.computeIfAbsent(target, k -> new ReentrantLock());
            // A worker blocked on a chunk lock must tell the pool, or enough of them blocking at once stalls it.
            ManagedLock.lock(lock);
        }
        return targets;
    }

    /** Releases what {@link #lock} returned, in reverse order. */
    public void unlock(long[] held) {
        for (int i = held.length - 1; i >= 0; i--) {
            ReentrantLock lock = this.locks.get(held[i]);
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /**
     * Drops every lock object. Only safe between ticks, with nothing holding a lock — it is a way to stop the
     * map growing without bound as a world is explored, not a way to break a stuck lock.
     */
    public void clear() {
        this.locks.clear();
    }

    /** How many chunk positions currently have a lock object. Diagnostic only. */
    public int size() {
        return this.locks.size();
    }
}
