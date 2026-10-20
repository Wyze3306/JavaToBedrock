package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BedrockBlockPalette;
import io.github.wyze3306.jtb.entity.EntityDefinitions;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.entity.JavaEntityDefinition;
import io.github.wyze3306.jtb.entity.MetaField;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDelimiterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bedrock item frames shown as Java entities, driven by {@link ChunkManager}.
 * <p>
 * Bedrock frames are blocks ({@code minecraft:frame}, {@code minecraft:glow_frame}, shown as air on Java)
 * whose block entity holds the item ({@code Item}, item NBT) and its rotation ({@code ItemRotation});
 * Java frames are entities. Every frame block of a chunk the client has is an {@code item_frame} or
 * {@code glow_item_frame} entity hanging in that block and facing like it, whose {@code ITEM} and
 * {@code ROTATION} follow the block entity. The chunk manager spawns a column's frames once the column is
 * sent, keeps them in step with block and block entity changes, and removes them with their block or
 * their chunk; a dimension change or server switch only forgets them (the Java client drops its
 * entities). Java ids come from {@link EntityTracker#allocateJavaId()}, so they never collide with the
 * other entities. Session loop only.
 */
final class ItemFrames {

    /** Most significant UUID bits of frame entities ("jtbf", version 4); the low bits are the Java id. */
    private static final long UUID_MOST_BITS = 0x6A74_6266_0000_4000L;
    private static final ClientboundDelimiterPacket BUNDLE_DELIMITER = new ClientboundDelimiterPacket(Unpooled.EMPTY_BUFFER);
    private static final Direction[] DIRECTIONS = Direction.values();

    /** A frame entity the client has. */
    private static final class Shown {

        final Vector3i position;
        final int frame;
        final int javaId;
        /** The Bedrock item shown, null when empty. */
        @Nullable NbtMap item;
        int rotation;

        Shown(Vector3i position, int frame, int javaId) {
            this.position = position;
            this.frame = frame;
            this.javaId = javaId;
        }

        ItemFrame contract() {
            return new ItemFrame(this.position, this.frame & 7, (this.frame & BedrockBlockPalette.GLOW_FRAME) != 0, this.item != null);
        }
    }

    private final ProxySession session;
    private final Long2ObjectOpenHashMap<Shown> byPosition = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Shown> byJavaId = new Int2ObjectOpenHashMap<>();

    ItemFrames(ProxySession session) {
        this.session = session;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The frame drawn by Java entity {@code javaId}, or null. */
    @Nullable ItemFrame frame(int javaId) {
        Shown shown = this.byJavaId.get(javaId);
        return shown == null ? null : shown.contract();
    }

    /** Number of frame entities the client has. */
    int size() {
        return this.byPosition.size();
    }

    /**
     * Shows the frame block at a position ({@code frame}: {@code BlockMapper#itemFrame} value) with the
     * item of its block entity; a frame already shown there is replaced.
     */
    void spawn(int x, int y, int z, int frame, @Nullable NbtMap blockEntity) {
        remove(x, y, z);
        int javaId = this.session.get(EntityTracker.KEY).allocateJavaId();
        Shown shown = new Shown(Vector3i.from(x, y, z), frame, javaId);
        this.byPosition.put(key(x, y, z), shown);
        this.byJavaId.put(javaId, shown);
        EntityType type = type(frame);
        Direction direction = DIRECTIONS[Math.min(frame & 7, DIRECTIONS.length - 1)];
        JavaEntityDefinition definition = EntityDefinitions.forJava(type);
        List<EntityMetadata<?, ?>> metadata = new ArrayList<>(3);
        addDirection(metadata, definition, direction);
        shown.item = item(blockEntity);
        shown.rotation = rotation(blockEntity);
        if (shown.item != null) {
            addItem(metadata, definition, javaItem(shown.item));
        }
        if (shown.rotation != 0) {
            addRotation(metadata, definition, shown.rotation);
        }
        // Java places a hanging entity in the block of its position, whatever the fraction.
        this.session.sendJava(BUNDLE_DELIMITER);
        this.session.sendJava(new ClientboundAddEntityPacket(javaId, new UUID(UUID_MOST_BITS, javaId), type, direction,
                x, y, z, Vector3d.ZERO, 0, 0, 0));
        this.session.sendJava(new ClientboundSetEntityDataPacket(javaId, metadata.toArray(new EntityMetadata<?, ?>[0])));
        this.session.sendJava(BUNDLE_DELIMITER);
    }

    /** The block entity of a shown frame changed: sends the item and rotation that differ. */
    void update(int x, int y, int z, @Nullable NbtMap blockEntity) {
        Shown shown = this.byPosition.get(key(x, y, z));
        if (shown == null) {
            return;
        }
        NbtMap item = item(blockEntity);
        int rotation = rotation(blockEntity);
        JavaEntityDefinition definition = EntityDefinitions.forJava(type(shown.frame));
        List<EntityMetadata<?, ?>> metadata = new ArrayList<>(2);
        if (item == null ? shown.item != null : !item.equals(shown.item)) {
            shown.item = item;
            addItem(metadata, definition, item == null ? null : javaItem(item));
        }
        if (rotation != shown.rotation) {
            shown.rotation = rotation;
            addRotation(metadata, definition, rotation);
        }
        if (!metadata.isEmpty()) {
            this.session.sendJava(new ClientboundSetEntityDataPacket(shown.javaId, metadata.toArray(new EntityMetadata<?, ?>[0])));
        }
    }

    /** Removes the frame entity at a position, if any. */
    void remove(int x, int y, int z) {
        Shown shown = this.byPosition.remove(key(x, y, z));
        if (shown != null) {
            this.byJavaId.remove(shown.javaId);
            this.session.sendJava(new ClientboundRemoveEntitiesPacket(new int[]{shown.javaId}));
        }
    }

    /** Forgets every frame without telling the client (it dropped its entities: respawn, new login). */
    void forgetAll() {
        this.byPosition.clear();
        this.byJavaId.clear();
    }

    private static EntityType type(int frame) {
        return (frame & BedrockBlockPalette.GLOW_FRAME) != 0 ? EntityType.GLOW_ITEM_FRAME : EntityType.ITEM_FRAME;
    }

    private @Nullable ItemStack javaItem(NbtMap item) {
        return ItemTranslator.toJava(this.session, ItemTranslator.nbtItem(this.session, item));
    }

    private static void addDirection(List<EntityMetadata<?, ?>> metadata, JavaEntityDefinition definition, Direction direction) {
        int index = definition.index(MetaField.DIRECTION);
        if (index >= 0) {
            metadata.add(new ObjectEntityMetadata<>(index, MetadataTypes.DIRECTION, direction));
        }
    }

    private static void addItem(List<EntityMetadata<?, ?>> metadata, JavaEntityDefinition definition, @Nullable ItemStack item) {
        int index = definition.index(MetaField.ITEM);
        if (index >= 0) {
            metadata.add(new ObjectEntityMetadata<>(index, MetadataTypes.ITEM_STACK, item));
        }
    }

    private static void addRotation(List<EntityMetadata<?, ?>> metadata, JavaEntityDefinition definition, int rotation) {
        int index = definition.index(MetaField.ROTATION);
        if (index >= 0) {
            metadata.add(new IntEntityMetadata(index, MetadataTypes.INT, rotation));
        }
    }

    // ------------------------------------------------------------------ block entity data

    /** The item NBT of a frame's block entity, or null when the frame is empty (no item, air, zero count). */
    static @Nullable NbtMap item(@Nullable NbtMap blockEntity) {
        NbtMap item = blockEntity == null ? null : blockEntity.getCompound("Item", null);
        if (item == null) {
            return null;
        }
        String name = item.getString("Name", "");
        Object count = item.get("Count");
        if (name.isEmpty() || name.equals("minecraft:air") || count instanceof Number number && number.intValue() <= 0) {
            return null;
        }
        return item;
    }

    /**
     * The Java rotation (0-7, 45° steps clockwise) of a frame's block entity. Bedrock stores
     * {@code ItemRotation} as degrees in a float; PowerNukkitX sends the step count in a byte.
     */
    static int rotation(@Nullable NbtMap blockEntity) {
        Object value = blockEntity == null ? null : blockEntity.get("ItemRotation");
        if (value instanceof Float || value instanceof Double) {
            return Math.floorMod(Math.round(((Number) value).floatValue() / 45f), 8);
        }
        if (value instanceof Number number) {
            int steps = number.intValue();
            return steps >= 0 && steps < 8 ? steps : Math.floorMod(Math.round(steps / 45f), 8);
        }
        return 0;
    }
}
