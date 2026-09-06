/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * Serialise/deserialise ("SerDes") framework for {@link net.neoforged.neoforge.mcmt multi-core tick processing}.
 *
 * <p>Mirrors JMT-MCMT's {@code serdes} package: some entity and block-entity ticks (pistons, sculk, and other
 * position-coupled behaviour) cannot run fully concurrently. Filters match those types and route their execution
 * through a pool (chunk-lock, single-execution, post-execute) that re-serialises just enough of the work to stay
 * correct while the rest of the tick stays parallel.
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt.serdes;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
