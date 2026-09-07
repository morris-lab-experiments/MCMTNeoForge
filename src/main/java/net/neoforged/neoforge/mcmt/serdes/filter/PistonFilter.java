/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * The vanilla block entities whose ticks reach outside their own block, and so cannot run freely.
 *
 * <p>This filter sits above {@link VanillaFilter}, which would otherwise wave these through as vanilla code.
 * They are the known-bad list MCMT has accumulated:
 *
 * <ul>
 * <li><b>Pistons.</b> A moving piston rewrites the blocks it is pushing, several chunks over in the worst case,
 * and two pistons pushing into the same space at once corrupt each other's block updates.
 * <li><b>Sculk sensors, shriekers and catalysts.</b> These propagate: a sensor schedules a shrieker, a catalyst
 * spreads veins across neighbouring blocks. The tick's effect is deliberately non-local.
 * </ul>
 *
 * <p>Both get a chunk lock rather than a global one — two pistons in different regions still run at the same
 * time. A chunk is the right scope here precisely because these reach beyond one block: a piston rewrites a
 * line of blocks that can cross into the next chunk, and sculk spreads. Things that reach exactly one block
 * belong in {@link HopperFilter}, on a much cheaper lock.
 */
public final class PistonFilter implements SerDesFilter {
    private final SerDesPool chunkLock;

    public PistonFilter(SerDesPool chunkLock) {
        this.chunkLock = chunkLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook != SerDesHookType.BLOCK_ENTITY_TICK) {
            return null;
        }
        if (PistonMovingBlockEntity.class.isAssignableFrom(type)
                || SculkSensorBlockEntity.class.isAssignableFrom(type)
                || SculkShriekerBlockEntity.class.isAssignableFrom(type)
                || SculkCatalystBlockEntity.class.isAssignableFrom(type)) {
            return this.chunkLock;
        }
        return null;
    }
}
