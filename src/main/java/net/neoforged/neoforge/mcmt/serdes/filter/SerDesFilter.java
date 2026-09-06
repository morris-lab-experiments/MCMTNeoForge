/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * Decides how a particular class of ticking object should be run.
 *
 * <p>Filters are consulted in priority order the first time a class is seen and the answer is cached, so this
 * is off the hot path — a filter may be as expensive as it needs to be.
 *
 * <p>Returning {@code null} from {@link #poolFor} means "no opinion, ask the next filter". A filter that wants
 * a class to run with no constraint at all returns {@link #FREE}, which ends the search.
 */
public interface SerDesFilter {
    /**
     * Marker meaning "run this unconstrained". Distinct from {@code null}, which means "no opinion". The
     * registry recognises it by identity and never calls it.
     */
    SerDesPool FREE = (task, pos, level) -> task.run();

    /**
     * How this filter wants instances of {@code type} run under {@code hook}, or {@code null} to defer to the
     * next filter.
     */
    @Nullable
    SerDesPool poolFor(SerDesHookType hook, Class<?> type);
}
