/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
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
 * <p>These go to the single-execution pool rather than a chunk lock. The point is not that they touch
 * their neighbourhood — a chunk lock would handle that — but that their effects reach further than a position
 * can describe.
 *
 * <p>Hopper minecarts duplicate items the same way a hopper block does, but they are handled by
 * {@link HopperFilter} rather than here, because what they need is a block-position lock rather than
 * whole-server serialisation.
 *
 * <p>Projectiles and entities mid-portal are also serialised, but per instance rather than per class, so that
 * lives in {@code MCMT.callEntityTick} rather than here.
 */
public final class EntityFilter implements SerDesFilter {
    private final SerDesPool singleExecution;

    public EntityFilter(SerDesPool singleExecution) {
        this.singleExecution = singleExecution;
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
        return null;
    }
}
