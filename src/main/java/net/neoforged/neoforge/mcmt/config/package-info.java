/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * Runtime configuration for {@link net.neoforged.neoforge.mcmt multi-core tick processing}.
 *
 * <p>Mirrors JMT-MCMT's {@code GeneralConfig} / {@code SerDesConfig}: a {@link net.neoforged.neoforge.common.ModConfigSpec}
 * backed set of toggles (global disable, per-phase disables, parallelism sizing, block-entity allow/deny lists,
 * tracing/debug options) that can be re-baked at runtime via the {@link net.neoforged.neoforge.mcmt.commands commands}.
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt.config;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
