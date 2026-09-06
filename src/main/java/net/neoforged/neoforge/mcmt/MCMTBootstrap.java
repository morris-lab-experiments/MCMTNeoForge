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
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.mcmt.commands.MCMTCommand;
import net.neoforged.neoforge.mcmt.config.MCMTConfig;
import net.neoforged.neoforge.mcmt.parallel.MCMTThreadPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Wires multi-core tick processing into NeoForge's own startup, from the {@code NeoForgeMod} constructor.
 *
 * <p>MCMT is not a mod — it is part of NeoForge here — so it has no {@code @Mod} class of its own and instead
 * borrows NeoForge's mod container for its config and NeoForge's event bus for its listeners.
 */
@ApiStatus.Internal
public final class MCMTBootstrap {
    private static final Logger LOGGER = LogManager.getLogger();

    private MCMTBootstrap() {}

    /** Called once from the {@code NeoForgeMod} constructor. */
    public static void init(ModContainer container, IEventBus modEventBus) {
        // COMMON rather than SERVER: the worker pool is a JVM-level resource that must be sized before any
        // server starts, and these settings belong to the installation rather than to a particular world.
        container.registerConfig(ModConfig.Type.COMMON, MCMTConfig.SPEC, "neoforge-mcmt.toml");
        modEventBus.register(MCMTConfig.class);

        CrashReportCallables.registerCrashCallable("MCMT", MCMT::populateCrashReport);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MCMTCommand.register(event.getDispatcher()));

        // Build the pool before the first tick rather than lazily inside one: creating a ForkJoinPool and
        // starting its workers mid-tick would show up as a one-off stall on the very first tick.
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            if (!MCMTConfig.disabled) {
                MCMTThreadPool.get();
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            // One line saying what actually got parallelised. Without it the only way to tell a working hook
            // from a silently inert one is to run /mcmt stats before shutting down, which is no use at all for
            // an automated run such as the game tests.
            if (MCMT.getDispatchedLevelTicks() > 0) {
                LOGGER.info("MCMT dispatched {} level, {} entity, {} block entity and {} chunk ticks this session",
                        MCMT.getDispatchedLevelTicks(),
                        MCMT.getDispatchedEntityTicks(),
                        MCMT.getDispatchedBlockEntityTicks(),
                        MCMT.getDispatchedChunkTicks());
            }
            MCMTThreadPool.shutdown();
        });
    }
}
