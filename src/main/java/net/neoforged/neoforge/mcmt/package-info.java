/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * Multi-core tick processing (MCMT) for NeoForge.
 *
 * <p>Ports the design of <a href="https://github.com/jediminer543/JMT-MCMT">JMT-MCMT</a> into NeoForge: the
 * server world / entity / block-entity / environment tick loops are parallelised across a worker pool, with the
 * hooks patched directly into the Minecraft sources under {@code projects/neoforge/src/main/java} rather than
 * applied as an ASM coremod.
 *
 * <p>This package holds the coordination entry points (thread pool, per-tick {@code Phaser}, dispatch of the
 * individual tick tasks). Supporting code lives in the sub-packages:
 * <ul>
 * <li>{@link net.neoforged.neoforge.mcmt.config} &mdash; runtime configuration</li>
 * <li>{@link net.neoforged.neoforge.mcmt.parallel} &mdash; concurrent collections and chunk-access helpers</li>
 * <li>{@link net.neoforged.neoforge.mcmt.serdes} &mdash; serialise/deserialise filters for tasks that cannot run fully concurrently</li>
 * <li>{@link net.neoforged.neoforge.mcmt.commands} &mdash; in-game configuration and diagnostic commands</li>
 * </ul>
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
