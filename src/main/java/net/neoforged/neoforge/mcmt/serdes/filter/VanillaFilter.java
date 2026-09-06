/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * Lets vanilla classes run unconstrained.
 *
 * <p>The bet MCMT makes: vanilla tick code is a known, finite body of work that has been run in parallel by
 * this design for years, and the handful of vanilla classes that do not tolerate it are named explicitly in
 * {@link PistonFilter} above. Everything else vanilla is allowed to run free.
 *
 * <p>Modded classes get no such assumption — they fall through to
 * {@link net.neoforged.neoforge.mcmt.serdes.SerDesRegistry}'s default, which chunk-locks them unless the
 * server owner says otherwise.
 *
 * <p>The test is a package-name prefix rather than a classloader or registry lookup because it is cheap and
 * happens once per class. A mod that puts classes under {@code net.minecraft} would be misjudged, but a mod
 * doing that has larger problems.
 */
public final class VanillaFilter implements SerDesFilter {
    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        return type.getName().startsWith("net.minecraft.") ? FREE : null;
    }
}
