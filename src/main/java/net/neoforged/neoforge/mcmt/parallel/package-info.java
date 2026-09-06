/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * Concurrency primitives and data structures backing {@link net.neoforged.neoforge.mcmt multi-core tick processing}.
 *
 * <p>Mirrors JMT-MCMT's {@code paralelised} package: the worker thread pool and its thread tracker, concurrent
 * replacements for the non-thread-safe collections Minecraft uses on hot tick paths (fastutil maps/sets, linked
 * lists), chunk locking, and the parallel chunk-cache access helpers.
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt.parallel;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
