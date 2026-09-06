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
 * The server owner's own white and black lists, from {@code neoforge-mcmt.toml}.
 *
 * <p>Ranked above every built-in judgement except {@link PistonFilter}'s, so an owner who has found that some
 * class misbehaves — or that some class MCMT is being cautious about is in fact fine — can say so and be
 * obeyed. The whitelist wins over the blacklist, so a broad blacklist can be narrowed by exception.
 *
 * <p>Pistons and sculk are deliberately not overridable this way: whitelisting them does not make them safe, it
 * makes the world corrupt quietly.
 */
public final class ConfigFilter implements SerDesFilter {
    private final SerDesPool chunkLock;

    public ConfigFilter(SerDesPool chunkLock) {
        this.chunkLock = chunkLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        boolean entity = hook == SerDesHookType.ENTITY_TICK;
        if ((entity ? MCMTConfig.entityWhiteList : MCMTConfig.blockEntityWhiteList).contains(type)) {
            return FREE;
        }
        if ((entity ? MCMTConfig.entityBlackList : MCMTConfig.blockEntityBlackList).contains(type)) {
            return this.chunkLock;
        }
        return null;
    }
}
