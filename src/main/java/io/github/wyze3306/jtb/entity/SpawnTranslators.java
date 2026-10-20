package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorLink;
import org.cloudburstmc.protocol.bedrock.data.actor.PropertySyncData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPaintingPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.SpawnExperienceOrbPacket;
import org.cloudburstmc.protocol.bedrock.packet.TakeItemActorPacket;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.FallingBlockData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.GenericObjectData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.MinecartType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ObjectData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Spawning and removal: {@code AddActor}, {@code AddPlayer}, {@code AddItemActor}, {@code AddPainting},
 * {@code SpawnExperienceOrb}, {@code RemoveActor} and {@code TakeItemActor}.
 * <p>
 * Each spawn is one Java bundle (AddEntity, entity data, attributes, equipment) so the client never
 * draws the entity with default data. Bedrock positions are converted to Java feet positions: players
 * and most entities are spawned at their feet by PowerNukkitX, boats and item entities with their
 * Bedrock offset (see {@link JavaEntityDefinition#offset()}).
 */
final class SpawnTranslators {

    private static final float PAINTING_WALL_OFFSET = 0.46875f;
    static final GenericObjectData NO_DATA = new GenericObjectData(0);
    private static final Direction[] PAINTING_FACING = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    private static final byte ALL_SKIN_PARTS = 0x7F;

    private SpawnTranslators() {
    }

    // ------------------------------------------------------------------ AddActor

    static void addActor(ProxySession session, AddActorPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        long runtimeId = packet.getTargetRuntimeID();
        if (runtimeId == tracker.localRuntimeId()) {
            return;
        }
        String identifier = namespaced(packet.getActorType());
        ActorDataMap data = packet.getActorData();
        JavaEntityDefinition definition = EntityDefinitions.forBedrock(identifier);
        TrackedEntity.Kind kind = TrackedEntity.Kind.VANILLA;
        if (definition != null) {
            definition = refine(definition, identifier, data);
        } else if (EntityDefinitions.isUnmappedVanilla(identifier)) {
            return;
        } else {
            kind = TrackedEntity.Kind.CUSTOM;
            definition = EntityDefinitions.forJava(EntityType.INTERACTION);
        }
        TrackedEntity entity = tracker.register(packet.getTargetActorID(), runtimeId, identifier, definition, kind, null);
        Vector3f position = packet.getPosition();
        Vector2f rotation = packet.getRotation();
        entity.x = position.getX();
        entity.y = position.getY() - (EntityTracker.isBoat(definition.type()) ? definition.offset() : 0);
        entity.z = position.getZ();
        entity.pitch = rotation.getX();
        entity.yaw = rotation.getY();
        entity.headYaw = packet.getHeadRotation();

        if (kind == TrackedEntity.Kind.CUSTOM) {
            spawnCustom(session, tracker, entity, data, packet.getSyncedProperties());
        } else {
            ObjectData objectData = objectData(session, tracker, entity, data);
            if (objectData == null) {
                tracker.remove(entity);
                return;
            }
            Vector3d velocity = velocity(packet.getVelocity());
            tracker.bundle(() -> {
                session.sendJava(new ClientboundAddEntityPacket(entity.javaId(), entity.uuid(), entity.javaType(), objectData,
                        entity.x, entity.y, entity.z, velocity, entity.yaw, entity.headYaw, entity.pitch));
                sendInitialData(session, tracker, entity, data, packet.getSyncedProperties(), null);
                AttributeTranslators.apply(session, tracker, entity, packet.getAttributesList());
            });
        }
        for (ActorLink link : packet.getActorLinks()) {
            tracker.link(link.getTargetA(), link.getTargetB(), link.getType());
        }
    }

    private static void spawnCustom(ProxySession session, EntityTracker tracker, TrackedEntity entity, ActorDataMap data,
                                    PropertySyncData properties) {
        MetadataWriter writer = tracker.metadata().begin(entity);
        ActorDataTranslator.translate(session, tracker, entity, data, writer);
        EntityMetadata<?, ?>[] metadata = writer.drain();
        tracker.bundle(() -> {
            tracker.customRenderer().spawn(session, tracker, entity);
            if (metadata != null) {
                session.sendJava(new ClientboundSetEntityDataPacket(entity.javaId(), metadata));
            }
        });
        if (!tracker.properties().isEmpty(properties)) {
            tracker.properties().apply(session, tracker, entity, properties, tracker.metadata().begin(entity));
        }
    }

    /** Java type variants Bedrock encodes in entity data (boat wood). */
    private static JavaEntityDefinition refine(JavaEntityDefinition definition, String identifier, ActorDataMap data) {
        boolean boat = identifier.equals("minecraft:boat");
        if (boat || identifier.equals("minecraft:chest_boat")) {
            Integer variant = data.get(ActorDataTypes.VARIANT);
            String wood = EntityDefinitions.boatWood(variant == null ? 0 : variant);
            String name = wood.equals("bamboo") ? (boat ? "BAMBOO_RAFT" : "BAMBOO_CHEST_RAFT")
                    : wood.toUpperCase(java.util.Locale.ROOT) + (boat ? "_BOAT" : "_CHEST_BOAT");
            try {
                return EntityDefinitions.forJava(EntityType.valueOf(name));
            } catch (IllegalArgumentException e) {
                return definition;
            }
        }
        return definition;
    }

    /** The {@code data} field of the Java AddEntity packet; null when the entity cannot be shown. */
    private static @Nullable ObjectData objectData(ProxySession session, EntityTracker tracker, TrackedEntity entity,
                                                   ActorDataMap data) {
        EntityType type = entity.javaType();
        if (type == EntityType.FALLING_BLOCK) {
            Integer block = data.get(ActorDataTypes.VARIANT);
            return new FallingBlockData(session.get(BlockMapper.KEY).toJava(block == null ? 0 : block), 0);
        }
        if (type == EntityType.MINECART) {
            return MinecartType.NORMAL;
        }
        if (type.isProjectile()) {
            Long owner = data.get(ActorDataTypes.OWNER);
            TrackedEntity shooter = owner == null || owner == -1 ? null : tracker.entityByUniqueId(owner);
            if (type == EntityType.FISHING_BOBBER && (shooter == null || !shooter.isPlayer())) {
                // Java discards a bobber without a player owner.
                return null;
            }
            return new ProjectileData(shooter == null ? 0 : shooter.javaId());
        }
        return NO_DATA;
    }

    /**
     * The entity data of a new entity: what the Bedrock spawn packet carries, plus Java-only defaults
     * (outer skin layers of players, the dangerous flag of blue wither skulls...). {@code extra} is
     * added as is (item of an item entity).
     */
    private static void sendInitialData(ProxySession session, EntityTracker tracker, TrackedEntity entity, ActorDataMap data,
                                        @Nullable PropertySyncData properties, @Nullable ItemStack item) {
        MetadataWriter writer = tracker.metadata().begin(entity);
        if (entity.definition().isPlayerLike()) {
            writer.putByte(MetaField.PLAYER_MODE_CUSTOMISATION, ALL_SKIN_PARTS);
        }
        if (entity.identifier().equals("minecraft:wither_skull_dangerous")) {
            writer.putBoolean(MetaField.DANGEROUS, true);
        }
        if (item != null) {
            writer.putObject(MetaField.ITEM, MetadataTypes.ITEM_STACK, item);
        }
        ActorDataTranslator.translate(session, tracker, entity, data, writer);
        if (properties != null && !tracker.properties().isEmpty(properties)) {
            tracker.properties().apply(session, tracker, entity, properties, writer);
        }
        writer.flush(session);
    }

    private static Vector3d velocity(@Nullable Vector3f motion) {
        return motion == null ? Vector3d.ZERO : Vector3d.from(motion.getX(), motion.getY(), motion.getZ());
    }

    static String namespaced(@Nullable String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "minecraft:unknown";
        }
        return identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier;
    }

    // ------------------------------------------------------------------ AddPlayer

    static void addPlayer(ProxySession session, AddPlayerPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        long runtimeId = packet.getTargetRuntimeID();
        if (runtimeId == tracker.localRuntimeId()) {
            return;
        }
        // The v2168+ wire format has no unique id field: it is the target of the abilities.
        long uniqueId = packet.getAbilitiesData().getTargetPlayerRawId();
        if (uniqueId == 0) {
            uniqueId = packet.getTargetActorID() != 0 ? packet.getTargetActorID() : runtimeId;
        }
        String name = packet.getPlayerName() == null ? "" : packet.getPlayerName();
        GameMode gameMode = GameModes.toJava(packet.getPlayerGameType());
        PlayerListState.Entry entry = tracker.playerList().ensureForSpawn(packet.getUuid(), uniqueId, name, gameMode);
        TrackedEntity entity = tracker.register(uniqueId, runtimeId, "minecraft:player",
                EntityDefinitions.forJava(EntityType.PLAYER), TrackedEntity.Kind.PLAYER, entry.javaUuid);
        entity.playerEntry = entry;
        entry.javaEntityId = entity.javaId();
        // PowerNukkitX (like vanilla Bedrock) spawns players at their feet, unlike MovePlayer.
        Vector3f position = packet.getPosition();
        Vector3f rotation = packet.getRotation();
        entity.x = position.getX();
        entity.y = position.getY();
        entity.z = position.getZ();
        entity.pitch = rotation.getX();
        entity.yaw = rotation.getY();
        entity.headYaw = rotation.getZ();
        ItemData carried = packet.getCarriedItem();
        ItemStack held = carried == null || carried.isNull() ? null : ItemTranslator.toJava(session, carried);
        entity.mainHandItem = carried == null || carried.isNull() ? null : carried.getDefinition().getIdentifier();
        Vector3d velocity = velocity(packet.getVelocity());
        tracker.bundle(() -> {
            session.sendJava(new ClientboundAddEntityPacket(entity.javaId(), entity.uuid(), EntityType.PLAYER, NO_DATA,
                    entity.x, entity.y, entity.z, velocity, entity.yaw, entity.headYaw, entity.pitch));
            sendInitialData(session, tracker, entity, packet.getActorData(), packet.getSyncedProperties(), null);
            if (held != null) {
                tracker.sendEquipment(entity, new Equipment[]{new Equipment(EquipmentSlot.MAIN_HAND, held)});
            }
        });
        tracker.nametags().update(entity);
        for (ActorLink link : packet.getActorLinks()) {
            tracker.link(link.getTargetA(), link.getTargetB(), link.getType());
        }
    }

    // ------------------------------------------------------------------ AddItemActor

    static void addItemActor(ProxySession session, AddItemActorPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        JavaEntityDefinition definition = EntityDefinitions.forJava(EntityType.ITEM);
        TrackedEntity entity = tracker.register(packet.getTargetActorID(), packet.getTargetRuntimeID(), "minecraft:item",
                definition, TrackedEntity.Kind.VANILLA, null);
        Vector3f position = packet.getPosition();
        entity.x = position.getX();
        entity.y = position.getY() - definition.offset();
        entity.z = position.getZ();
        ItemData item = packet.getItem();
        entity.itemCount = item == null ? 1 : Math.max(1, item.getCount());
        ItemStack stack = item == null || item.isNull() ? null : ItemTranslator.toJava(session, item);
        Vector3d velocity = velocity(packet.getVelocity());
        tracker.bundle(() -> {
            session.sendJava(new ClientboundAddEntityPacket(entity.javaId(), entity.uuid(), EntityType.ITEM, NO_DATA,
                    entity.x, entity.y, entity.z, velocity, 0, 0, 0));
            sendInitialData(session, tracker, entity, packet.getEntityData(), null, stack);
        });
    }

    // ------------------------------------------------------------------ AddPainting

    static void addPainting(ProxySession session, AddPaintingPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        String variant = EntityDefinitions.paintingVariant(packet.getMotif() == null ? "" : packet.getMotif());
        JavaRegistries.Registry registry = JavaRegistries.registry("minecraft:painting_variant");
        int variantId = variant == null ? -1 : registry.id(variant);
        if (variantId < 0) {
            session.logger().debug("[{}] Unknown painting motive {}", session.getName(), packet.getMotif());
            variantId = Math.max(0, registry.id("minecraft:kebab"));
        }
        int width = 1;
        int height = 1;
        RegistryEntry entry = registry.entries().get(variantId);
        if (entry.getData() instanceof NbtMap element) {
            width = element.getInt("width", 1);
            height = element.getInt("height", 1);
        }
        Direction facing = PAINTING_FACING[packet.getDirection() & 3];
        int fx = facing == Direction.EAST ? 1 : facing == Direction.WEST ? -1 : 0;
        int fz = facing == Direction.SOUTH ? 1 : facing == Direction.NORTH ? -1 : 0;
        // Counter-clockwise of the facing (Java's width axis): (fz, -fx).
        int cx = fz;
        int cz = -fx;
        double sideOffset = width % 2 == 0 ? 0.5 : 0;
        double upOffset = height % 2 == 0 ? 0.5 : 0;
        // Bedrock gives the painting's centre on the wall; Java wants the block it hangs in.
        Vector3f center = packet.getPosition();
        double blockX = Math.floor(center.getX() + PAINTING_WALL_OFFSET * fx - sideOffset * cx);
        double blockY = Math.floor(center.getY() - upOffset);
        double blockZ = Math.floor(center.getZ() + PAINTING_WALL_OFFSET * fz - sideOffset * cz);

        TrackedEntity entity = tracker.register(packet.getTargetActorID(), packet.getTargetRuntimeID(), "minecraft:painting",
                EntityDefinitions.forJava(EntityType.PAINTING), TrackedEntity.Kind.VANILLA, null);
        entity.x = blockX;
        entity.y = blockY;
        entity.z = blockZ;
        int paintingVariant = variantId;
        tracker.bundle(() -> {
            session.sendJava(new ClientboundAddEntityPacket(entity.javaId(), entity.uuid(), EntityType.PAINTING, facing,
                    blockX, blockY, blockZ, Vector3d.ZERO, 0, 0, 0));
            MetadataWriter writer = tracker.metadata().begin(entity);
            writer.putObject(MetaField.DIRECTION, MetadataTypes.DIRECTION, facing);
            writer.putObject(MetaField.PAINTING_VARIANT, MetadataTypes.PAINTING_VARIANT, Holder.ofId(paintingVariant));
            writer.flush(session);
        });
    }

    // ------------------------------------------------------------------ experience orbs

    /** Bedrock asks the client to show orbs that are not entities: shown, then collected by the local player. */
    static void spawnExperienceOrb(ProxySession session, SpawnExperienceOrbPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        int javaId = tracker.allocateJavaId();
        Vector3f position = packet.getPosition();
        JavaEntityDefinition orb = EntityDefinitions.forJava(EntityType.EXPERIENCE_ORB);
        tracker.bundle(() -> {
            session.sendJava(new ClientboundAddEntityPacket(javaId, UUID.randomUUID(), EntityType.EXPERIENCE_ORB, NO_DATA,
                    position.getX(), position.getY(), position.getZ(), Vector3d.ZERO, 0, 0, 0));
            session.sendJava(new ClientboundSetEntityDataPacket(javaId, new EntityMetadata<?, ?>[]{
                    new IntEntityMetadata(orb.index(MetaField.VALUE), MetadataTypes.INT, Math.max(1, packet.getXpValue()))}));
        });
        tracker.addTemporaryOrb(javaId);
    }

    // ------------------------------------------------------------------ removal

    /** {@code RemoveActor} addresses the entity by its unique id. */
    static void removeActor(ProxySession session, RemoveActorPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entityByUniqueId(packet.getTargetActorID());
        if (entity == null) {
            entity = tracker.entity(packet.getTargetActorID());
        }
        if (entity == null || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            return;
        }
        if (entity.dead) {
            // Bedrock clients show smoke when a dead mob disappears; Java waits for this event.
            session.sendJava(new ClientboundEntityEventPacket(entity.javaId(), EntityEvent.MAKE_POOF_PARTICLES));
        }
        tracker.remove(entity);
    }

    /** Pick-up animation; Bedrock removes the item entity separately. */
    static void takeItemActor(ProxySession session, TakeItemActorPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity item = tracker.entity(packet.getItemRuntimeID());
        TrackedEntity collector = tracker.entity(packet.getActorRuntimeID());
        if (item == null || collector == null) {
            return;
        }
        session.sendJava(new ClientboundTakeItemEntityPacket(item.javaId(), collector.javaId(),
                item.javaType() == EntityType.ITEM ? item.itemCount : 1));
    }
}
