/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;

/**
 * The last word: what happens to a class nothing else had an opinion about.
 *
 * <p>By the time a class reaches here it is not vanilla, not configured either way, and has not yet thrown. In
 * other words it is modded code of unknown thread-safety. With {@code chunkLockModded} on — the default — it
 * gets a chunk lock, which is slower than running free but cannot corrupt anything. An owner who has satisfied
 * themselves that their modpack is clean can turn that off and take the throughput.
 */
public final class DefaultFilter implements SerDesFilter {
    private final SerDesPool chunkLock;

    public DefaultFilter(SerDesPool chunkLock) {
        this.chunkLock = chunkLock;
    }

    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        return MCMTConfig.chunkLockModded ? this.chunkLock : FREE;
    }
}
