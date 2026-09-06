/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.CrashReportCallables;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.mcmt.commands.MCMTCommand;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;
import org.jetbrains.annotations.ApiStatus;

/**
 * Wires multi-core tick processing into NeoForge's own startup, from the {@code NeoForgeMod} constructor.
 *
 * <p>MCMT is not a mod — it is part of NeoForge here — so it has no {@code @Mod} class of its own and instead
 * borrows NeoForge's mod container for its config and NeoForge's event bus for its listeners.
 */
@ApiStatus.Internal
public final class MCMTBootstrap {
    private MCMTBootstrap() {}

    /** Called once from the {@code NeoForgeMod} constructor. */
    public static void init(ModContainer container, IEventBus modEventBus) {
        // COMMON rather than SERVER: the worker pool is a JVM-level resource that must be sized before any
        // server starts, and these settings belong to the installation rather than to a particular world.
        container.registerConfig(ModConfig.Type.COMMON, MCMTConfig.SPEC, "neoforge-mcmt.toml");
        modEventBus.register(MCMTConfig.class);

        CrashReportCallables.registerCrashCallable("MCMT", MCMT::populateCrashReport);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MCMTCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> MCMTThreadPool.shutdown());
    }
}
