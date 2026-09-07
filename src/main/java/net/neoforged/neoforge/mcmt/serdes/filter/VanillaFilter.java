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
 * Decides what happens to a vanilla class that none of the filters above had an opinion about.
 *
 * <h2>The bet this represents, and its record</h2>
 *
 * <p>MCMT's original position — and JMT-MCMT's before it — was that vanilla tick code is a known, finite body
 * of work, that the handful of classes which cannot run in parallel can be named explicitly, and that
 * everything else may run free. {@link PistonFilter}, {@link HopperFilter} and {@link EntityFilter} are that
 * list.
 *
 * <p>The bet has been wrong three times. Hoppers duplicated items at 3.3x; item-entity merging lost them; mob
 * loot pickup duplicated them. Each was a read-modify-write across two objects that vanilla only ever ran on
 * one thread, and each was waved through here for living under {@code net.minecraft.}.
 *
 * <p>What matters about those three is not that the list was incomplete — any list can be — but <em>how</em> it
 * was incomplete. It had been assembled from crash reports, and none of these three ever crashed. A thread
 * safety bug that throws announces itself the first time it happens; one that miscounts an item does not, and
 * a soak, a gametest suite and a benchmark all ran for five phases without noticing.
 *
 * <p>So the answer is now a setting rather than an assumption: {@link MCMTConfig#vanillaDefault}. It is still
 * {@code FREE} by default, because locking every vanilla tick costs throughput that MCMT exists to provide —
 * but an owner who would rather be slow than wrong can say so, and the cost of saying so is measurable.
 */
public final class VanillaFilter implements SerDesFilter {
    private final SerDesPool posLock;
    private final SerDesPool chunkLock;

    public VanillaFilter(SerDesPool posLock, SerDesPool chunkLock) {
        this.posLock = posLock;
        this.chunkLock = chunkLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        // A package-name prefix rather than a classloader or registry lookup: it is cheap, and it happens
        // once per class because the registry caches the answer. A mod that puts classes under
        // net.minecraft would be misjudged, but a mod doing that has larger problems.
        if (!type.getName().startsWith("net.minecraft.")) {
            return null;
        }
        return switch (MCMTConfig.vanillaDefault) {
            case FREE -> FREE;
            case POS_LOCK -> this.posLock;
            case CHUNK_LOCK -> this.chunkLock;
        };
    }
}
