/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.mcmt;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

/**
 * Gametests that drive player-gated and thread-identity-sensitive vanilla paths while MCMT dispatches the
 * server tick across its worker pool.
 *
 * <p>This suite runs with MCMT enabled, so entity ticks (hook H2) and block-entity ticks (hook H3) execute
 * on pool workers rather than the server thread. Vanilla has many places where a worker reads shared level
 * state - the player list, the entity index, a block entity - through an API that was written assuming the
 * caller is the level's own thread. When such a read silently returns empty off-thread, nothing crashes:
 * the spawner just never fires, the hopper just never moves an item. A flat-rate soak misses that; a
 * gametest that asserts the effect catches it on the first run.
 *
 * <p>Every test here asserts positively on the result of the player-gated operation. If the setup does not
 * take - the spawner never ticks, the menu never opens, the mob never runs its {@code aiStep} - the
 * assertion fails loudly rather than passing on a no-op.
 */
@ForEachTest(groups = McmtPlayerTests.GROUP)
public class McmtPlayerTests {
    public static final String GROUP = "mcmt";

    /**
     * A mob spawner only ticks when {@link net.minecraft.world.level.EntityGetter#hasNearbyAlivePlayer}
     * finds a non-spectator, living player within {@code requiredPlayerRange}. Under MCMT that player-list
     * read runs on a worker during {@code BaseSpawner.serverTick}, dispatched as a block-entity tick.
     *
     * <p>Bug shape: if {@code ServerLevel.players()} comes back empty off-thread, {@code isNearPlayer}
     * returns false forever and the spawner produces nothing. This test places a spawner set to armor
     * stands - an entity type with no spawn-placement rules, so the spawn cannot be vetoed for light or
     * ground - with a ticking mock player nearby, and asserts an armor stand actually appears. A broken
     * off-thread player lookup leaves the structure empty and the assertion throws.
     */
    @GameTest(timeoutTicks = 300)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder(description = "Under MCMT, a mob spawner with a ticking mock player nearby actually spawns entities - the player-gate read (EntityGetter.hasNearbyAlivePlayer) runs on a worker thread during the block-entity tick, and this fails loudly if that read comes back empty")
    static void spawnerTicksWithNearbyPlayer(final DynamicTest test) {
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(4, 2, 4, Blocks.SPAWNER))
                .thenExecute(() -> helper.requireBlockEntity(4, 2, 4, SpawnerBlockEntity.class)
                        .setEntityId(EntityType.ARMOR_STAND, helper.getLevel().getRandom()))
                .thenIdle(5)
                // The spawner starts with spawnDelay == 20 ticks and re-arms to at least 200 ticks after it
                // fires, so exactly one batch spawns inside this window.
                .thenExecuteAfter(140, () -> helper.assertEntityPresent(EntityType.ARMOR_STAND))
                .thenSucceed());
    }

    /**
     * A survival player walks over a dropped item and picks it up in {@code Player.aiStep}, which runs on a
     * worker under MCMT (hook H2). The pickup loop calls {@code ServerLevel.getEntities} from that thread to
     * find the {@link net.minecraft.world.entity.item.ItemEntity}.
     *
     * <p>Bug shape: the level entity index ({@code EntitySectionStorage} / {@code EntityLookup}) was not
     * thread-safe (plan section 4l); an off-thread query could miss the item and the player would never
     * pick it up. This test asserts the stick lands in the player's inventory exactly once and no item
     * entity is left on the ground, so both a missed pickup and a duplication fail loudly.
     */
    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder(description = "Under MCMT, a player picks up a dropped ItemEntity during its worker-dispatched aiStep; asserts the stick is in the inventory exactly once and gone from the ground, so a missed off-thread entity lookup or a duplication both fail")
    static void playerPicksUpItemEntity(final DynamicTest test) {
        test.onGameTest(helper -> {
            final GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL).moveToCentre();
            helper.startSequence()
                    .thenExecute(() -> helper.spawnItem(Items.STICK, 2, 2, 2))
                    .thenWaitUntil(() -> helper.assertTrue(
                            player.getInventory().countItem(Items.STICK) >= 1,
                            "player never picked up the stick dropped while MCMT was dispatching entity ticks"))
                    .thenExecute(() -> helper.assertTrue(
                            player.getInventory().countItem(Items.STICK) == 1,
                            "player inventory holds " + player.getInventory().countItem(Items.STICK) + " sticks, expected exactly 1 (duplication or loss)"))
                    .thenExecute(() -> helper.assertItemEntityCountIs(Items.STICK, new BlockPos(2, 2, 2), 4.0, 0))
                    .thenSucceed();
        });
    }

    /**
     * A mob picks up a nearby item in its {@code aiStep} looting loop, dispatched to a worker under MCMT
     * (hook H2). This project wraps that loop in {@code MCMT.guardItemPickup} because two workers must not
     * claim one item at once, and the loop calls {@code ServerLevel.getEntitiesOfClass} off-thread to find
     * the item.
     *
     * <p>Bug shape: a duplication (both the mob and the ground keep the item) or a missed pickup (the mob
     * ends empty-handed). This test spawns one zombified piglin - which does not burn in daylight and picks
     * up weapons - and one iron sword next to a ticking player, then asserts the mob holds the sword and no
     * sword item entity remains. Total conserved at one.
     */
    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder(description = "Under MCMT, a mob picks up a nearby item during its worker-dispatched aiStep (the path guarded by MCMT.guardItemPickup); asserts the mob holds the item and none is left on the ground, conserving exactly one")
    static void mobPicksUpItemNearPlayer(final DynamicTest test) {
        test.onGameTest(helper -> {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, helper.getLevel().getServer());
            helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
            final Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIFIED_PIGLIN, 3, 2, 3);
            mob.setCanPickUpLoot(true);
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            helper.startSequence()
                    .thenExecute(() -> helper.spawnItem(Items.IRON_SWORD, 3, 2, 3))
                    .thenIdle(60)
                    .thenExecute(() -> helper.assertTrue(
                            mob.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD),
                            "mob did not pick up the iron sword during its MCMT-dispatched aiStep"))
                    .thenExecute(() -> helper.assertItemEntityCountIs(Items.IRON_SWORD, new BlockPos(3, 2, 3), 5.0, 0))
                    .thenSucceed();
        });
    }

    /**
     * A player opens a chest and shift-clicks a stack into their own inventory. The chest's lid animation
     * and opener bookkeeping ({@code ContainerOpenersCounter}) run on a worker under MCMT (hook H3) while
     * the interaction happens on the server thread.
     *
     * <p>Bug shape: the block-entity lookup that backs {@code Level.getBlockEntity} returned null off-thread
     * (see plan section 6), so any block-entity-mediated container read from the tick path came back empty.
     * This test asserts the menu quick-move still conserves items - the diamonds move fully into the player
     * inventory and the chest ends empty - with the block-entity tick loop parallelized.
     */
    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder(description = "Under MCMT, a player opens a chest and takes a stack; asserts the items move fully into the player inventory and the chest ends empty - total conserved through the menu quick-move")
    static void playerTakesItemFromChest(final DynamicTest test) {
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(2, 2, 2, Blocks.CHEST))
                .thenExecute(() -> helper.requireBlockEntity(2, 2, 2, ChestBlockEntity.class)
                        .setItem(0, new ItemStack(Items.DIAMOND, 5)))
                .thenIdle(2)
                .thenExecute(player -> helper.useBlock(new BlockPos(2, 2, 2), player))
                .thenIdle(2)
                .thenExecute(player -> helper.assertTrue(
                        player.containerMenu instanceof ChestMenu,
                        "chest menu did not open for the mock player"))
                .thenExecute(player -> player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player))
                .thenIdle(2)
                .thenExecute(player -> helper.assertTrue(
                        player.getInventory().countItem(Items.DIAMOND) == 5,
                        "player inventory holds " + player.getInventory().countItem(Items.DIAMOND) + " diamonds after taking from the chest, expected 5"))
                .thenExecute(() -> helper.assertTrue(
                        helper.requireBlockEntity(2, 2, 2, ChestBlockEntity.class).countItem(Items.DIAMOND) == 0,
                        "chest still holds diamonds after the player took them"))
                .thenSucceed());
    }
}
