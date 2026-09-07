/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * Item entities, which merge with each other and so must not tick beside a neighbour.
 *
 * <p>An {@code ItemEntity} tick calls {@code mergeWithNeighbours}: it scans a half-block box for other item
 * entities of the same kind and folds them into itself — one stack grows, the other is discarded. Two workers
 * ticking both halves of that pair at once each read both counts, each compute the merged total, and one of the
 * two writes is lost, along with the entity discarded to make room for it. The divergence harness measured it at
 * roughly -1 gold ingot per 200 ticks against an MCMT-off control that conserved them exactly.
 *
 * <p>{@link VanillaFilter} would otherwise wave item entities through as vanilla code. They were missing from
 * the explicit list for the same reason hoppers were: the list was assembled from what crashed, and a lost
 * merge does not crash.
 *
 * <p>The pool is a block-position lock. Two item entities merge only when they are within half a block of each
 * other, so their block positions differ by at most one on any axis and their locked plus-shapes always share a
 * stripe — whichever way they are offset, including diagonally. So the pair that can merge always contends and
 * a pair three blocks apart never does. Whole-server serialisation ({@code EntityFilter}'s pool) would also be
 * correct and is far more than this needs.
 *
 * <p>The lock table is the same one {@code MCMT.guardItemPickup} uses for mob pickup, so a merge and a pickup
 * that touch one item serialise against each other as well.
 */
public final class ItemEntityFilter implements SerDesFilter {
    private final SerDesPool posLock;

    public ItemEntityFilter(SerDesPool posLock) {
        this.posLock = posLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook == SerDesHookType.ENTITY_TICK && ItemEntity.class.isAssignableFrom(type)) {
            return this.posLock;
        }
        return null;
    }
}
