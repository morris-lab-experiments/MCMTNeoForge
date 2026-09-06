/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The kinds of tick that can be re-serialised. A filter is only ever consulted for classes belonging to its
 * hook's supertype, which keeps the entity and block-entity lookups from interfering with each other.
 */
public enum SerDesHookType {
    /** One entity's tick, from {@code ServerLevel.tick}. */
    ENTITY_TICK(Entity.class),
    /** One block entity's tick, from {@code Level.tickBlockEntities}. */
    BLOCK_ENTITY_TICK(BlockEntity.class);

    private final Class<?> supertype;

    SerDesHookType(Class<?> supertype) {
        this.supertype = supertype;
    }

    /** The common supertype of everything this hook ticks. */
    public Class<?> supertype() {
        return this.supertype;
    }

    /** Whether a filter targeting {@code type} is relevant to this hook at all. */
    public boolean targets(Class<?> type) {
        return this.supertype.isAssignableFrom(type);
    }
}
