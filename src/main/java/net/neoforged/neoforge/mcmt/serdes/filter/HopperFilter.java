/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * The things that move items between adjacent containers, and so must not run beside each other.
 *
 * <p>A hopper's tick reads and writes a container that is not its own: the one above it, which it pulls from,
 * and the one it faces, which it pushes into. None of that is synchronised. Two hoppers sharing a container
 * both read a slot at <i>n</i>, both take one item, and both write <i>n-1</i> — one item consumed, two
 * delivered. It duplicates items, and not rarely: 4096 hoppers over 24000 ticks turned 129024 cobblestone
 * into 429867, against an identical run with MCMT off that conserved them exactly.
 *
 * <p>{@link VanillaFilter} would otherwise wave these through as vanilla code, on the argument that vanilla's
 * unsafe classes are named explicitly. Hoppers were missing from that list, which is how the bug survived five
 * phases of gametests, soaks and benchmarks — none of which ever counted the items.
 *
 * <p>{@code MinecartHopper} is here too, and for the same reason rather than an analogous one: it calls the
 * same {@code HopperBlockEntity.suckInItems} and {@code addItem} statics that the block does.
 *
 * <p>The pool is a block-position lock, not a chunk lock. Both are correct; the chunk lock costs about a
 * hundred times more on a hopper array, because it claims nine chunks for a tick that reaches one block. See
 * {@link net.neoforged.neoforge.mcmt.parallel.PosLock}.
 */
public final class HopperFilter implements SerDesFilter {
    private final SerDesPool posLock;

    public HopperFilter(SerDesPool posLock) {
        this.posLock = posLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook == SerDesHookType.BLOCK_ENTITY_TICK && HopperBlockEntity.class.isAssignableFrom(type)) {
            return this.posLock;
        }
        if (hook == SerDesHookType.ENTITY_TICK && MinecartHopper.class.isAssignableFrom(type)) {
            return this.posLock;
        }
        return null;
    }
}
