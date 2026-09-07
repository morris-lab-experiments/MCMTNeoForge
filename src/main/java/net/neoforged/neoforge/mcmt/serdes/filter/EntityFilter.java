/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * The vanilla entities that cannot tick concurrently, and why.
 *
 * <p>The entity counterpart of {@link PistonFilter}, and like it, it outranks {@link VanillaFilter}. The list
 * comes from MCMTFabric, which arrived at it the hard way:
 *
 * <ul>
 * <li><b>Falling blocks.</b> A falling block's tick ends by turning itself back into a block, so it is a
 * world mutation disguised as an entity, and two landing in the same place race for the position.
 * <li><b>Primed TNT.</b> Detonation rewrites a sphere of blocks and pushes every entity in range, so its
 * effect is not bounded by anything a chunk lock could scope.
 * <li><b>Allays.</b> They coordinate: allays track each other, duplicate, and hand items between themselves,
 * so their ticks read and write each other's state.
 * </ul>
 *
 * <p>Those three go to the single-execution pool rather than a chunk lock. The point is not that they touch
 * their neighbourhood — a chunk lock would handle that — but that their effects reach further than a position
 * can describe.
 *
 * <p>Hopper minecarts are here too, but chunk-locked rather than single-executed. They call the same
 * {@code HopperBlockEntity.suckInItems} and {@code addItem} statics a hopper block does, so they duplicate
 * items the same way and for the same reason — see {@link PistonFilter} — and like a hopper they reach exactly
 * one block, so a chunk lock is the right scope.
 *
 * <p>Projectiles and entities mid-portal are also serialised, but per instance rather than per class, so that
 * lives in {@code MCMT.callEntityTick} rather than here.
 */
public final class EntityFilter implements SerDesFilter {
    private final SerDesPool singleExecution;
    private final SerDesPool chunkLock;

    public EntityFilter(SerDesPool singleExecution, SerDesPool chunkLock) {
        this.singleExecution = singleExecution;
        this.chunkLock = chunkLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook != SerDesHookType.ENTITY_TICK) {
            return null;
        }
        if (FallingBlockEntity.class.isAssignableFrom(type)
                || PrimedTnt.class.isAssignableFrom(type)
                || Allay.class.isAssignableFrom(type)) {
            return this.singleExecution;
        }
        if (MinecartHopper.class.isAssignableFrom(type)) {
            return this.chunkLock;
        }
        return null;
    }
}
