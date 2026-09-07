/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
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
 * <li><b>Hoppers.</b> A hopper's tick reads and writes a container that is not its own — the one above it and
 * the one it faces — with no synchronisation of any kind. Two hoppers sharing a container both read a slot at
 * <i>n</i>, both take one item, and both write <i>n-1</i>: one item is consumed and two are delivered. This
 * duplicates items, and it is not a rare race. Measured on 4096 hoppers over 24000 ticks, 129024 cobblestone
 * became 429867 — a 3.3x inflation — against an identical run with MCMT off, which conserved them exactly.
 * </ul>
 *
 * <p>All of them get a chunk lock rather than a global one — two pistons in different regions still run at the
 * same time. A radius of one is enough for a hopper, which reaches exactly one block: two hoppers that can
 * touch the same container always have overlapping squares, so they always contend on a shared chunk.
 *
 * <p>Hoppers being on this list rather than running free is expensive — they are the densest block entity in
 * most real worlds, and serialising them by neighbourhood gives up much of the parallelism MCMT exists for.
 * That is the correct trade. The alternative is a server that silently mints items.
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
                || HopperBlockEntity.class.isAssignableFrom(type)
                || SculkSensorBlockEntity.class.isAssignableFrom(type)
                || SculkShriekerBlockEntity.class.isAssignableFrom(type)
                || SculkCatalystBlockEntity.class.isAssignableFrom(type)) {
            return this.chunkLock;
        }
        return null;
    }
}
