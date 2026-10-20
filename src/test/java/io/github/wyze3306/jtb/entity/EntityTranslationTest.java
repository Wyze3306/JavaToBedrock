package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.mapping.MappingData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.payload.abilities.SerializedAbilitiesData;
import org.cloudburstmc.protocol.bedrock.data.payload.list.PlayerListAddEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.list.PlayerListRemoveEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.move.MoveActorDeltaData;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPaintingPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamColor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity translation on a real session: spawning, entity data (flags, pose, names, per-type data),
 * the entity data cache, moves, removal and the tab list; plus the static tables.
 */
class EntityTranslationTest {

    private static final long ZOMBIE = 7;

    @TempDir
    Path directory;
    private EntityTestSession test;

    @BeforeAll
    static void load() {
        MappingData.load();
        EntityDefinitions.load();
    }

    @BeforeEach
    void open() {
        this.test = new EntityTestSession(this.directory);
    }

    @AfterEach
    void close() {
        this.test.close();
    }

    // ------------------------------------------------------------------ helpers

    private static AddActorPacket addActor(long id, String type, float x, float y, float z, ActorDataMap data) {
        AddActorPacket packet = new AddActorPacket();
        packet.setTargetActorID(id);
        packet.setTargetRuntimeID(id);
        packet.setActorType(type);
        packet.setPosition(Vector3f.from(x, y, z));
        packet.setVelocity(Vector3f.ZERO);
        packet.setRotation(Vector2f.from(10, 90));
        packet.setHeadRotation(90);
        packet.setActorData(data);
        packet.setAttributesList(new ArrayList<>());
        packet.setActorLinks(new ArrayList<>());
        return packet;
    }

    private static ActorDataMap flags(ActorFlags... flags) {
        ActorDataMap data = new ActorDataMap();
        EnumSet<ActorFlags> set = EnumSet.noneOf(ActorFlags.class);
        set.addAll(List.of(flags));
        data.putFlags(set);
        return data;
    }

    private int spawn(long id, String type, ActorDataMap data) {
        this.test.run(() -> SpawnTranslators.addActor(this.test.session, addActor(id, type, 10, 64, 20, data)));
        return this.test.tracker().javaId(id);
    }

    private void setData(long id, ActorDataMap data) {
        SetActorDataPacket packet = new SetActorDataPacket();
        packet.setTargetRuntimeID(id);
        packet.setActorData(data);
        this.test.run(() -> DataTranslators.setActorData(this.test.session, packet));
    }

    private static int index(EntityType type, MetaField field) {
        return EntityDefinitions.forJava(type).index(field);
    }

    // ------------------------------------------------------------------ tables

    @Test
    void javaIndicesFollowTheClassHierarchy() {
        assertEquals(0, index(EntityType.ZOMBIE, MetaField.SHARED_FLAGS));
        assertEquals(6, index(EntityType.ZOMBIE, MetaField.POSE));
        assertEquals(9, index(EntityType.ZOMBIE, MetaField.HEALTH));
        assertEquals(15, index(EntityType.ZOMBIE, MetaField.MOB_FLAGS));
        assertEquals(16, index(EntityType.ZOMBIE, MetaField.BABY));
        assertEquals(18, index(EntityType.SHEEP, MetaField.WOOL));
        assertEquals(19, index(EntityType.VILLAGER, MetaField.VILLAGER_DATA));
        assertEquals(19, index(EntityType.HORSE, MetaField.TYPE_VARIANT));
        assertEquals(16, index(EntityType.PLAYER, MetaField.PLAYER_MODE_CUSTOMISATION));
        assertEquals(-1, index(EntityType.ITEM, MetaField.HEALTH));
        assertTrue(EntityDefinitions.forJava(EntityType.ZOMBIE).isLiving());
        assertFalse(EntityDefinitions.forJava(EntityType.ITEM).isLiving());
        assertTrue(EntityDefinitions.forJava(EntityType.PLAYER).isPlayerLike());
    }

    @Test
    void everyJavaTypeHasADefinition() {
        for (EntityType type : EntityType.values()) {
            JavaEntityDefinition definition = EntityDefinitions.forJava(type);
            assertNotNull(definition, type.name());
            for (int i = 0; i < definition.fieldCount(); i++) {
                assertNotNull(definition.fieldType(i), type + " field " + i);
            }
        }
    }

    @Test
    void bedrockIdentifiersMapToJavaTypes() {
        assertEquals(EntityType.ZOMBIE, EntityDefinitions.forBedrock("minecraft:zombie").type());
        assertEquals(EntityType.VILLAGER, EntityDefinitions.forBedrock("minecraft:villager_v2").type());
        assertEquals(EntityType.ZOMBIFIED_PIGLIN, EntityDefinitions.forBedrock("minecraft:zombie_pigman").type());
        assertEquals(EntityType.END_CRYSTAL, EntityDefinitions.forBedrock("minecraft:ender_crystal").type());
        assertNull(EntityDefinitions.forBedrock("minecraft:agent"));
        assertTrue(EntityDefinitions.isUnmappedVanilla("minecraft:agent"));
        assertNull(EntityDefinitions.forBedrock("mypack:golem"));
        assertFalse(EntityDefinitions.isUnmappedVanilla("mypack:golem"));
        assertEquals("minecraft:kebab", EntityDefinitions.paintingVariant("Kebab"));
        assertEquals("minecraft:in_fire", EntityDefinitions.damageType(6));
    }

    // ------------------------------------------------------------------ spawning and entity data

    @Test
    void spawnsAZombieWithItsData() {
        int javaId = spawn(ZOMBIE, "minecraft:zombie", flags(ActorFlags.ON_FIRE, ActorFlags.BABY, ActorFlags.HAS_GRAVITY));
        assertTrue(javaId > 0);
        assertEquals(ZOMBIE, this.test.tracker().runtimeId(javaId));

        List<ClientboundAddEntityPacket> adds = this.test.sent(ClientboundAddEntityPacket.class);
        assertEquals(1, adds.size());
        ClientboundAddEntityPacket add = adds.getFirst();
        assertEquals(javaId, add.getEntityId());
        assertEquals(EntityType.ZOMBIE, add.getType());
        assertEquals(10, add.getX(), 1e-6);
        assertEquals(64, add.getY(), 1e-6);
        assertEquals(20, add.getZ(), 1e-6);
        assertEquals(90, add.getYaw(), 1e-6);
        assertEquals(10, add.getPitch(), 1e-6);

        Map<Integer, Object> data = this.test.data(javaId);
        assertEquals((byte) 0x01, data.get(index(EntityType.ZOMBIE, MetaField.SHARED_FLAGS)));
        assertEquals(true, data.get(index(EntityType.ZOMBIE, MetaField.BABY)));
        assertEquals(Pose.STANDING, data.get(index(EntityType.ZOMBIE, MetaField.POSE)));
        // Every entry fits the Java 26.3 table of a zombie.
        JavaEntityDefinition zombie = EntityDefinitions.forJava(EntityType.ZOMBIE);
        for (ClientboundSetEntityDataPacket packet : this.test.sent(ClientboundSetEntityDataPacket.class)) {
            for (EntityMetadata<?, ?> entry : packet.getMetadata()) {
                assertSame(zombie.fieldType(entry.getId()), entry.getType(), zombie.fieldName(entry.getId()));
            }
        }
    }

    @Test
    void onlyChangedEntityDataIsSent() {
        int javaId = spawn(ZOMBIE, "minecraft:zombie", flags(ActorFlags.BABY));
        this.test.clear();

        setData(ZOMBIE, flags(ActorFlags.BABY));
        assertTrue(this.test.sent(ClientboundSetEntityDataPacket.class).isEmpty(), "nothing changed");

        setData(ZOMBIE, flags(ActorFlags.BABY, ActorFlags.SNEAKING));
        List<ClientboundSetEntityDataPacket> packets = this.test.sent(ClientboundSetEntityDataPacket.class);
        assertEquals(1, packets.size());
        Map<Integer, Object> data = this.test.data(javaId);
        // Mobs have no sneaking pose on Java: only the shared flag changes.
        assertEquals(Map.of(index(EntityType.ZOMBIE, MetaField.SHARED_FLAGS), (byte) 0x02), data);
    }

    @Test
    void sentDataCanBeRebuilt() {
        int javaId = spawn(ZOMBIE, "minecraft:zombie", flags(ActorFlags.BABY, ActorFlags.INVISIBLE));
        Map<Integer, Object> sent = this.test.data(javaId);
        EntityMetadata<?, ?>[] rebuilt = this.test.tracker().entity(ZOMBIE).sentMetadata();
        assertNotNull(rebuilt);
        assertEquals(sent.size(), rebuilt.length);
        JavaEntityDefinition zombie = EntityDefinitions.forJava(EntityType.ZOMBIE);
        for (EntityMetadata<?, ?> entry : rebuilt) {
            assertEquals(sent.get(entry.getId()), entry.getValue(), zombie.fieldName(entry.getId()));
            assertSame(zombie.fieldType(entry.getId()), entry.getType());
        }
    }

    @Test
    void sheepWoolCombinesColourAndShearing() {
        ActorDataMap data = flags(ActorFlags.SHEARED);
        data.putType(ActorDataTypes.COLOR_INDEX, (byte) 14);
        int javaId = spawn(ZOMBIE, "minecraft:sheep", data);
        assertEquals((byte) (14 | 0x10), this.test.data(javaId).get(index(EntityType.SHEEP, MetaField.WOOL)));
    }

    @Test
    void villagerDataUsesJavaRegistryIds() {
        ActorDataMap data = flags();
        data.putType(ActorDataTypes.VARIANT, 1);      // farmer
        data.putType(ActorDataTypes.MARK_VARIANT, 4); // snow
        data.putType(ActorDataTypes.TRADE_TIER, 2);
        int javaId = spawn(ZOMBIE, "minecraft:villager_v2", data);
        VillagerData villager = (VillagerData) this.test.data(javaId).get(index(EntityType.VILLAGER, MetaField.VILLAGER_DATA));
        // Built-in registry ids: villager_profession farmer = 5, villager_type snow = 4.
        assertEquals(5, villager.getProfession());
        assertEquals(4, villager.getType());
        assertEquals(3, villager.getLevel());
    }

    @Test
    void horseVariantPacksColourAndMarkings() {
        ActorDataMap data = flags(ActorFlags.TAMED);
        data.putType(ActorDataTypes.VARIANT, 3);
        data.putType(ActorDataTypes.MARK_VARIANT, 2);
        int javaId = spawn(ZOMBIE, "minecraft:horse", data);
        Map<Integer, Object> values = this.test.data(javaId);
        assertEquals(3 | 2 << 8, values.get(index(EntityType.HORSE, MetaField.TYPE_VARIANT)));
        assertEquals((byte) 0x02, values.get(index(EntityType.HORSE, MetaField.FLAGS)));
    }

    @Test
    void nameTagsBecomeCustomNames() {
        ActorDataMap data = flags();
        data.putType(ActorDataTypes.NAME, "§cBob");
        data.putType(ActorDataTypes.NAMETAG_ALWAYS_SHOW, (byte) 1);
        int javaId = spawn(ZOMBIE, "minecraft:zombie", data);
        Map<Integer, Object> values = this.test.data(javaId);
        Optional<?> name = (Optional<?>) values.get(index(EntityType.ZOMBIE, MetaField.CUSTOM_NAME));
        Component component = (Component) name.orElseThrow();
        assertEquals("Bob", PlainTextComponentSerializer.plainText().serialize(component));
        assertTrue(hasColor(component, NamedTextColor.RED), component.toString());
        assertEquals(true, values.get(index(EntityType.ZOMBIE, MetaField.CUSTOM_NAME_VISIBLE)));

        // No longer always shown, and Bedrock may not show it at all: Java hides it too.
        ActorDataMap hide = new ActorDataMap();
        hide.putType(ActorDataTypes.NAMETAG_ALWAYS_SHOW, (byte) 0);
        setData(ZOMBIE, hide);
        values = this.test.data(javaId);
        assertEquals(Optional.empty(), values.get(index(EntityType.ZOMBIE, MetaField.CUSTOM_NAME)));
        assertEquals(false, values.get(index(EntityType.ZOMBIE, MetaField.CUSTOM_NAME_VISIBLE)));
    }

    private static boolean hasColor(Component component, TextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ movement and removal

    private void moveTo(Float x, Float y, Float z, boolean force) {
        MoveActorDeltaData move = new MoveActorDeltaData();
        move.setActorRuntimeID(ZOMBIE);
        move.setNewPositionX(x);
        move.setNewPositionY(y);
        move.setNewPositionZ(z);
        move.setForceMove(force);
        move.setOnGround(true);
        MoveActorDeltaPacket packet = new MoveActorDeltaPacket();
        packet.setMoveData(move);
        this.test.run(() -> MovementTranslators.moveActorDelta(this.test.session, packet));
    }

    @Test
    void movesAreRelativeWhenSmallAndAbsoluteOtherwise() {
        int javaId = spawn(ZOMBIE, "minecraft:zombie", flags());
        this.test.clear();

        moveTo(10.5f, null, null, false);
        List<ClientboundMoveEntityPosPacket> relative = this.test.sent(ClientboundMoveEntityPosPacket.class);
        assertEquals(1, relative.size());
        assertEquals(javaId, relative.getFirst().getEntityId());
        assertEquals(0.5, relative.getFirst().getMoveX(), 1e-9);
        assertEquals(0, relative.getFirst().getMoveY(), 1e-9);
        assertEquals(0, relative.getFirst().getMoveZ(), 1e-9);

        moveTo(10.5f, null, null, false);
        assertEquals(1, this.test.sent(ClientboundMoveEntityPosPacket.class).size(), "no move, nothing sent");

        moveTo(200f, 70f, null, false);
        List<ClientboundEntityPositionSyncPacket> far = this.test.sent(ClientboundEntityPositionSyncPacket.class);
        assertEquals(1, far.size());
        assertEquals(200, far.getFirst().getEndPosition().getX(), 1e-6);
        assertEquals(70, far.getFirst().getEndPosition().getY(), 1e-6);
        assertEquals(20, far.getFirst().getEndPosition().getZ(), 1e-6);

        moveTo(201f, null, null, true);
        assertEquals(1, this.test.sent(ClientboundTeleportEntityPacket.class).size());
    }

    @Test
    void removalForgetsTheEntity() {
        int javaId = spawn(ZOMBIE, "minecraft:zombie", flags());
        RemoveActorPacket remove = new RemoveActorPacket();
        remove.setTargetActorID(ZOMBIE);
        this.test.run(() -> SpawnTranslators.removeActor(this.test.session, remove));
        List<ClientboundRemoveEntitiesPacket> removed = this.test.sent(ClientboundRemoveEntitiesPacket.class);
        assertEquals(1, removed.size());
        assertArrayEquals(new int[]{javaId}, removed.getFirst().getEntityIds());
        assertNull(this.test.tracker().entity(ZOMBIE));
        assertEquals(-1, this.test.tracker().javaId(ZOMBIE));
    }

    @Test
    void customEntitiesGetAHitBoxAndParts() {
        ActorDataMap data = flags();
        data.putType(ActorDataTypes.WIDTH, 1.5f);
        data.putType(ActorDataTypes.HEIGHT, 2f);
        int javaId = spawn(ZOMBIE, "mypack:golem", data);
        List<ClientboundAddEntityPacket> adds = this.test.sent(ClientboundAddEntityPacket.class);
        assertEquals(EntityType.INTERACTION, adds.getFirst().getType());
        assertEquals(javaId, adds.getFirst().getEntityId());
        assertTrue(adds.stream().anyMatch(add -> add.getType() == EntityType.ITEM_DISPLAY));
        assertTrue(adds.stream().anyMatch(add -> add.getType() == EntityType.TEXT_DISPLAY));
        // Every part maps back to the Bedrock entity (attacks and interactions target it).
        for (ClientboundAddEntityPacket add : adds) {
            assertEquals(ZOMBIE, this.test.tracker().runtimeId(add.getEntityId()));
        }
        Map<Integer, Object> box = this.test.data(javaId);
        assertEquals(1.5f, box.get(index(EntityType.INTERACTION, MetaField.WIDTH)));
        assertEquals(2f, box.get(index(EntityType.INTERACTION, MetaField.HEIGHT)));
    }

    // ------------------------------------------------------------------ players

    @Test
    void playersAreListedBeforeTheyAreSpawned() {
        UUID uuid = UUID.randomUUID();
        PlayerListPacket list = new PlayerListPacket();
        PlayerListAddEntry entry = new PlayerListAddEntry();
        entry.setUuid(uuid);
        entry.setActorUniqueID(50);
        entry.setPlayerName("§bSteve");
        list.getEntries().add(entry);
        this.test.run(() -> DataTranslators.playerList(this.test.session, list));

        List<ClientboundPlayerInfoUpdatePacket> infos = this.test.sent(ClientboundPlayerInfoUpdatePacket.class);
        assertEquals(1, infos.size());
        assertTrue(infos.getFirst().getActions().contains(PlayerListEntryAction.ADD_PLAYER));
        PlayerListEntry info = infos.getFirst().getEntries()[0];
        assertEquals(uuid, info.getProfileId());
        assertEquals("Steve", info.getProfile().getName());
        assertTrue(info.isListed());

        AddPlayerPacket add = new AddPlayerPacket();
        add.setUuid(uuid);
        add.setPlayerName("§bSteve");
        add.setTargetRuntimeID(50);
        add.setTargetActorID(50);
        SerializedAbilitiesData abilities = new SerializedAbilitiesData();
        abilities.setTargetPlayerRawId(50);
        add.setAbilitiesData(abilities);
        add.setPosition(Vector3f.from(1, 65, 2));
        add.setRotation(Vector3f.ZERO);
        add.setVelocity(Vector3f.ZERO);
        add.setPlayerGameType(GameType.SURVIVAL);
        add.setCarriedItem(ItemData.AIR);
        add.setActorData(new ActorDataMap());
        add.setActorLinks(new ArrayList<>());
        this.test.run(() -> SpawnTranslators.addPlayer(this.test.session, add));
        ClientboundAddEntityPacket spawned = this.test.sent(ClientboundAddEntityPacket.class).getFirst();
        assertEquals(EntityType.PLAYER, spawned.getType());
        assertEquals(uuid, spawned.getUuid());
        assertEquals(65, spawned.getY(), 1e-6);
        assertEquals(1, this.test.sent(ClientboundPlayerInfoUpdatePacket.class).size(), "already listed");
        assertEquals((byte) 0x7F, this.test.data(spawned.getEntityId()).get(index(EntityType.PLAYER, MetaField.PLAYER_MODE_CUSTOMISATION)));
        setData(50, flags(ActorFlags.SNEAKING));
        Map<Integer, Object> data = this.test.data(spawned.getEntityId());
        assertEquals((byte) 0x02, data.get(index(EntityType.PLAYER, MetaField.SHARED_FLAGS)));
        assertEquals(Pose.SNEAKING, data.get(index(EntityType.PLAYER, MetaField.POSE)));

        // Removed from the Bedrock list while spawned: only unlisted, then removed with the entity.
        PlayerListPacket removeList = new PlayerListPacket();
        PlayerListRemoveEntry removeEntry = new PlayerListRemoveEntry();
        removeEntry.setUuid(uuid);
        removeList.getEntries().add(removeEntry);
        this.test.run(() -> DataTranslators.playerList(this.test.session, removeList));
        infos = this.test.sent(ClientboundPlayerInfoUpdatePacket.class);
        assertEquals(EnumSet.of(PlayerListEntryAction.UPDATE_LISTED), infos.getLast().getActions());
        assertFalse(infos.getLast().getEntries()[0].isListed());
        assertTrue(this.test.sent(ClientboundPlayerInfoRemovePacket.class).isEmpty());

        RemoveActorPacket remove = new RemoveActorPacket();
        remove.setTargetActorID(50);
        this.test.run(() -> SpawnTranslators.removeActor(this.test.session, remove));
        List<ClientboundPlayerInfoRemovePacket> removed = this.test.sent(ClientboundPlayerInfoRemovePacket.class);
        assertEquals(1, removed.size());
        assertEquals(List.of(uuid), removed.getFirst().getProfileIds());
    }

    @Test
    void nameTagTeams() {
        assertNull(PlayerNametags.wanted("Steve", "Steve", false));
        assertEquals(new PlayerNametags.Team("", "", TeamColor.WHITE, false), PlayerNametags.wanted("Steve", "Steve", true));
        PlayerNametags.Team team = PlayerNametags.wanted("§c[Admin] §eSteve§7 lvl 5", "Steve", false);
        assertEquals("§c[Admin] §e", team.prefix());
        assertEquals("§7 lvl 5", team.suffix());
        assertEquals(TeamColor.YELLOW, team.color());
        assertTrue(team.visible());
        assertEquals("§aBob§r ", PlayerNametags.wanted("§aBob", "Steve", false).prefix());
    }

    @Test
    void profileNamesFitJava() {
        assertEquals("Steve", PlayerListState.profileName("§l§bSteve"));
        assertEquals("ABCDEFGHIJKLMNOP", PlayerListState.profileName("ABCDEFGHIJKLMNOPQRST"));
        assertEquals("Player", PlayerListState.profileName("§r"));
    }

    // ------------------------------------------------------------------ paintings

    @Test
    void paintingsHangInTheirBlock() {
        // A 2x2 painting facing north (Bedrock direction 2) hanging in block (5, 70, 9): Java's centre.
        String motive = "Kebab";
        JavaRegistries.Registry registry = JavaRegistries.registry("minecraft:painting_variant");
        RegistryEntry variant = registry.entries().get(registry.id("minecraft:kebab"));
        int width = ((NbtMap) variant.getData()).getInt("width", 1);
        int height = ((NbtMap) variant.getData()).getInt("height", 1);
        double side = width % 2 == 0 ? 0.5 : 0;
        double up = height % 2 == 0 ? 0.5 : 0;
        // Java: block centre, pushed 0.46875 against the wall, shifted to the counter-clockwise side (west for north).
        float cx = (float) (5.5 - side);
        float cy = (float) (70.5 + up);
        float cz = (float) (9.5 + 0.46875);
        AddPaintingPacket packet = new AddPaintingPacket();
        packet.setTargetActorID(90);
        packet.setTargetRuntimeID(90);
        packet.setMotif(motive);
        packet.setDirection(2);
        packet.setPosition(Vector3f.from(cx, cy, cz));
        this.test.run(() -> SpawnTranslators.addPainting(this.test.session, packet));
        ClientboundAddEntityPacket add = this.test.sent(ClientboundAddEntityPacket.class).getFirst();
        assertEquals(EntityType.PAINTING, add.getType());
        assertEquals(Direction.NORTH, add.getData());
        assertEquals(5, add.getX(), 1e-9);
        assertEquals(70, add.getY(), 1e-9);
        assertEquals(9, add.getZ(), 1e-9);
    }
}
