package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BedrockBlockPalette;
import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.entity.EntityDefinitions;
import io.github.wyze3306.jtb.entity.JavaEntityDefinition;
import io.github.wyze3306.jtb.entity.MetaField;
import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.TestItems;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bedrock item frame blocks shown as Java item frame entities. */
class ItemFramesTest {

    private static BlockMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = TestSessions.mapper();
    }

    private static NbtMap frameEntity(String item, Object rotation) {
        NbtMapBuilder builder = NbtMap.builder().putString("id", "ItemFrame")
                .putCompound("Item", NbtMap.builder().putString("Name", item).putByte("Count", (byte) 1).putShort("Damage", (short) 0).build())
                .putFloat("ItemDropChance", 1f);
        builder.put("ItemRotation", rotation);
        return builder.build();
    }

    @Test
    void frameBlocksAreAirOnJavaAndKnowTheirFacing() {
        int frames = 0;
        for (int index = 0; index < BedrockBlockPalette.size(); index++) {
            String name = BedrockBlockPalette.name(index);
            if (!name.equals("minecraft:frame") && !name.equals("minecraft:glow_frame")) {
                assertEquals(-1, BedrockBlockPalette.itemFrame(index), name);
                continue;
            }
            frames++;
            int runtimeId = BedrockBlockPalette.hash(index);
            int facing = ((Number) BedrockBlockPalette.stateValue(index, "facing_direction")).intValue();
            int expected = facing | (name.equals("minecraft:glow_frame") ? BedrockBlockPalette.GLOW_FRAME : 0);
            assertEquals(expected, mapper.itemFrame(runtimeId), BedrockBlockPalette.state(index)::toString);
            // The entity is the frame: no block (and no placeholder) where it hangs.
            assertEquals(JavaBlockStates.AIR, mapper.toJava(runtimeId), BedrockBlockPalette.state(index)::toString);
        }
        // Two blocks, six facings, map and photo bits.
        assertEquals(2 * 6 * 4, frames);
        assertEquals(-1, mapper.itemFrame(mapper.toBedrock(JavaBlockStates.parse("stone"))));
        assertEquals(-1, mapper.itemFrame(mapper.bedrockAir()));
    }

    @Test
    void rotationReadsDegreesAndSteps() {
        assertEquals(0, ItemFrames.rotation(null));
        assertEquals(0, ItemFrames.rotation(NbtMap.EMPTY));
        // Bedrock: degrees in a float.
        assertEquals(1, ItemFrames.rotation(NbtMap.builder().putFloat("ItemRotation", 45f).build()));
        assertEquals(2, ItemFrames.rotation(NbtMap.builder().putFloat("ItemRotation", 90f).build()));
        assertEquals(7, ItemFrames.rotation(NbtMap.builder().putFloat("ItemRotation", 315f).build()));
        assertEquals(0, ItemFrames.rotation(NbtMap.builder().putFloat("ItemRotation", 360f).build()));
        assertEquals(7, ItemFrames.rotation(NbtMap.builder().putFloat("ItemRotation", -45f).build()));
        // PowerNukkitX: the step count in a byte.
        assertEquals(3, ItemFrames.rotation(NbtMap.builder().putByte("ItemRotation", (byte) 3).build()));
        assertEquals(2, ItemFrames.rotation(NbtMap.builder().putInt("ItemRotation", 90).build()));
    }

    @Test
    void emptyFramesHaveNoItem() {
        assertNull(ItemFrames.item(null));
        assertNull(ItemFrames.item(NbtMap.builder().putString("id", "ItemFrame").build()));
        assertNull(ItemFrames.item(frameEntity("minecraft:air", (byte) 0)));
        assertNull(ItemFrames.item(NbtMap.builder().putCompound("Item", NbtMap.builder().putString("Name", "minecraft:diamond")
                .putByte("Count", (byte) 0).build()).build()));
        NbtMap item = ItemFrames.item(frameEntity("minecraft:diamond", (byte) 0));
        assertNotNull(item);
        assertEquals("minecraft:diamond", item.getString("Name"));
    }

    @Test
    void clickPositionIsOnTheFrame() {
        // A frame facing north hangs on the south side of its block: the entity is 0.46875 south of the centre.
        ItemFrame north = new ItemFrame(Vector3i.from(1, 2, 3), 2, false, false);
        assertEquals(Vector3f.from(0.5f, 0.5f, 0.96875f), north.clickPosition(null));
        Vector3f click = north.clickPosition(Vector3d.from(0.1, -0.2, -0.03125));
        assertEquals(0.6f, click.getX(), 1e-6f);
        assertEquals(0.3f, click.getY(), 1e-6f);
        assertEquals(0.9375f, click.getZ(), 1e-6f);
        // On the floor (facing up), clamped into the block.
        ItemFrame up = new ItemFrame(Vector3i.ZERO, 1, false, false);
        assertEquals(Vector3f.from(0.5f, 0f, 0.5f), up.clickPosition(Vector3d.from(0, -0.5, 0)));
    }

    @Test
    void framesAreSpawnedUpdatedAndRemoved() {
        TestItems.TestSession test = TestItems.session();
        ItemFrames frames = new ItemFrames(test.session());
        JavaEntityDefinition definition = EntityDefinitions.forJava(EntityType.ITEM_FRAME);
        int itemIndex = definition.index(MetaField.ITEM);
        int rotationIndex = definition.index(MetaField.ROTATION);
        int directionIndex = definition.index(MetaField.DIRECTION);
        assertTrue(itemIndex >= 0 && rotationIndex >= 0 && directionIndex >= 0);

        frames.spawn(10, 64, -3, 2, frameEntity("minecraft:diamond", (byte) 0));
        ClientboundAddEntityPacket add = test.sent(ClientboundAddEntityPacket.class).getFirst();
        assertEquals(EntityType.ITEM_FRAME, add.getType());
        assertEquals(Direction.NORTH, add.getData());
        assertEquals(10, add.getX());
        assertEquals(64, add.getY());
        assertEquals(-3, add.getZ());
        int id = add.getEntityId();
        Map<Integer, Object> data = data(test, id);
        assertEquals(Direction.NORTH, data.get(directionIndex));
        ItemStack shown = (ItemStack) data.get(itemIndex);
        assertNotNull(shown);
        assertEquals(ItemMappings.get().javaId("diamond"), shown.getId());
        assertFalse(data.containsKey(rotationIndex), "default rotation is not sent");
        assertEquals(new ItemFrame(Vector3i.from(10, 64, -3), 2, false, true), frames.frame(id));

        // Rotated: only the rotation is sent.
        test.javaPackets().clear();
        frames.update(10, 64, -3, frameEntity("minecraft:diamond", 90f));
        List<ClientboundSetEntityDataPacket> updates = test.sent(ClientboundSetEntityDataPacket.class);
        assertEquals(1, updates.size());
        assertEquals(1, updates.getFirst().getMetadata().length);
        assertEquals(2, data(test, id).get(rotationIndex));

        // Item taken out (PowerNukkitX then sends no Item at all).
        test.javaPackets().clear();
        frames.update(10, 64, -3, NbtMap.builder().putString("id", "ItemFrame").putByte("ItemRotation", (byte) 0).build());
        data = data(test, id);
        assertTrue(data.containsKey(itemIndex));
        assertNull(data.get(itemIndex));
        assertEquals(0, data.get(rotationIndex));
        assertFalse(frames.frame(id).hasItem());

        // Nothing changed: nothing sent.
        test.javaPackets().clear();
        frames.update(10, 64, -3, NbtMap.builder().putString("id", "ItemFrame").build());
        assertTrue(test.javaPackets().isEmpty());

        frames.remove(10, 64, -3);
        assertArrayEquals(new int[]{id}, test.sent(ClientboundRemoveEntitiesPacket.class).getFirst().getEntityIds());
        assertNull(frames.frame(id));
        assertEquals(0, frames.size());
    }

    @Test
    void glowFramesAndReplacedFrames() {
        TestItems.TestSession test = TestItems.session();
        ItemFrames frames = new ItemFrames(test.session());
        frames.spawn(0, 70, 0, 3 | BedrockBlockPalette.GLOW_FRAME, null);
        ClientboundAddEntityPacket add = test.sent(ClientboundAddEntityPacket.class).getFirst();
        assertEquals(EntityType.GLOW_ITEM_FRAME, add.getType());
        assertEquals(Direction.SOUTH, add.getData());
        assertEquals(new ItemFrame(Vector3i.from(0, 70, 0), 3, true, false), frames.frame(add.getEntityId()));

        // Another frame in the same block (turned): the old entity goes, a new one comes.
        test.javaPackets().clear();
        frames.spawn(0, 70, 0, 5, null);
        assertArrayEquals(new int[]{add.getEntityId()}, test.sent(ClientboundRemoveEntitiesPacket.class).getFirst().getEntityIds());
        ClientboundAddEntityPacket turned = test.sent(ClientboundAddEntityPacket.class).getFirst();
        assertEquals(EntityType.ITEM_FRAME, turned.getType());
        assertEquals(Direction.EAST, turned.getData());
        assertTrue(turned.getEntityId() != add.getEntityId());
        assertEquals(1, frames.size());

        // A respawn or new login dropped the client's entities: nothing to send.
        test.javaPackets().clear();
        frames.forgetAll();
        assertTrue(test.javaPackets().isEmpty());
        assertNull(frames.frame(turned.getEntityId()));
    }

    private static Map<Integer, Object> data(TestItems.TestSession test, int javaId) {
        Map<Integer, Object> values = new HashMap<>();
        for (ClientboundSetEntityDataPacket packet : test.sent(ClientboundSetEntityDataPacket.class)) {
            if (packet.getEntityId() == javaId) {
                for (EntityMetadata<?, ?> entry : packet.getMetadata()) {
                    values.put(entry.getId(), entry.getValue());
                }
            }
        }
        return values;
    }
}
