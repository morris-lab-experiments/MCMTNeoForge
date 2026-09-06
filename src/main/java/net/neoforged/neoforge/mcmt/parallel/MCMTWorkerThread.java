/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * A worker of the MCMT tick pool.
 *
 * <p>The only reason this subclass exists is so that "am I a tick worker?" is an {@code instanceof} check rather
 * than a set lookup or a {@link ThreadLocal} read. That question is asked from
 * {@code BlockableEventLoop.isSameThread} and from {@code Level.getProfiler()}, both of which are called several
 * times per entity per tick, so it has to be as close to free as possible.
 *
 * <p>JMT-MCMT used a {@code Map<String, Set<Thread>>} tracker for this. It could not subclass the worker thread
 * because its pool was created from a coremod during class loading, where touching extra classes was unsafe. We
 * have no such constraint.
 */
public final class MCMTWorkerThread extends ForkJoinWorkerThread {
    MCMTWorkerThread(ForkJoinPool pool, String name) {
        super(pool);
        this.setName(name);
    }
}
