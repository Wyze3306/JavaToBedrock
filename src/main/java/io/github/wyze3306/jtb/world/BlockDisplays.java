package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.CustomBlockDisplay;
import io.github.wyze3306.jtb.entity.EntityDefinitions;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.entity.JavaEntityDefinition;
import io.github.wyze3306.jtb.entity.MetaField;
import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import io.github.wyze3306.jtb.resourcepack.ResourcePackState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDelimiterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Custom block states without a Java carrier ({@link CustomBlockDisplay}), shown by {@code item_display}
 * entities, driven by {@link ChunkManager} like {@link ItemFrames}.
 * <p>
 * Java has a few thousand carrier states for custom blocks; servers with more looks than that (tens of
 * thousands of states) get the rest drawn by an entity: the Java client has an invisible block there
 * ({@code barrier}: collision, targeting; {@code structure_void} for blocks without collision) and an
 * item display at the block's centre holds {@code minecraft:paper} with the look's item model
 * ({@link JavaAssetNames#blockDisplayModel}), rotated like the blockstate variant would be. Spawned with
 * their column, replaced or removed with their block, removed with their chunk; a dimension change or
 * server switch only forgets them. Only shown when the client loaded the converted pack and it has the
 * look (otherwise the invisible block is all there is). Session loop only.
 */
final class BlockDisplays {

    /** Most significant UUID bits of the display entities ("jtbd", version 4); the low bits are the Java id. */
    private static final long UUID_MOST_BITS = 0x6A74_6264_0000_4000L;
    private static final ClientboundDelimiterPacket BUNDLE_DELIMITER = new ClientboundDelimiterPacket(Unpooled.EMPTY_BUFFER);
    private static final int ITEM_DISPLAY_CONTEXT_NONE = 0;

    private final ProxySession session;
    /** Block position -> Java entity id. */
    private final Long2IntOpenHashMap byPosition = new Long2IntOpenHashMap();
    private final HashMap<Integer, ItemStack> items = new HashMap<>();

    BlockDisplays(ProxySession session) {
        this.session = session;
        this.byPosition.defaultReturnValue(-1);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Number of display entities the client has. */
    int size() {
        return this.byPosition.size();
    }

    /** Shows display {@code id} in the block at a position; one already there is replaced. */
    void spawn(int x, int y, int z, int id) {
        remove(x, y, z);
        List<CustomBlockDisplay> displays = this.session.get(BlockMapper.KEY).blockDisplays();
        ItemStack item = id >= 0 && id < displays.size() ? item(id) : null;
        if (item == null) {
            return;
        }
        CustomBlockDisplay display = displays.get(id);
        int javaId = this.session.get(EntityTracker.KEY).allocateJavaId();
        this.byPosition.put(key(x, y, z), javaId);
        JavaEntityDefinition definition = EntityDefinitions.forJava(EntityType.ITEM_DISPLAY);
        EntityMetadata<?, ?>[] metadata = {
                new ObjectEntityMetadata<>(definition.index(MetaField.ITEM_STACK), MetadataTypes.ITEM_STACK, item),
                new ByteEntityMetadata(definition.index(MetaField.ITEM_DISPLAY), MetadataTypes.BYTE, (byte) ITEM_DISPLAY_CONTEXT_NONE)};
        // An item display turns its item half a turn around Y: yaw y + 180 and pitch x give the
        // blockstate variant rotation (rotY(-y) * rotX(-x)) of the model.
        float yaw = display.rotationY() + 180f;
        float pitch = display.rotationX();
        this.session.sendJava(BUNDLE_DELIMITER);
        this.session.sendJava(new ClientboundAddEntityPacket(javaId, new UUID(UUID_MOST_BITS, javaId), EntityType.ITEM_DISPLAY,
                x + 0.5, y + 0.5, z + 0.5, yaw, pitch, yaw));
        this.session.sendJava(new ClientboundSetEntityDataPacket(javaId, metadata));
        this.session.sendJava(BUNDLE_DELIMITER);
    }

    /** Removes the display entity at a position, if any. */
    void remove(int x, int y, int z) {
        int javaId = this.byPosition.remove(key(x, y, z));
        if (javaId >= 0) {
            this.session.sendJava(new ClientboundRemoveEntitiesPacket(new int[]{javaId}));
        }
    }

    /** Forgets every display without telling the client (it dropped its entities: respawn, new login). */
    void forgetAll() {
        this.byPosition.clear();
        this.items.clear();
    }

    /** Paper with the look's item model, or null when the client has no such model. */
    private @Nullable ItemStack item(int id) {
        ItemStack cached = this.items.get(id);
        if (cached != null) {
            return cached;
        }
        ConversionArtifacts artifacts = this.session.get(ResourcePackState.KEY).artifacts();
        int paper = ItemMappings.get().javaId("paper");
        if (artifacts == null || !artifacts.hasBlockDisplay(id) || paper < 0) {
            return null;
        }
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.ITEM_MODEL, Key.key(JavaAssetNames.blockDisplayModel(id)));
        ItemStack item = new ItemStack(paper, 1, components);
        this.items.put(id, item);
        return item;
    }
}
