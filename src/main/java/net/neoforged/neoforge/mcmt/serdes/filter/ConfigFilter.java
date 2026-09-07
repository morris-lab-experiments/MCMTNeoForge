/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * The server owner's own lists, from {@code neoforge-mcmt.toml}.
 *
 * <p>Ranked above every built-in judgement except {@link PistonFilter}'s, so an owner who has found that some
 * class misbehaves — or that some class MCMT is being cautious about is in fact fine — can say so and be
 * obeyed. Three lists, consulted in this order:
 *
 * <ol>
 * <li>the whitelist, which runs the class free;
 * <li>the single-thread list, which allows no two of its members to overlap anywhere;
 * <li>the blacklist, which chunk-locks.
 * </ol>
 *
 * <p>The whitelist is checked first so it can narrow either of the others by exception: an owner can
 * blacklist {@code com.example.**} and then whitelist back the handful of its classes that are known fine.
 * Between the other two the stricter wins, because a class named on both is being described by an owner who
 * is unsure, and the safe reading of "unsure" is the stronger constraint.
 *
 * <p>Pistons and sculk are deliberately not overridable this way: whitelisting them does not make them safe, it
 * makes the world corrupt quietly.
 */
public final class ConfigFilter implements SerDesFilter {
    private final SerDesPool chunkLock;
    private final SerDesPool singleThread;

    public ConfigFilter(SerDesPool chunkLock, SerDesPool singleThread) {
        this.chunkLock = chunkLock;
        this.singleThread = singleThread;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        boolean entity = hook == SerDesHookType.ENTITY_TICK;
        if ((entity ? MCMTConfig.entityWhiteList : MCMTConfig.blockEntityWhiteList).matches(type)) {
            return FREE;
        }
        if ((entity ? MCMTConfig.entitySingleThreadList : MCMTConfig.blockEntitySingleThreadList).matches(type)) {
            return this.singleThread;
        }
        if ((entity ? MCMTConfig.entityBlackList : MCMTConfig.blockEntityBlackList).matches(type)) {
            return this.chunkLock;
        }
        return null;
    }
}
