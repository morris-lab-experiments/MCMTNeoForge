/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.commands;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a headless {@link ServerPlayer} through interactions along the real serverbound-packet path, so the
 * divergence harnesses exercise the code multi-core tick processing actually parallelises.
 *
 * <h2>Why go through the packet handlers</h2>
 *
 * <p>{@code /mcmt testplayer spawn} gives you player <em>presence</em>: spawners tick, natural spawning runs,
 * villagers become willing. It does not give you <em>interaction</em>, and interaction is where the
 * highest-value real-world duplication bugs live &mdash; the hopper, item-merge and mob-pickup duplications
 * that phases 4d&ndash;4h chased all sit on paths a player triggers.
 *
 * <p>A shortcut such as {@code player.getInventory().add(stack)} proves nothing: it never touches
 * {@link net.minecraft.world.inventory.AbstractContainerMenu#clicked}, the method a container-click
 * duplication lives in. So every method here builds the same {@code Serverbound*} packet a real client sends
 * and calls the matching {@code ServerGamePacketListenerImpl} handler. The handler runs its full validation,
 * its menu synchronisation and its event hooks, exactly as it would for a networked player.
 *
 * <h2>Threading</h2>
 *
 * <p>You call these from the server thread. A command executes on the server thread, at the top of the tick
 * loop, before the parallel entity and block-entity phases start. A real packet arrives on a Netty thread and
 * {@link net.minecraft.network.protocol.PacketUtils#ensureRunningOnSameThread} reschedules it onto the server
 * thread; when you are already on the server thread that method is a no-op and returns normally, so calling
 * the handler directly is safe and takes the same code path from that point on. Do not call these from a pool
 * worker.
 *
 * <h2>Leaving the player in a clean state</h2>
 *
 * <p>Every method is safe to call repeatedly from a scripted harness. A container click can leave an item on
 * the menu cursor; {@link #reset} closes any open menu, which returns the cursor item to the inventory. Call
 * {@link #reset} between scenarios.
 *
 * <h2>Failure is loud</h2>
 *
 * <p>The vanilla handlers swallow a rejected interaction into a {@code LOGGER.debug} call and return, which
 * would produce a green test that proves nothing &mdash; the single failure mode this project cares most
 * about. Each method here checks the same preconditions the handler checks <em>before</em> calling it and
 * returns a failing {@link Outcome} with the reason. A malformed click that makes the menu throw is left to
 * propagate.
 */
public final class TestPlayerActions {
    private TestPlayerActions() {}

    /**
     * The result of one interaction attempt.
     *
     * @param ok     true if the interaction reached and ran the server handler
     * @param detail a human-readable description: the rejection reason when {@code ok} is false, or a summary
     *               of what changed when it is true
     */
    public record Outcome(boolean ok, String detail) {
        static Outcome ok(String detail) {
            return new Outcome(true, detail);
        }

        static Outcome fail(String detail) {
            return new Outcome(false, detail);
        }
    }

    /**
     * A per-player sequence counter for block-interaction packets.
     *
     * <p>{@code ServerboundUseItemOnPacket} and {@code ServerboundPlayerActionPacket} carry a sequence number
     * that the client bumps on every block action. The server stores it in {@code connection.ackBlockChangesUpTo}
     * and echoes it back so the client can drop its optimistic block-state prediction; the server never
     * validates it. A real client still sends a monotonic value, so this harness does too rather than sending
     * a constant, in case future validation is added.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    // ----------------------------------------------------------------------------------------------------
    // Container interaction
    // ----------------------------------------------------------------------------------------------------

    /**
     * Opens the menu of the block at {@code pos} for {@code player}, the way right-clicking a chest or barrel
     * does.
     *
     * <p>This drives {@link ServerPlayer#openMenu(MenuProvider)} with the block's own
     * {@link BlockState#getMenuProvider}. {@code openMenu} allocates the container id, sends the open-screen
     * packet (discarded on this player's embedded channel), wires the menu to the player and sets
     * {@code player.containerMenu}. After this returns ok, {@code player.containerMenu.containerId} is the id
     * you pass to {@link #containerClick}.
     *
     * <p>A range check is applied first: a real open goes through {@code handleUseItemOn}, which rejects a
     * block further than {@code blockInteractionRange + 1} from the eye. Place the block within about five
     * blocks of the player, or move the player with {@link #placeAt}.
     *
     * @param pos the block whose menu to open
     * @return ok with the container id, or a failure describing why no menu opened
     */
    public static Outcome openContainer(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!player.canInteractWithBlock(pos, 1.0)) {
            return Outcome.fail("block " + pos.toShortString() + " is out of interaction range of " + player.getName().getString());
        }

        BlockState state = level.getBlockState(pos);
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider == null) {
            return Outcome.fail("block " + state.getBlock().getName().getString() + " at " + pos.toShortString() + " has no menu");
        }

        OptionalInt id = player.openMenu(provider);
        if (id.isEmpty()) {
            return Outcome.fail("openMenu returned empty for " + pos.toShortString() + " (menu factory declined, or player is a spectator)");
        }
        if (player.containerMenu == player.inventoryMenu || player.containerMenu.containerId != id.getAsInt()) {
            return Outcome.fail("openMenu reported id " + id.getAsInt() + " but player.containerMenu did not switch");
        }
        return Outcome.ok("opened menu " + id.getAsInt() + " (" + player.containerMenu.getClass().getSimpleName() + ", "
                + player.containerMenu.slots.size() + " slots)");
    }

    /**
     * Clicks one slot of the player's open menu, driving
     * {@link net.minecraft.server.network.ServerGamePacketListenerImpl#handleContainerClick}.
     *
     * <p>Open a menu first with {@link #openContainer}. The synthetic {@link ServerboundContainerClickPacket}
     * is filled the way a well-behaved client fills it:
     *
     * <ul>
     * <li><b>container id</b> &mdash; taken from {@code player.containerMenu}. The handler drops the click
     * outright if this does not match, so the harness must never guess it.
     * <li><b>state id</b> &mdash; taken from {@code player.containerMenu.getStateId()}. The handler does
     * <em>not</em> reject a stale state id; it uses it only to choose between a full resync and an
     * incremental one. A wrong value still runs the click, so matching it just avoids a spurious full
     * broadcast.
     * <li><b>changed slots</b> and <b>carried item</b> &mdash; the client's prediction of the post-click
     * state. The handler copies these into the menu's <em>remote</em> tracking slots only; they do not touch
     * the authoritative inventory, so an empty map and the current real cursor item are a faithful stand-in.
     * The real mutation is {@code menu.clicked(slot, button, type, player)}, which the handler always runs.
     * </ul>
     *
     * <p>Preconditions checked before the handler runs, matching the handler's own checks: a non-inventory
     * menu is open, the player is not a spectator, {@code menu.stillValid(player)} holds (this is the menu's
     * distance-to-block check &mdash; keep the player near the block), and the slot index is valid.
     *
     * @param slot   the slot index, or -999 for a click outside the window
     * @param button 0 for left / primary, 1 for right / secondary; for {@link ClickType#SWAP} the hotbar
     *               index 0-8
     * @param type   the click kind: {@link ClickType#PICKUP}, {@link ClickType#QUICK_MOVE} (shift-click),
     *               {@link ClickType#SWAP}, {@link ClickType#THROW}, and so on
     * @return ok with a before/after slot summary, or a failure describing the rejected precondition
     */
    public static Outcome containerClick(ServerPlayer player, int slot, int button, ClickType type) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            return Outcome.fail("no container menu is open; call openContainer first");
        }
        if (player.isSpectator()) {
            return Outcome.fail("player is a spectator; the handler only resyncs and never clicks");
        }
        if (!menu.stillValid(player)) {
            return Outcome.fail("menu " + menu.containerId + " is no longer valid for this player (moved too far from the block, or the block is gone)");
        }
        if (!menu.isValidSlotIndex(slot)) {
            return Outcome.fail("slot " + slot + " is out of range for menu " + menu.containerId + " (" + menu.slots.size() + " slots)");
        }

        List<ItemStack> before = snapshotSlots(menu);
        ItemStack carriedBefore = menu.getCarried().copy();

        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                menu.containerId, menu.getStateId(), slot, button, type, menu.getCarried().copy(), changedSlots);

        // Straight into the real handler. ensureRunningOnSameThread is a no-op on the server thread, so this
        // takes the identical path a networked click would from that point on.
        player.connection.handleContainerClick(packet);

        List<ItemStack> after = snapshotSlots(player.containerMenu);
        ItemStack carriedAfter = player.containerMenu.getCarried();
        String delta = describeSlotDelta(before, after, carriedBefore, carriedAfter);
        return Outcome.ok("clicked slot " + slot + " button " + button + " " + type + "; " + delta);
    }

    /**
     * Closes the player's open menu, driving
     * {@link net.minecraft.server.network.ServerGamePacketListenerImpl#handleContainerClose}.
     *
     * <p>{@code doCloseContainer} returns any item held on the menu cursor to the inventory, so this is the
     * reset for a click that left the cursor loaded. A no-op when only the inventory menu is open.
     */
    public static Outcome closeContainer(ServerPlayer player) {
        if (!player.hasContainerOpen()) {
            return Outcome.ok("no container open");
        }
        int id = player.containerMenu.containerId;
        player.connection.handleContainerClose(new ServerboundContainerClosePacket(id));
        return Outcome.ok("closed menu " + id);
    }

    // ----------------------------------------------------------------------------------------------------
    // Block use
    // ----------------------------------------------------------------------------------------------------

    /**
     * Right-clicks the block at {@code pos}, driving
     * {@link net.minecraft.server.network.ServerGamePacketListenerImpl#handleUseItemOn} with a synthetic
     * {@link ServerboundUseItemOnPacket}.
     *
     * <p>This is the realistic "player interacts with a block" path: it runs the {@code RightClickBlock}
     * event, {@code ServerPlayerGameMode.useItemOn}, and the block's own {@code useItemOn} /
     * {@code useWithoutItem}. For a chest or barrel it opens the menu the same way {@link #openContainer}
     * does; for a lever, button, door or dispenser it toggles or fires it.
     *
     * <p>The handler enforces these, so this method checks them first:
     *
     * <ul>
     * <li>The hit location must be within about one block of the target block's faces &mdash; the synthetic
     * {@link BlockHitResult} is built at the centre of the named face, which always satisfies this.
     * <li>{@code player.canInteractWithBlock(pos, 1.0)} &mdash; the range check.
     * <li>{@code serverLevel.mayInteract(player, pos)} &mdash; <b>spawn protection and the world border</b>.
     * A non-op test player inside the server's {@code spawn-protection} radius (16 blocks around world spawn
     * by default) is silently refused. Build test contraptions away from world spawn, or set
     * {@code spawn-protection=0}.
     * <li>{@code connection.awaitingPositionFromClient} must be null. The headless player is positioned with
     * {@code teleportTo}, which never sets that field, so it stays null.
     * </ul>
     *
     * @param pos  the block to click
     * @param face the face that is hit; also the face a placed block would attach to
     * @param hand the hand to use; {@link InteractionHand#MAIN_HAND} for the normal case
     * @return ok, or a failure naming the precondition the handler would have rejected on
     */
    public static Outcome useBlock(ServerPlayer player, BlockPos pos, Direction face, InteractionHand hand) {
        ServerLevel level = player.serverLevel();
        if (!player.canInteractWithBlock(pos, 1.0)) {
            return Outcome.fail("block " + pos.toShortString() + " is out of interaction range");
        }
        if (!level.mayInteract(player, pos)) {
            return Outcome.fail("level.mayInteract denied " + pos.toShortString() + " (spawn protection or world border); move the test away from world spawn or set spawn-protection=0");
        }

        Vec3 hitLocation = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        BlockHitResult hit = new BlockHitResult(hitLocation, face, pos, false);
        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(hand, hit, SEQUENCE.getAndIncrement());

        boolean hadMenu = player.hasContainerOpen();
        player.connection.handleUseItemOn(packet);

        String opened = !hadMenu && player.hasContainerOpen()
                ? "; opened menu " + player.containerMenu.containerId
                : "";
        return Outcome.ok("used block " + level.getBlockState(pos).getBlock().getName().getString() + " at " + pos.toShortString() + opened);
    }

    // ----------------------------------------------------------------------------------------------------
    // Drop and pickup
    // ----------------------------------------------------------------------------------------------------

    /**
     * Drops the item in the player's selected hotbar slot, driving
     * {@link net.minecraft.server.network.ServerGamePacketListenerImpl#handlePlayerAction} with a
     * {@link ServerboundPlayerActionPacket.Action#DROP_ITEM} (or {@code DROP_ALL_ITEMS}) packet.
     *
     * <p>The handler calls {@code player.drop(wholeStack)}, which removes the item from the hotbar slot,
     * spawns an {@link ItemEntity}, and sets a 40-tick pickup delay plus this player as the thrower &mdash;
     * so the <em>same</em> player will not re-collect it immediately, but another player, a hopper or a mob
     * can. That asymmetry is what makes a drop useful for hunting a pickup or merge duplication.
     *
     * @param wholeStack true to drop the whole stack ({@code DROP_ALL_ITEMS}), false to drop one item
     * @return ok naming the dropped item, or a failure if the selected slot was empty
     */
    public static Outcome dropSelected(ServerPlayer player, boolean wholeStack) {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.isEmpty()) {
            return Outcome.fail("selected hotbar slot " + player.getInventory().selected + " is empty");
        }
        int countBefore = selected.getCount();
        String name = selected.getHoverName().getString();

        ServerboundPlayerActionPacket.Action action = wholeStack
                ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                : ServerboundPlayerActionPacket.Action.DROP_ITEM;
        // The direction is unused for a drop action but the packet requires one.
        ServerboundPlayerActionPacket packet = new ServerboundPlayerActionPacket(
                action, player.blockPosition(), Direction.DOWN, SEQUENCE.getAndIncrement());
        player.connection.handlePlayerAction(packet);

        int countAfter = player.getInventory().getSelected().getCount();
        int dropped = countBefore - countAfter;
        if (dropped <= 0) {
            return Outcome.fail("drop ran but the selected slot did not shrink (item refused to be dropped?)");
        }
        return Outcome.ok("dropped " + dropped + "x " + name + " as an item entity");
    }

    /**
     * Moves the headless player to {@code target}.
     *
     * <p>This is a positioning helper, not an interaction: it teleports rather than replaying movement
     * packets. Item pickup, mob targeting and {@code hasNearbyAlivePlayer} all key off player position, and a
     * headless player has no client sending {@code ServerboundMovePlayerPacket}, so the harness moves it
     * directly. {@code teleportTo} keeps {@code connection.awaitingPositionFromClient} null, which
     * {@link #useBlock} relies on.
     *
     * <p>Pickup itself needs no call: {@code Player.aiStep} scans an inflated box around the player every
     * tick and collects any {@link ItemEntity} in it whose pickup delay has elapsed. Place the player on the
     * item and wait a tick.
     */
    public static Outcome placeAt(ServerPlayer player, Vec3 target) {
        ServerLevel level = player.serverLevel();
        player.teleportTo(level, target.x, target.y, target.z, player.getYRot(), player.getXRot());
        return Outcome.ok("moved to " + String.format("%.1f, %.1f, %.1f", target.x, target.y, target.z));
    }

    // ----------------------------------------------------------------------------------------------------
    // Reset and invariants
    // ----------------------------------------------------------------------------------------------------

    /**
     * Returns the player to a neutral state: closes any open menu, which drops the menu cursor item back into
     * the inventory. Call this between scenarios so a click that left the cursor loaded does not carry over.
     */
    public static Outcome reset(ServerPlayer player) {
        return closeContainer(player);
    }

    /**
     * Counts every item the player holds: the 37 inventory slots (hotbar, main, armor, offhand) plus the
     * menu cursor. Use it as one half of a conservation check &mdash; snapshot before a scenario, run the
     * scenario, snapshot after, and assert the totals across all players plus {@link #countLooseItems} plus
     * any container contents are unchanged. A duplication bug breaks that equality; a silently-dropped
     * interaction leaves it holding, which is why the interaction methods report failure explicitly.
     */
    public static int countPlayerItems(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            total += player.getInventory().getItem(i).getCount();
        }
        total += player.containerMenu.getCarried().getCount();
        return total;
    }

    /** Sums the stack sizes of every {@link ItemEntity} in {@code region}. The other half of a conservation check. */
    public static int countLooseItems(ServerLevel level, AABB region) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, region)) {
            total += item.getItem().getCount();
        }
        return total;
    }

    // ----------------------------------------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------------------------------------

    private static List<ItemStack> snapshotSlots(AbstractContainerMenu menu) {
        List<ItemStack> copy = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            copy.add(menu.slots.get(i).getItem().copy());
        }
        return copy;
    }

    private static String describeSlotDelta(
            List<ItemStack> before, List<ItemStack> after, ItemStack carriedBefore, ItemStack carriedAfter) {
        StringBuilder sb = new StringBuilder();
        int changed = 0;
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            if (!ItemStack.matches(before.get(i), after.get(i))) {
                changed++;
                if (sb.length() < 200) {
                    sb.append(" slot ").append(i).append(": ")
                            .append(render(before.get(i))).append(" -> ").append(render(after.get(i)));
                }
            }
        }
        if (!ItemStack.matches(carriedBefore, carriedAfter)) {
            sb.append(" cursor: ").append(render(carriedBefore)).append(" -> ").append(render(carriedAfter));
        } else if (changed == 0) {
            return "no slot changed (the click was a no-op)";
        }
        return changed + " slot(s) changed:" + sb;
    }

    private static String render(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x " + stack.getHoverName().getString();
    }
}
