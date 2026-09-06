/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Learns which classes cannot be run in parallel by watching them fail.
 *
 * <p>When a tick throws on a worker, that is usually not a bug in the object's logic — the same code has been
 * running for years single-threaded — but a race MCMT introduced. So rather than crash the server or, worse,
 * keep running the class and corrupting the world, MCMT demotes it: the class is added here and every later
 * tick of it takes a chunk lock. One exception buys permanent safety for that class.
 *
 * <p>This is why a modded server can be brought up under MCMT at all. The alternative is for the owner to
 * discover each offending class by reading crash reports.
 *
 * <p>The demotion is in memory only. {@code /mcmt save} writes the accumulated list into the config blacklist,
 * which is what makes it survive a restart — deliberately a decision the owner makes rather than something
 * MCMT does to their config file behind their back.
 */
public final class AutoFilter implements SerDesFilter {
    private static final Logger LOGGER = LogManager.getLogger();

    private final SerDesPool chunkLock;
    private final Set<Class<?>> demoted = ConcurrentHashMap.newKeySet();

    public AutoFilter(SerDesPool chunkLock) {
        this.chunkLock = chunkLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        return this.demoted.contains(type) ? this.chunkLock : null;
    }

    /**
     * Records that a tick of {@code type} threw, so future ticks of it are chunk-locked.
     *
     * @return true if this class had not already been demoted, i.e. the caller should invalidate caches
     */
    public boolean demote(Class<?> type, Throwable cause) {
        if (!this.demoted.add(type)) {
            return false;
        }
        LOGGER.warn("MCMT: {} threw while ticking in parallel; it will be chunk-locked from now on. "
                + "Use /mcmt save to make this permanent.", type.getName(), cause);
        return true;
    }

    /** The classes demoted so far. */
    public Set<Class<?>> demoted() {
        return this.demoted;
    }
}
