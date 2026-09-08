/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.config;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Configuration for multi-core tick processing, mirroring JMT-MCMT's {@code GeneralConfig}.
 *
 * <p>There are two copies of every setting: the {@link ModConfigSpec} values (the on-disk config, reachable via
 * {@link #SPEC_VALUES}) and the plain {@code static} fields on this class (the "baked" copy). The tick hooks read
 * only the baked fields, because they sit on the hottest paths in the game and a {@code ConfigValue.get()} call
 * per entity per tick is far too expensive. {@link #bake()} copies spec to baked; {@link #save()} copies back.
 *
 * <p>Every setting is runtime-toggleable through {@code /mcmt config}, which writes the baked field directly.
 * Such a change is lost on restart unless {@code /mcmt save} is used to push it back into the config file.
 *
 * <p>This is a {@code COMMON} config rather than {@code SERVER}: the worker pool is a JVM-level resource that has
 * to be sized before any server starts, and these settings are a property of the installation rather than of a
 * particular world.
 */
public final class MCMTConfig {
    private static final Logger LOGGER = LogManager.getLogger();

    private MCMTConfig() {}

    /**
     * What happens to a vanilla class that no filter has an opinion about.
     *
     * <p>MCMT's original bet was {@link #FREE}: vanilla is a known, finite body of code, and the handful of
     * classes that cannot run in parallel are named explicitly. That bet has now been wrong three times —
     * hoppers, item merging and mob loot pickup all duplicated or destroyed items — and each time the failure
     * was silent, because the list of unsafe classes was assembled from what crashed rather than from what
     * quietly diverged.
     *
     * <p>So the default is a dial rather than an assumption. Locking costs throughput; being wrong costs the
     * world.
     */
    public enum VanillaDefault {
        /** Unlisted vanilla runs unconstrained. Fastest, and what MCMT and JMT-MCMT have always done. */
        FREE,
        /** Unlisted vanilla locks its own block and the six around it. Covers a tick that reaches one block. */
        POS_LOCK,
        /** Unlisted vanilla locks the square of chunks around it. Covers a tick that reaches further. */
        CHUNK_LOCK
    }

    /** How {@link #paraMax} is interpreted when sizing the worker pool. */
    public enum ParaMaxMode {
        /** {@code paraMax} is an upper bound, clamped to the available processor count. */
        STANDARD,
        /** {@code paraMax} is used verbatim, even above the available processor count. */
        OVERRIDE,
        /** {@code paraMax} is subtracted from the available processor count. */
        REDUCTION
    }

    // Baked values. Read by the tick hooks; written by bake() and by /mcmt config.
    // ---------------------------------------------------------------------------

    /** Master switch. When true every hook runs its task inline on the calling thread, exactly like vanilla. */
    public static boolean disabled;

    /** Requested worker count, interpreted according to {@link #paraMaxMode}. Zero or one means "all processors". */
    public static int paraMax;

    /** How {@link #paraMax} is interpreted. */
    public static ParaMaxMode paraMaxMode;

    /**
     * Hard ceiling on the total worker-thread count, compensation threads included. Zero means "the same as the
     * parallelism target". See {@link #getMaxPoolSize()}.
     */
    public static int poolMaxThreads;

    /** Disables parallel dispatch of the per-{@code ServerLevel} tick (hook H1). */
    public static boolean disableWorld;

    /** Disables parallel dispatch of entity ticks (hook H2). */
    public static boolean disableEntity;

    /** Disables parallel dispatch of block-entity ticks (hook H3). */
    public static boolean disableBlockEntity;

    /** Disables parallel dispatch of chunk/environment ticks (hook H4). */
    public static boolean disableEnvironment;

    /** Disables the concurrent chunk-cache fast path (hook H5). */
    public static boolean disableChunkProvider;

    /** When true, block entities whose class is not part of vanilla are chunk-locked rather than run freely. */
    public static boolean chunkLockModded;

    /** What an unlisted vanilla class gets. See {@link VanillaDefault}. */
    public static VanillaDefault vanillaDefault;

    /** Block-entity classes that are always chunk-locked. Resolved from {@link Template#blockEntityBlackList}. */
    public static final ClassMatcher blockEntityBlackList = new ClassMatcher();

    /** Block-entity classes that are never chunk-locked; overrides every other list and {@link #chunkLockModded}. */
    public static final ClassMatcher blockEntityWhiteList = new ClassMatcher();

    /** Block-entity classes that may not run concurrently with each other at all, wherever they are. */
    public static final ClassMatcher blockEntitySingleThreadList = new ClassMatcher();

    /** Entity classes that are always chunk-locked. */
    public static final ClassMatcher entityBlackList = new ClassMatcher();

    /** Entity classes that are never chunk-locked. */
    public static final ClassMatcher entityWhiteList = new ClassMatcher();

    /** Entity classes that may not run concurrently with each other at all, wherever they are. */
    public static final ClassMatcher entitySingleThreadList = new ClassMatcher();

    /**
     * When true, every dispatched task registers a human-readable name in {@code MCMT.currentTasks} for the
     * duration of its run, so a crash report can list what was in flight. Costs a string concatenation and two
     * concurrent-set operations per task, so it is off by default.
     */
    public static boolean opsTracing;

    // Spec
    // ----

    /** The on-disk mirror of the settings above. */
    public static final class Template {
        public final BooleanValue disabled;
        public final IntValue paraMax;
        public final EnumValue<ParaMaxMode> paraMaxMode;
        public final IntValue poolMaxThreads;
        public final BooleanValue disableWorld;
        public final BooleanValue disableEntity;
        public final BooleanValue disableBlockEntity;
        public final BooleanValue disableEnvironment;
        public final BooleanValue disableChunkProvider;
        public final BooleanValue chunkLockModded;
        public final EnumValue<VanillaDefault> vanillaDefault;
        public final ConfigValue<List<? extends String>> blockEntityBlackList;
        public final ConfigValue<List<? extends String>> blockEntityWhiteList;
        public final ConfigValue<List<? extends String>> blockEntitySingleThreadList;
        public final ConfigValue<List<? extends String>> entityBlackList;
        public final ConfigValue<List<? extends String>> entityWhiteList;
        public final ConfigValue<List<? extends String>> entitySingleThreadList;
        public final BooleanValue opsTracing;

        Template(ModConfigSpec.Builder builder) {
            builder.comment("Multi-core tick processing (MCMT).",
                    "Parallelising the server tick trades determinism and stability for throughput.",
                    "Every value here can also be changed at runtime with /mcmt config <key> <value>.")
                    .push("general");
            disabled = builder
                    .comment("Master switch. When true, MCMT runs every tick inline on the server thread (vanilla behaviour).")
                    .define("disabled", true);
            opsTracing = builder
                    .comment("Record the name of every in-flight parallel task so crash reports can list them.",
                            "Useful when hunting a concurrency crash, but it costs allocation on every task.")
                    .define("opsTracing", false);
            builder.pop();

            builder.comment("Worker pool sizing.").push("parallelism");
            paraMaxMode = builder
                    .comment("How paraMax is interpreted:",
                            "STANDARD  - paraMax is an upper bound, clamped to the processor count.",
                            "OVERRIDE  - paraMax is used verbatim, even above the processor count.",
                            "REDUCTION - paraMax is subtracted from the processor count. Use this to leave headroom",
                            "            for Minecraft's own background executor, which handles chunk generation and I/O.")
                    .defineEnum("paraMaxMode", ParaMaxMode.STANDARD);
            paraMax = builder
                    .comment("Requested worker count. 0 or 1 means 'use all available processors'.",
                            "Values below 4 are raised to 4: with fewer workers than that the per-task dispatch",
                            "cost exceeds what the parallelism buys, and MCMT is slower than being switched off.")
                    .defineInRange("paraMax", 0, 0, 256);
            poolMaxThreads = builder
                    .comment("Hard ceiling on the total number of worker threads, including the compensation threads",
                            "a ForkJoinPool spawns to cover a worker that is blocked on a lock or a chunk-load wait.",
                            "0 means 'the same as the parallelism target': the pool never grows past it, and a tick",
                            "that has to wait for a lock simply waits, rather than the pool starting a thread to run",
                            "other work in its place. Left uncapped this inflates badly under contention - a heavy",
                            "world has been measured at ~1500 threads against a target of 32, which costs more in",
                            "scheduling than the parallelism is worth. Raise this above the target only if a profile",
                            "shows workers genuinely idle on external I/O while queued tick work goes unrun.",
                            "Takes effect on the next pool build (/mcmt restart).")
                    .defineInRange("poolMaxThreads", 0, 0, 1024);
            builder.pop();

            builder.comment("Per-hook switches. Each disables parallel dispatch for one tick loop,",
                    "running it inline instead. Useful for bisecting which loop is causing trouble.")
                    .push("hooks");
            disableWorld = builder
                    .comment("Disable parallel dispatch of per-level ticks (MinecraftServer.tickChildren).")
                    .define("disableWorld", false);
            disableEntity = builder
                    .comment("Disable parallel dispatch of entity ticks (ServerLevel.tick).")
                    .define("disableEntity", false);
            disableBlockEntity = builder
                    .comment("Disable parallel dispatch of block-entity ticks (Level.tickBlockEntities).")
                    .define("disableBlockEntity", false);
            disableEnvironment = builder
                    .comment("Disable parallel dispatch of chunk/environment ticks (ServerChunkCache.tickChunks).")
                    .define("disableEnvironment", false);
            disableChunkProvider = builder
                    .comment("Disable the concurrent chunk-cache fast path (ServerChunkCache.getChunk).")
                    .define("disableChunkProvider", false);
            builder.pop();

            builder.comment("Which entity and block-entity classes may not run freely in parallel.",
                    "",
                    "Three lists, in order of precedence, each naming classes to be run a particular way:",
                    "  whiteList        - run free, with no constraint at all. Wins over everything below,",
                    "                     including chunkLockModded, so a broad rule can be narrowed by exception.",
                    "  singleThreadList - never run concurrently with anything else on this list, anywhere in any",
                    "                     dimension. For ticks whose effects are not bounded by position: something",
                    "                     that walks a global registry, or moves objects between dimensions.",
                    "  blackList        - chunk-locked. The tick takes a lock on the chunks around it, so two of",
                    "                     them near each other are serialised while distant ones still run at once.",
                    "                     This is the right answer for ordinary machinery, and much cheaper than",
                    "                     singleThreadList; reach for that only when position-scoped locking cannot help.",
                    "",
                    "Entries are fully-qualified class names, or wildcard patterns:",
                    "  com.example.mod.BlockEntityFoo  - that class",
                    "  com.example.mod.*               - every class directly in that package",
                    "  com.example.mod.**              - that package and everything beneath it",
                    "A single * also spans the $ of a nested class, so com.example.Foo* covers com.example.Foo$Ticker.",
                    "Matching is on the class's own name and is not inherited. Names that no loaded class has are",
                    "kept as configured but ignored, so one config file can serve a modded and a vanilla instance.")
                    .push("serdes");
            chunkLockModded = builder
                    .comment("Chunk-lock every block entity and entity whose class is not part of vanilla Minecraft.",
                            "This is the safe default: modded tick code has never been audited for thread safety.")
                    .define("chunkLockModded", true);
            vanillaDefault = builder
                    .comment("What a vanilla class that no rule mentions is allowed to do.",
                            "FREE       - run unconstrained. Fastest, and what MCMT has always assumed.",
                            "POS_LOCK   - lock its own block and the six around it.",
                            "CHUNK_LOCK - lock the square of chunks around it.",
                            "",
                            "FREE is a bet that vanilla's unsafe classes are all named in the code. That bet has",
                            "been wrong three times so far - hoppers, item merging and mob loot pickup each",
                            "duplicated or destroyed items - and every time it failed silently, because the list",
                            "was built from crashes and none of these ever crashed. POS_LOCK buys most of the",
                            "safety back for part of the throughput; measure before choosing.")
                    .defineEnum("vanillaDefault", VanillaDefault.FREE);
            blockEntityBlackList = builder.defineListAllowEmpty("blockEntityBlackList", List.of(), () -> "", o -> o instanceof String);
            blockEntityWhiteList = builder.defineListAllowEmpty("blockEntityWhiteList", List.of(), () -> "", o -> o instanceof String);
            blockEntitySingleThreadList = builder.defineListAllowEmpty("blockEntitySingleThreadList", List.of(), () -> "", o -> o instanceof String);
            entityBlackList = builder.defineListAllowEmpty("entityBlackList", List.of(), () -> "", o -> o instanceof String);
            entityWhiteList = builder.defineListAllowEmpty("entityWhiteList", List.of(), () -> "", o -> o instanceof String);
            entitySingleThreadList = builder.defineListAllowEmpty("entitySingleThreadList", List.of(), () -> "", o -> o instanceof String);
            builder.pop();
        }
    }

    /** The on-disk config values. Prefer the baked static fields when reading from a tick hook. */
    public static final Template SPEC_VALUES;

    /** The spec to register with the mod container. */
    public static final ModConfigSpec SPEC;

    static {
        final Pair<Template, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Template::new);
        SPEC_VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    // Pool sizing
    // -----------

    /**
     * The smallest pool worth building.
     *
     * <p>Not an arbitrary guard against silly values — it is where MCMT stops paying for itself. Dispatching a
     * tick task costs something and a busy level dispatches tens of thousands of them, so at two workers the
     * overhead exceeds what the parallelism buys: measured at 67.4 ms against 60.9 ms for the same load with
     * MCMT off, i.e. slower than not using it at all. Four is the first setting that wins (2.5x), so a request
     * for less than that is honoured as four rather than as a slowdown the owner did not ask for.
     */
    private static final int MIN_PARALLELISM = 4;

    /**
     * The worker count implied by {@link #paraMax} and {@link #paraMaxMode}, never below
     * {@link #MIN_PARALLELISM} — except on a machine too small to reach it, where the cap is the core count and
     * MCMT is unlikely to be the right choice anyway.
     */
    public static int getParallelism() {
        int cores = Runtime.getRuntime().availableProcessors();
        int floor = Math.min(MIN_PARALLELISM, cores);
        return switch (paraMaxMode) {
            case STANDARD -> paraMax <= 1 ? cores : Math.max(floor, Math.min(cores, paraMax));
            case OVERRIDE -> paraMax <= 1 ? cores : Math.max(floor, paraMax);
            case REDUCTION -> Math.max(floor, cores - Math.max(0, paraMax));
        };
    }

    /**
     * The hard ceiling on total worker threads passed to the pool as its {@code maximumPoolSize}.
     *
     * <p>{@link #poolMaxThreads} of zero — the default — pins this to {@link #getParallelism()} exactly: the pool
     * is not allowed a single compensation thread, and a worker that blocks on a lock or a chunk-load wait blocks
     * in place. That is the point. A {@code ForkJoinPool} left to compensate freely reached ~1500 threads on a
     * heavy world against a target of 32 (see {@code TickBatch}), and every contention source found since has
     * inflated it the same way. Whatever a blocked tick loses by waiting, it loses less than the whole server
     * loses to scheduling a four-figure thread count.
     *
     * <p>A non-zero value is honoured verbatim, clamped to no less than the parallelism target and no more than
     * 1024.
     */
    public static int getMaxPoolSize() {
        int parallelism = getParallelism();
        return poolMaxThreads <= 0 ? parallelism : Math.max(parallelism, Math.min(1024, poolMaxThreads));
    }

    // Bake / save
    // -----------

    @SubscribeEvent
    public static void onConfigLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    /** Copies the on-disk config into the baked fields the tick hooks read. */
    public static synchronized void bake() {
        disabled = SPEC_VALUES.disabled.get();
        opsTracing = SPEC_VALUES.opsTracing.get();

        paraMax = SPEC_VALUES.paraMax.get();
        paraMaxMode = SPEC_VALUES.paraMaxMode.get();
        poolMaxThreads = SPEC_VALUES.poolMaxThreads.get();

        disableWorld = SPEC_VALUES.disableWorld.get();
        disableEntity = SPEC_VALUES.disableEntity.get();
        disableBlockEntity = SPEC_VALUES.disableBlockEntity.get();
        disableEnvironment = SPEC_VALUES.disableEnvironment.get();
        disableChunkProvider = SPEC_VALUES.disableChunkProvider.get();

        chunkLockModded = SPEC_VALUES.chunkLockModded.get();
        vanillaDefault = SPEC_VALUES.vanillaDefault.get();

        blockEntityBlackList.load(SPEC_VALUES.blockEntityBlackList.get());
        blockEntityWhiteList.load(SPEC_VALUES.blockEntityWhiteList.get());
        blockEntitySingleThreadList.load(SPEC_VALUES.blockEntitySingleThreadList.get());
        entityBlackList.load(SPEC_VALUES.entityBlackList.get());
        entityWhiteList.load(SPEC_VALUES.entityWhiteList.get());
        entitySingleThreadList.load(SPEC_VALUES.entitySingleThreadList.get());

        // Filters read the lists and chunkLockModded above, and their answers are cached per class.
        net.neoforged.neoforge.mcmt.serdes.SerDesRegistry.invalidate();
    }

    /** Copies the baked fields back into the on-disk config and writes it out. */
    public static synchronized void save() {
        SPEC_VALUES.disabled.set(disabled);
        SPEC_VALUES.opsTracing.set(opsTracing);

        SPEC_VALUES.paraMax.set(paraMax);
        SPEC_VALUES.paraMaxMode.set(paraMaxMode);
        SPEC_VALUES.poolMaxThreads.set(poolMaxThreads);

        SPEC_VALUES.disableWorld.set(disableWorld);
        SPEC_VALUES.disableEntity.set(disableEntity);
        SPEC_VALUES.disableBlockEntity.set(disableBlockEntity);
        SPEC_VALUES.disableEnvironment.set(disableEnvironment);
        SPEC_VALUES.disableChunkProvider.set(disableChunkProvider);

        SPEC_VALUES.chunkLockModded.set(chunkLockModded);
        SPEC_VALUES.vanillaDefault.set(vanillaDefault);

        SPEC_VALUES.blockEntityBlackList.set(blockEntityBlackList.toConfigList());
        SPEC_VALUES.blockEntityWhiteList.set(blockEntityWhiteList.toConfigList());
        SPEC_VALUES.blockEntitySingleThreadList.set(blockEntitySingleThreadList.toConfigList());
        SPEC_VALUES.entityBlackList.set(entityBlackList.toConfigList());
        SPEC_VALUES.entityWhiteList.set(entityWhiteList.toConfigList());
        SPEC_VALUES.entitySingleThreadList.set(entitySingleThreadList.toConfigList());

        SPEC.save();
    }

    /** Every configured entry that names a class absent from this environment, for {@code /mcmt stats}. */
    public static List<String> unresolvedClassNames() {
        List<String> out = new ArrayList<>();
        for (ClassMatcher list : List.of(
                blockEntityBlackList, blockEntityWhiteList, blockEntitySingleThreadList,
                entityBlackList, entityWhiteList, entitySingleThreadList)) {
            out.addAll(list.unresolved());
        }
        return out;
    }
}
