/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Lock;

/**
 * Acquires a {@link Lock} from a {@link ForkJoinPool} worker without paying for a compensation thread when the
 * lock is free.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The obvious way to take a lock from a pool worker is to wrap {@code lock::lock} in a {@code ManagedBlocker}
 * built from a {@code Runnable}, and MCMT did exactly that in three places. It is wrong, in a way that does not
 * show up as contention and so survived a profiling pass that specifically went looking for contention.
 *
 * <p>{@link ForkJoinPool#managedBlock} asks {@link ForkJoinPool.ManagedBlocker#isReleasable()} first, and if the
 * answer is no it <em>starts a replacement thread before calling {@code block()}</em>. A blocker wrapping an
 * opaque {@code Runnable} has nothing to answer with, so it says no every time — and the pool grows a thread on
 * every single acquisition, whether or not the lock was ever going to block. On a heavy world the chunk-load
 * lock is taken on every cache miss, which is hot and almost never contended: 37% of all worker samples sat in
 * this call, and the pool reached ~1500 threads against a target of 32.
 *
 * <p>So the fix is not to lock less. It is to answer the question the pool is actually asking. {@code tryLock}
 * in {@code isReleasable} means an uncontended acquisition never reaches {@code block()}, and the pool never
 * compensates for it; only a genuinely contended one does, which is what the mechanism is for.
 *
 * <p>This is the idiom {@code ManagedBlocker}'s own javadoc gives for locks. MCMT reached for the generic
 * wrapper instead, and the generic wrapper cannot be correct here.
 */
public final class ManagedLock implements ForkJoinPool.ManagedBlocker {
    private final Lock lock;
    private boolean held;

    private ManagedLock(Lock lock) {
        this.lock = lock;
    }

    /**
     * Takes {@code lock}, telling the pool only if it actually has to wait.
     *
     * <p>Outside a pool worker {@link ForkJoinPool#managedBlock} just runs the blocker directly, so this stays
     * correct — and no more expensive than {@code lock.lock()} — on the server thread too.
     */
    public static void lock(Lock lock) {
        try {
            ForkJoinPool.managedBlock(new ManagedLock(lock));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring a tick lock", e);
        }
    }

    @Override
    public boolean isReleasable() {
        return this.held || (this.held = this.lock.tryLock());
    }

    @Override
    public boolean block() {
        if (!this.held) {
            this.lock.lock();
            this.held = true;
        }
        return true;
    }
}
