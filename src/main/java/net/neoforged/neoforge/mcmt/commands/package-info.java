/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * In-game commands for {@link net.neoforged.neoforge.mcmt multi-core tick processing}.
 *
 * <p>Mirrors JMT-MCMT's {@code commands} package: reading and toggling the {@link net.neoforged.neoforge.mcmt.config
 * configuration} at runtime, plus stats / perf / debug output for the parallel tick phases.
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt.commands;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
