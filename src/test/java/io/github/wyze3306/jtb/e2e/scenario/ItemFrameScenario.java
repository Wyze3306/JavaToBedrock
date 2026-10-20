package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.block.BlockData;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.entity.EntityDefinitions;
import io.github.wyze3306.jtb.entity.MetaField;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Item frames (blocks on Bedrock, entities on Java) against PowerNukkitX; the tester must be an operator.
 * The player places a frame on a wall with the frame item and checks it shows as an {@code item_frame}
 * entity facing the right way with no block where it hangs; puts a diamond in (entity data {@code ITEM},
 * one diamond fewer on the server), turns it ({@code ROTATION}), punches it (the diamond drops, the frame
 * is empty), puts a filled map in (shown with its Java map id), takes it out and breaks the frame (the
 * entity goes). A glow frame placed on the floor gets a diamond, survives a far teleport and a trip to
 * the nether (removed with its chunk, shown again with its item when the chunk comes back), then is
 * picked (block pick), emptied and broken in creative mode, with the item frame sounds heard along the
 * way. Run it for 200 s.
 */
public final class ItemFrameScenario extends InventoryScript {

    private static final int FAR = 3000;

    /** A frame entity as the Java client was told about it. */
    private static final class Frame {
        final int id;
        final EntityType type;
        final Vector3i block;
        final Direction direction;
        volatile @Nullable ItemStack item;
        volatile int rotation;
        volatile boolean removed;

        Frame(int id, EntityType type, Vector3i block, Direction direction) {
            this.id = id;
            this.type = type;
            this.block = block;
            this.direction = direction;
        }
    }

    private final int frameItem = id("item_frame");
    private final int glowFrameItem = id("glow_item_frame");
    private final int diamond = id("diamond");
    private final int filledMap = id("filled_map");
    private final int itemIndex = EntityDefinitions.forJava(EntityType.ITEM_FRAME).index(MetaField.ITEM);
    private final int rotationIndex = EntityDefinitions.forJava(EntityType.ITEM_FRAME).index(MetaField.ROTATION);

    private final Map<Integer, Frame> frames = new ConcurrentHashMap<>();
    /** Ids of item entities (drops) spawned so far. */
    private final List<Integer> drops = new CopyOnWriteArrayList<>();
    private final Set<String> sounds = ConcurrentHashMap.newKeySet();
    private final Map<Long, byte[]> chunks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> blockUpdates = new ConcurrentHashMap<>();
    private final AtomicInteger respawns = new AtomicInteger();
    private int sequence = 1000;

    private Vector3i wall;
    private Vector3i framed;
    private Vector3i floor;
    private Vector3i glowFramed;
    private Frame first;
    private Frame glow;
    private int dropsMark;
    private int respawnsMark;
    private volatile @Nullable Integer heldMapId;

    public ItemFrameScenario() {
        BlockData.load();
    }

    // ------------------------------------------------------------------ packets (network thread)

    @Override
    public synchronized void onPacket(ClientContext context, Packet packet) {
        super.onPacket(context, packet);
        if (packet instanceof ClientboundAddEntityPacket add) {
            if (add.getType() == EntityType.ITEM_FRAME || add.getType() == EntityType.GLOW_ITEM_FRAME) {
                Vector3i block = Vector3i.from(Math.floor(add.getX()), Math.floor(add.getY()), Math.floor(add.getZ()));
                Frame frame = new Frame(add.getEntityId(), add.getType(), block, (Direction) add.getData());
                this.frames.put(add.getEntityId(), frame);
                context.log("Frame entity " + add.getEntityId() + " " + add.getType() + " at " + block + " facing " + add.getData());
            } else if (add.getType() == EntityType.ITEM) {
                this.drops.add(add.getEntityId());
            }
        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
            Frame frame = this.frames.get(data.getEntityId());
            if (frame != null) {
                for (EntityMetadata<?, ?> entry : data.getMetadata()) {
                    if (entry.getId() == this.itemIndex) {
                        frame.item = (ItemStack) entry.getValue();
                    } else if (entry.getId() == this.rotationIndex) {
                        frame.rotation = (Integer) entry.getValue();
                    }
                }
                context.log("Frame entity " + frame.id + " item " + describe(frame.item) + " rotation " + frame.rotation);
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : remove.getEntityIds()) {
                Frame frame = this.frames.get(id);
                if (frame != null) {
                    frame.removed = true;
                    context.log("Frame entity " + id + " removed");
                }
            }
        } else if (packet instanceof ClientboundRespawnPacket) {
            // The Java client drops every entity on a respawn.
            this.respawns.incrementAndGet();
            for (Frame frame : this.frames.values()) {
                frame.removed = true;
            }
            this.chunks.clear();
        } else if (packet instanceof ClientboundSoundPacket sound) {
            this.sounds.add(sound.getSound().getName());
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            this.chunks.put(chunkKey(chunk.getX(), chunk.getZ()), chunk.getChunkData());
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            this.chunks.remove(chunkKey(forget.getX(), forget.getZ()));
        } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            blockUpdate(update.getEntry());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            for (BlockChangeEntry entry : update.getEntries()) {
                blockUpdate(entry);
            }
        }
    }

    private void blockUpdate(BlockChangeEntry entry) {
        this.blockUpdates.put(blockKey(entry.getPosition()), entry.getBlock());
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long blockKey(Vector3i p) {
        return ((long) (p.getX() & 0x3FFFFFF) << 38) | ((long) (p.getZ() & 0x3FFFFFF) << 12) | (p.getY() & 0xFFF);
    }

    /** The Java block state the client has at a position (block updates over the chunk), or -1 without the chunk. */
    private int blockAt(Vector3i p) {
        Integer updated = this.blockUpdates.get(blockKey(p));
        if (updated != null) {
            return updated;
        }
        byte[] data = this.chunks.get(chunkKey(p.getX() >> 4, p.getZ() >> 4));
        if (data == null) {
            return -1;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        int index = (p.getY() + 64) >> 4;
        ChunkSection section = null;
        for (int i = 0; i <= index; i++) {
            section = MinecraftTypes.readChunkSection(buf, JavaBlockStates.count(), 67);
        }
        return section == null ? -1 : section.getBlock(p.getX() & 15, p.getY() & 15, p.getZ() & 15);
    }

    private @Nullable Frame frameAt(Vector3i block, EntityType type) {
        Frame found = null;
        for (Frame frame : this.frames.values()) {
            if (!frame.removed && frame.type == type && frame.block.equals(block) && (found == null || frame.id > found.id)) {
                found = frame;
            }
        }
        return found;
    }

    private static boolean holds(@Nullable Frame frame, int itemId) {
        ItemStack item = frame == null ? null : frame.item;
        return item != null && item.getId() == itemId && item.getAmount() > 0;
    }

    private static boolean empty(@Nullable Frame frame) {
        ItemStack item = frame == null ? null : frame.item;
        return frame != null && (item == null || item.getAmount() <= 0);
    }

    private static @Nullable Integer mapId(@Nullable ItemStack item) {
        return item == null || item.getDataComponentsPatch() == null ? null : item.getDataComponentsPatch().get(DataComponentTypes.MAP_ID);
    }

    private static String describe(@Nullable ItemStack item) {
        return item == null ? "none" : item.getId() + "x" + item.getAmount() + (mapId(item) != null ? " map " + mapId(item) : "");
    }

    // ------------------------------------------------------------------ actions

    private void useOn(Vector3i block, Direction face) {
        float x = face == Direction.WEST ? 0 : face == Direction.EAST ? 1 : 0.5f;
        float y = face == Direction.DOWN ? 0 : face == Direction.UP ? 1 : 0.5f;
        float z = face == Direction.NORTH ? 0 : face == Direction.SOUTH ? 1 : 0.5f;
        this.context.send(new ServerboundUseItemOnPacket(block, face, Hand.MAIN_HAND, x, y, z, false, false, ++this.sequence));
    }

    private void interact(Frame frame) {
        // The hit point on the frame's front, relative to the entity (what the Java client sends).
        this.context.send(new ServerboundInteractPacket(frame.id, Hand.MAIN_HAND, Vector3d.from(0.1, 0.1, 0.03), false));
        this.context.send(ServerboundPunchPacket.INSTANCE);
    }

    private void attack(Frame frame) {
        this.context.send(new ServerboundAttackPacket(frame.id));
        this.context.send(ServerboundPunchPacket.INSTANCE);
    }

    private void select(int slot) {
        this.context.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /** A drop spawned since the mark. */
    private boolean dropped() {
        return this.drops.size() > this.dropsMark;
    }

    private void markDrops() {
        this.dropsMark = this.drops.size();
    }

    // ------------------------------------------------------------------ script

    @Override
    protected void script() {
        this.wall = this.feet.add(0, 1, 3);
        this.framed = this.wall.add(0, 0, -1);
        this.floor = this.feet.add(-2, 0, 2);
        this.glowFramed = this.floor.add(0, 1, 0);

        step("look south", 5, () -> this.context.send(new ServerboundMovePlayerRotPacket(true, false, 0f, 20f)));
        step("give", 30, () -> {
            command("give " + this.name + " frame 1");
            command("give " + this.name + " glow_frame 1");
            command("give " + this.name + " diamond 2");
            command("give " + this.name + " filled_map 1");
            setblock(this.wall, "stone");
            setblock(this.floor, "stone");
        });
        expect("given: frame, glow frame, 2 diamonds, filled map in the hotbar", () -> is(0, 36, this.frameItem, 1)
                && is(0, 37, this.glowFrameItem, 1) && is(0, 38, this.diamond, 2) && is(0, 39, this.filledMap, 1));

        // -------------------------------------------------------------- a frame on the wall
        step("hold frame", 40, () -> select(0));
        step("place frame", 10, () -> useOn(this.wall, Direction.NORTH));
        expect("frame placed on the wall: an item_frame entity facing north", () -> {
            this.first = frameAt(this.framed, EntityType.ITEM_FRAME);
            return this.first != null && this.first.direction == Direction.NORTH;
        });
        expect("no block shown where the frame hangs", () -> {
            int state = blockAt(this.framed);
            return state >= 0 && JavaBlockStates.isAir(state);
        });
        serverCount("frame", 0);
        // PowerNukkitX keeps the block entity of a frame broken by an earlier run: empty it first.
        step("empty a leftover frame", 30, () -> {
            if (!empty(this.first)) {
                this.context.log("The new frame holds " + describe(this.first.item) + ": punching it out");
                attack(this.first);
            }
        });
        expect("the new frame is empty", () -> empty(this.first));
        step("clear drops", 5, () -> command("kill @e[type=item]"));

        step("hold diamond", 40, () -> select(2));
        step("put diamond", 10, () -> interact(this.first));
        expect("use with a diamond: the frame shows it (ITEM)", () -> holds(this.first, this.diamond));
        serverCount("diamond", 1);
        step("rotate", 10, () -> interact(this.first));
        expect("use again: the diamond turns (ROTATION 1)", () -> this.first.rotation == 1 && holds(this.first, this.diamond));
        serverCount("diamond", 1);
        step("punch", 10, () -> {
            markDrops();
            attack(this.first);
        });
        expect("punch: the frame is empty and the diamond dropped", () -> empty(this.first) && dropped()
                && this.first.rotation == 0);
        step("clear diamond drop", 5, () -> command("kill @e[type=item]"));

        step("hold map", 40, () -> {
            this.heldMapId = mapId(item(0, 39));
            select(3);
        });
        step("put map", 10, () -> interact(this.first));
        expect("use with a filled map: the frame shows the map with its Java map id", () -> {
            Integer framedMap = mapId(this.first.item);
            return holds(this.first, this.filledMap) && framedMap != null && framedMap.equals(this.heldMapId);
        });
        serverCount("filled_map", 0);
        step("punch map", 10, () -> {
            markDrops();
            attack(this.first);
        });
        expect("punch: the map dropped", () -> empty(this.first) && dropped());
        step("clear map drop", 5, () -> command("kill @e[type=item]"));
        step("break frame", 10, () -> {
            markDrops();
            attack(this.first);
        });
        expect("punching the empty frame breaks it: the entity is removed", () -> this.first.removed);
        expect("the broken frame dropped as an item", this::dropped);
        expect("still no block where the frame was", () -> {
            int state = blockAt(this.framed);
            return state >= 0 && JavaBlockStates.isAir(state);
        });
        step("clear frame drop", 5, () -> command("kill @e[type=item]"));
        expect("item frame sounds played (place, add, rotate, remove)", () -> this.sounds.contains("entity.item_frame.place")
                && this.sounds.contains("entity.item_frame.add_item") && this.sounds.contains("entity.item_frame.rotate_item")
                && (this.sounds.contains("entity.item_frame.remove_item") || this.sounds.contains("entity.item_frame.break")));

        // -------------------------------------------------------------- a glow frame on the floor
        step("hold glow frame", 40, () -> select(1));
        step("place glow frame", 10, () -> useOn(this.floor, Direction.UP));
        expect("glow frame placed on the floor: a glow_item_frame entity facing up", () -> {
            this.glow = frameAt(this.glowFramed, EntityType.GLOW_ITEM_FRAME);
            return this.glow != null && this.glow.direction == Direction.UP;
        });
        step("empty a leftover glow frame", 30, () -> {
            if (!empty(this.glow)) {
                attack(this.glow);
            }
        });
        expect("the new glow frame is empty", () -> empty(this.glow));
        step("clear leftover drop", 5, () -> command("kill @e[type=item]"));
        step("hold last diamond", 40, () -> select(2));
        step("put last diamond", 10, () -> interact(this.glow));
        expect("glow frame shows the diamond", () -> holds(this.glow, this.diamond));
        serverCount("diamond", 0);

        // The chunk goes away with the player and comes back: the frame entity follows it.
        step("teleport far", 100, () -> command("tp " + this.name + " " + FAR + " 120 " + FAR));
        expect("far away: the glow frame entity left with its chunk", () -> this.glow.removed);
        step("teleport back", 100, () -> command("tp " + this.name + " " + this.feet.getX() + ".5 " + this.feet.getY() + " "
                + this.feet.getZ() + ".5"));
        expect("back: the glow frame is shown again from the chunk, diamond included", () -> {
            Frame again = frameAt(this.glowFramed, EntityType.GLOW_ITEM_FRAME);
            if (again != null) {
                this.glow = again;
            }
            return again != null && again.direction == Direction.UP && holds(again, this.diamond);
        });

        // Dimension change: the Java client drops its entities, the proxy forgets its frames.
        step("nether", 120, () -> {
            this.respawnsMark = this.respawns.get();
            command("world tp world_the_nether");
        });
        expect("in the nether (Java respawn)", () -> this.respawns.get() > this.respawnsMark);
        step("overworld", 120, () -> {
            this.respawnsMark = this.respawns.get();
            command("world tp world");
        });
        expect("back in the overworld (Java respawn)", () -> this.respawns.get() > this.respawnsMark);
        step("teleport to the frames", 100, () -> command("tp " + this.name + " " + this.feet.getX() + ".5 " + this.feet.getY() + " "
                + this.feet.getZ() + ".5"));
        expect("after the dimension trip: the glow frame is shown again with its diamond", () -> {
            Frame again = frameAt(this.glowFramed, EntityType.GLOW_ITEM_FRAME);
            if (again != null) {
                this.glow = again;
            }
            return again != null && holds(again, this.diamond);
        });

        // -------------------------------------------------------------- creative
        step("creative", 2, () -> command("gamemode creative " + this.name));
        expect("creative mode", () -> this.gameMode == GameMode.CREATIVE);
        step("hold nothing", 40, () -> select(5));
        step("pick the glow frame", 10, () -> this.context.send(new ServerboundPickItemFromEntityPacket(this.glow.id, false)));
        expect("creative pick on the frame entity: a glow frame item in the hotbar (block pick)",
                () -> total(0, 36, 45, this.glowFrameItem) == 1);
        step("creative punch", 10, () -> {
            markDrops();
            attack(this.glow);
        });
        expect("creative punch: the glow frame is emptied", () -> empty(this.glow));
        step("creative break", 10, () -> attack(this.glow));
        expect("creative punch on the empty glow frame: broken at once, entity removed", () -> this.glow.removed);
        expect("creative: nothing dropped", () -> !dropped());
        expect("no block where the glow frame was", () -> {
            int state = blockAt(this.glowFramed);
            return state >= 0 && JavaBlockStates.isAir(state);
        });
        step("survival", 2, () -> command("gamemode survival " + this.name));
        expect("survival mode", () -> this.gameMode == GameMode.SURVIVAL);
        step("done", 1, () -> this.context.log("Item frame scenario finished; sounds " + this.sounds.stream()
                .filter(sound -> sound.contains("frame")).sorted().toList()));
    }
}
