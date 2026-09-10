/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.mcmt;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/**
 * Gametests that drive block-entity tick logic while MCMT dispatches those ticks (hook H3) to the worker
 * pool.
 *
 * <p>The canonical bug this project caught: {@code Level.getBlockEntity} returns null - rather than
 * throwing - when called off the level's own thread, so every block-entity lookup from a worker came back
 * empty. Hoppers moved nothing, crafters never crafted, comparators read empty containers. A ten-minute
 * soak at a flat 20 TPS missed it; one gametest run caught it. These tests re-create that shape: they run
 * a hopper and a comparator through their real tick path and assert the <em>result</em>, so a lookup that
 * silently returns empty off-thread fails loudly here.
 *
 * <p>Each test also spawns a ticking mock player so the pool has concurrent entity work and the
 * block-entity batch is actually dispatched rather than run inline.
 */
@ForEachTest(groups = McmtBlockEntityTests.GROUP)
public class McmtBlockEntityTests {
    public static final String GROUP = "mcmt";

    /**
     * A hopper pulls sticks from the chest above it and pushes them into the chest below.
     * {@code HopperBlockEntity.serverTick} runs on a worker under MCMT and resolves both the source and the
     * target container with {@code Level.getBlockEntity} from that thread.
     *
     * <p>Bug shape: if that off-thread lookup returns null, {@code getSourceContainer} and
     * {@code getAttachedContainer} both yield nothing and the hopper moves zero items - with no exception.
     * This test asserts the target chest gains at least one stick and that the eight sticks stay conserved
     * across the two chests, so a broken lookup (nothing moved) and a duplication (more than eight) both
     * throw.
     */
    @GameTest(timeoutTicks = 300)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder(description = "Under MCMT, a hopper moves items between two chests - HopperBlockEntity.serverTick resolves both containers with Level.getBlockEntity on a worker thread; asserts the target chest gains items and the sticks stay conserved, so a null off-thread lookup fails loudly")
    static void hopperMovesItemBetweenContainers(final DynamicTest test) {
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(2, 3, 2, Blocks.CHEST))
                // A hopper's default state faces down, so it pushes into (2, 1, 2) and pulls from the block
                // directly above it, (2, 3, 2).
                .thenExecute(() -> helper.setBlock(2, 2, 2, Blocks.HOPPER))
                .thenExecute(() -> helper.setBlock(2, 1, 2, Blocks.CHEST))
                .thenExecute(() -> {
                    final ChestBlockEntity source = helper.requireBlockEntity(2, 3, 2, ChestBlockEntity.class);
                    source.setItem(0, new ItemStack(Items.STICK, 8));
                    source.setChanged();
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    final int moved = helper.requireBlockEntity(2, 1, 2, ChestBlockEntity.class).countItem(Items.STICK);
                    final int left = helper.requireBlockEntity(2, 3, 2, ChestBlockEntity.class).countItem(Items.STICK);
                    helper.assertTrue(moved >= 1, "hopper moved no sticks between the chests while its tick ran on an MCMT worker");
                    helper.assertTrue(moved + left == 8, "sticks not conserved: " + left + " left in source + " + moved + " moved to target != 8");
                })
                .thenSucceed());
    }

    /**
     * A redstone comparator reads the fill level of the chest in front of it. A hopper fills that chest
     * while ticking on a worker under MCMT; the {@code Container.setChanged} the hopper calls runs
     * {@code Level.updateNeighbourForOutputSignal} - which schedules the comparator re-check - on that same
     * worker.
     *
     * <p>Bug shape: comparators reading empty containers was one symptom of the off-thread block-entity
     * lookup returning null. If the worker cannot read block state or schedule a tick correctly, the
     * comparator never re-evaluates and stays unpowered even though the chest holds items. This test
     * asserts the comparator ends up powered and the chest reports a non-zero analog output signal.
     */
    @GameTest(timeoutTicks = 300)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder(description = "Under MCMT, a comparator reads a container's fill level after a hopper fills it on a worker thread; asserts the comparator ends up powered and the container's analog output signal is non-zero, so a missed off-thread neighbour update fails loudly")
    static void comparatorReadsContainerFillLevel(final DynamicTest test) {
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(2, 3, 2, Blocks.CHEST))
                .thenExecute(() -> helper.setBlock(2, 2, 2, Blocks.HOPPER))
                .thenExecute(() -> helper.setBlock(2, 1, 2, Blocks.CHEST))
                // A comparator is a DiodeBlock, so it needs a rigid block beneath it or canSurvive pops it
                // to air on the next neighbour update. Place that support explicitly rather than relying on
                // the template floor: @EmptyTemplate generates the floor dynamically for a non-default size,
                // and it does not cover this footprint -- the comparator placed fine, passed the unpowered
                // assert, and had vanished by the time the powered one ran.
                .thenExecute(() -> helper.setBlock(2, 0, 3, Blocks.IRON_BLOCK))
                // The comparator's default facing is north, and ComparatorBlock.getInputSignal reads
                // pos.relative(facing), so the chest at (2, 1, 2) is this comparator's input.
                .thenExecute(() -> helper.setBlock(2, 1, 3, Blocks.COMPARATOR))
                .thenExecute(() -> {
                    final ChestBlockEntity source = helper.requireBlockEntity(2, 3, 2, ChestBlockEntity.class);
                    source.setItem(0, new ItemStack(Items.STICK, 8));
                    source.setChanged();
                })
                .thenExecute(() -> helper.assertBlockProperty(new BlockPos(2, 1, 3), BlockStateProperties.POWERED, Boolean.FALSE))
                .thenIdle(120)
                .thenExecute(() -> helper.assertBlockProperty(new BlockPos(2, 1, 3), BlockStateProperties.POWERED, Boolean.TRUE))
                .thenExecute(() -> helper.assertTrue(
                        helper.getBlockState(new BlockPos(2, 1, 2)).getAnalogOutputSignal(
                                helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2))) > 0,
                        "target container reports zero analog output signal despite holding items moved under MCMT"))
                .thenSucceed());
    }
}
