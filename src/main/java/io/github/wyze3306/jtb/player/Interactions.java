package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.inventory.InventoryState;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ActorSwingSource;
import org.cloudburstmc.protocol.bedrock.data.HandSlot;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.net.ItemStackLegacyRequestId;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.InventoryTransaction;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemReleaseActionType;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUseActionType;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUseClientCooldownState;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUseOnActorActionType;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUsePredictedResult;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUseTriggerType;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.data.InventoryTransactionData;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.data.ItemReleaseInventoryTransaction;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.data.ItemUseInventoryTransaction;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.data.ItemUseOnActorInventoryTransaction;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket;

/**
 * The player's actions on blocks, items and entities that become Bedrock inventory transactions, and
 * their bookkeeping: block change acknowledgements, arm swings and item use.
 * <p>
 * <b>Block change acks.</b> The Java client predicts block changes and keeps its prediction until the
 * server acknowledges the action's sequence number; on the ack it re-applies the block states the
 * server sent in between. Acking at once would revert a correct prediction until Bedrock's
 * {@code UpdateBlock} arrives (flicker), so the ack waits for the round trip to the Bedrock server
 * plus two server ticks (measured with a {@code NetworkStackLatency} ping, which PowerNukkitX echoes).
 * <p>
 * <b>Item use.</b> PowerNukkitX finishes food on its own, but potions, milk and ominous bottles only
 * when the client sends a second "use" once drinking is over; the Java client never says it finished,
 * so the proxy sends it after the Java drinking time.
 */
final class Interactions {

    /** Java drinking time (32 ticks) plus one, so PowerNukkitX counts at least its required 31. */
    private static final int DRINK_TICKS = 33;
    /** Minimum Java ticks between forwarded swings (the Bedrock swing lasts 6, its client repeats it every 4 at most). */
    private static final int SWING_INTERVAL = 4;
    private static final int DEFAULT_ACK_DELAY_TICKS = 3;
    private static final int MAX_ACK_DELAY_TICKS = 20;
    private static final long PING_INTERVAL_TICKS = 40;

    private final ProxySession session;
    private final PlayerState player;

    private int pendingAck;
    private long ackDeadline;
    private long rttNanos = -1;
    private long lastPingTick = -PING_INTERVAL_TICKS;

    private ActorSwingSource actionThisTick;
    private long javaTicks;
    private long lastSwingTick = -SWING_INTERVAL;

    private boolean usingItem;
    private boolean drinking;
    private int useTicks;
    private int useSlot;

    Interactions(ProxySession session, PlayerState player) {
        this.session = session;
        this.player = player;
    }

    // ------------------------------------------------------------------ acks & latency

    /** Schedules the acknowledgement of a Java block action sequence number. */
    void acknowledge(int sequence) {
        if (sequence <= 0) {
            return;
        }
        this.pendingAck = Math.max(this.pendingAck, sequence);
        if (this.ackDeadline == 0) {
            int delay = this.rttNanos < 0 ? DEFAULT_ACK_DELAY_TICKS
                    : (int) Math.min(MAX_ACK_DELAY_TICKS, 2 + (this.rttNanos + 49_999_999L) / 50_000_000L);
            this.ackDeadline = this.session.tickCount() + delay;
        }
    }

    /** Our own latency ping came back (a {@code NetworkStackLatency} that is not from the server). */
    void onPingEcho(long creationTime) {
        long rtt = System.nanoTime() - creationTime;
        if (rtt >= 0 && rtt < 10_000_000_000L) {
            this.rttNanos = this.rttNanos < 0 ? rtt : (this.rttNanos * 3 + rtt) / 4;
        }
    }

    /** Proxy tick: due acks, latency pings. */
    void tick() {
        long now = this.session.tickCount();
        if (this.pendingAck > 0 && now >= this.ackDeadline) {
            this.session.sendJava(new ClientboundBlockChangedAckPacket(this.pendingAck));
            this.pendingAck = 0;
            this.ackDeadline = 0;
        }
        if (this.player.isSpawned() && now - this.lastPingTick >= PING_INTERVAL_TICKS) {
            this.lastPingTick = now;
            NetworkStackLatencyPacket ping = new NetworkStackLatencyPacket();
            ping.setCreationTime(System.nanoTime());
            ping.setFromServer(true);
            this.session.sendBedrock(ping);
        }
    }

    // ------------------------------------------------------------------ swings

    /** Records what the next Java swing of this tick belongs to. */
    void acted(ActorSwingSource source) {
        this.actionThisTick = source;
    }

    /**
     * Java {@code Punch} (arm swing). It follows the attack, use or dig of the same tick; a swing on
     * its own is a missed swing. Swings are thinned to the Bedrock rhythm.
     */
    void onSwing(boolean digging) {
        ActorSwingSource source = this.actionThisTick;
        if (source == null) {
            if (digging) {
                source = ActorSwingSource.MINE;
            } else {
                source = ActorSwingSource.NONE;
                this.player.input().missedSwing();
            }
        }
        // A Bedrock arm swing lasts 6 ticks and its client never starts one faster than every 4; Java
        // swings every tick while digging and on every click. Server anti-cheats (Linesia's BadPacketF)
        // flag swings closer than that, so they are thinned whatever they belong to: the attack, use or
        // dig itself is sent apart.
        if (this.javaTicks - this.lastSwingTick < SWING_INTERVAL) {
            return;
        }
        this.lastSwingTick = this.javaTicks;
        AnimatePacket animate = new AnimatePacket();
        animate.setAction(AnimatePacket.Action.SWING);
        animate.setTargetRuntimeID(this.player.runtimeId());
        animate.setSwingSource(source);
        this.session.sendBedrock(animate);
    }

    // ------------------------------------------------------------------ item use

    /** Called once per emulated movement tick (Java tick, or proxy tick when the client is silent). */
    void onMovementTick() {
        this.javaTicks++;
        this.actionThisTick = null;
        if (!this.usingItem) {
            return;
        }
        InventoryState inventory = this.session.get(InventoryState.KEY);
        if (inventory.selectedSlot() != this.useSlot) {
            this.usingItem = false;
        } else if (this.drinking && ++this.useTicks >= DRINK_TICKS) {
            this.usingItem = false;
            sendUse(inventory);
        }
    }

    /** Java {@code UseItem} (right click in the air) with the main hand. */
    void useItem() {
        InventoryState inventory = this.session.get(InventoryState.KEY);
        sendUse(inventory);
        ItemData held = inventory.heldItem();
        String identifier = held.getDefinition() == null ? "" : held.getDefinition().getIdentifier();
        this.usingItem = true;
        this.drinking = "minecraft:potion".equals(identifier) || "minecraft:milk_bucket".equals(identifier)
                || "minecraft:ominous_bottle".equals(identifier);
        this.useTicks = 0;
        this.useSlot = inventory.selectedSlot();
        this.player.input().startUsingItem();
        acted(ActorSwingSource.USE_ITEM);
    }

    private void sendUse(InventoryState inventory) {
        ItemUseInventoryTransaction transaction = itemUse(ItemUseActionType.USE, inventory);
        transaction.setTriggerType(ItemUseTriggerType.PLAYER_INPUT);
        transaction.setPosition(Vector3i.ZERO);
        transaction.setFace(-1);
        transaction.setClickPosition(Vector3f.ZERO);
        transaction.setTargetBlockId(new BlockIds(this.session.get(BlockMapper.KEY).bedrockAir()));
        transaction.setClientInteractPrediction(ItemUsePredictedResult.FAILURE);
        send(transaction);
    }

    /** Java {@code RELEASE_USE_ITEM}: bow, crossbow, trident... or eating interrupted. */
    void releaseItem() {
        this.usingItem = false;
        InventoryState inventory = this.session.get(InventoryState.KEY);
        ItemReleaseInventoryTransaction transaction = new ItemReleaseInventoryTransaction();
        transaction.setActions(new InventoryTransaction());
        transaction.setActionType(ItemReleaseActionType.RELEASE);
        transaction.setSlot(inventory.selectedSlot());
        transaction.setItem(inventory.heldItem());
        transaction.setFromPosition(this.player.input().eyePosition());
        transaction.setHand(HandSlot.MAINHAND);
        send(transaction);
    }

    /** The server finished the item use ({@code CompletedUsingItem}). */
    void onUseCompleted() {
        this.usingItem = false;
    }

    /**
     * Click on a block with the main hand (Java {@code UseItemOn}, or the use of an item frame entity).
     * PowerNukkitX only places a block when the client predicted success, so a click that may place
     * predicts {@code SUCCESS} (the server checks); one that must not (an item frame) predicts
     * {@code FAILURE}, which still lets the block react.
     */
    void useItemOn(Vector3i position, int face, float cursorX, float cursorY, float cursorZ, int targetRuntimeId, boolean place) {
        InventoryState inventory = this.session.get(InventoryState.KEY);
        ItemUseInventoryTransaction transaction = itemUse(ItemUseActionType.PLACE, inventory);
        transaction.setTriggerType(ItemUseTriggerType.PLAYER_INPUT);
        transaction.setPosition(position);
        transaction.setFace(face);
        transaction.setClickPosition(Vector3f.from(cursorX, cursorY, cursorZ));
        transaction.setTargetBlockId(new BlockIds(targetRuntimeId));
        transaction.setClientInteractPrediction(place ? ItemUsePredictedResult.SUCCESS : ItemUsePredictedResult.FAILURE);
        send(transaction);
        acted(ActorSwingSource.BUILD);
    }

    /** Java {@code STAB} (spear jab): Bedrock "use as attack". */
    void stab() {
        InventoryState inventory = this.session.get(InventoryState.KEY);
        ItemUseInventoryTransaction transaction = itemUse(ItemUseActionType.USE_AS_ATTACK, inventory);
        transaction.setTriggerType(ItemUseTriggerType.PLAYER_INPUT);
        transaction.setPosition(Vector3i.ZERO);
        transaction.setFace(-1);
        transaction.setClickPosition(Vector3f.ZERO);
        transaction.setTargetBlockId(new BlockIds(this.session.get(BlockMapper.KEY).bedrockAir()));
        transaction.setClientInteractPrediction(ItemUsePredictedResult.SUCCESS);
        send(transaction);
        acted(ActorSwingSource.ATTACK);
    }

    private ItemUseInventoryTransaction itemUse(ItemUseActionType type, InventoryState inventory) {
        ItemUseInventoryTransaction transaction = new ItemUseInventoryTransaction();
        transaction.setActions(new InventoryTransaction());
        transaction.setActionType(type);
        transaction.setSlot(inventory.selectedSlot());
        transaction.setHand(HandSlot.MAINHAND);
        transaction.setItem(inventory.heldItem());
        transaction.setFromPosition(this.player.input().eyePosition());
        transaction.setClientCooldownState(ItemUseClientCooldownState.OFF);
        return transaction;
    }

    /**
     * Java {@code Attack} / {@code Interact} on an entity.
     *
     * @param hit where the entity was clicked
     */
    void useOnEntity(long runtimeId, ItemUseOnActorActionType type, Vector3f hit) {
        InventoryState inventory = this.session.get(InventoryState.KEY);
        ItemUseOnActorInventoryTransaction transaction = new ItemUseOnActorInventoryTransaction();
        transaction.setActions(new InventoryTransaction());
        transaction.setRuntimeId(runtimeId);
        transaction.setActionType(type);
        transaction.setSlot(inventory.selectedSlot());
        transaction.setHand(HandSlot.MAINHAND);
        transaction.setItem(inventory.heldItem());
        transaction.setFromPosition(this.player.input().eyePosition());
        transaction.setHitPosition(hit);
        send(transaction);
        acted(type == ItemUseOnActorActionType.ATTACK ? ActorSwingSource.ATTACK : ActorSwingSource.INTERACT);
    }

    private void send(InventoryTransactionData transaction) {
        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setLegacyRequestID(new ItemStackLegacyRequestId(0));
        packet.setTransaction(transaction);
        this.session.sendBedrock(packet);
    }

    static void interact(ProxySession session, InteractPacket.Action action, long targetRuntimeId) {
        InteractPacket interact = new InteractPacket();
        interact.setAction(action);
        interact.setTargetRuntimeID(targetRuntimeId);
        session.sendBedrock(interact);
    }

    /** Forgets in-progress actions (death). */
    void reset() {
        this.usingItem = false;
        this.actionThisTick = null;
    }

    /** Block definitions for transactions: the codec only writes the runtime id. */
    private record BlockIds(int runtimeId) implements BlockDefinition {

        @Override
        public int getRuntimeId() {
            return this.runtimeId;
        }
    }
}
