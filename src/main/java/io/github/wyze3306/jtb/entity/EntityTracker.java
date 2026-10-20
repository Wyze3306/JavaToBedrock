package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.ActorLinkType;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.data.payload.move.PositionMode;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDelimiterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityLinkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CONTRACT (owner: entity subsystem). The Bedrock entities known to the Java client and the id mapping
 * between Bedrock runtime/unique ids (longs) and Java entity ids (ints).
 * <p>
 * Every spawned Bedrock entity is a {@link TrackedEntity} (the local player too, with Java id
 * {@link #localJavaId()}). Proxy-side Java entities (parts of custom entities, temporary experience
 * orbs) get Java ids from {@link #allocateJavaId()}; parts map back to their Bedrock entity in
 * {@link #runtimeId(int)}, so a Java interaction with any of them reaches the right Bedrock entity.
 * PowerNukkitX uses the same value for unique and runtime ids, other servers do not: packets that
 * address entities by unique id ({@code RemoveActor}, links, owners...) go through
 * {@link #entityByUniqueId(long)}.
 * <p>
 * Session-confined (event loop only).
 */
public final class EntityTracker implements SessionState {

    public static final StateKey<EntityTracker> KEY = StateKey.of("entity-tracker", EntityTracker::new);

    /** Relative moves are shorts of 1/4096 block: larger moves are sent as absolute positions. */
    private static final double MAX_DELTA = Short.MAX_VALUE / 4096.0;
    private static final ClientboundDelimiterPacket BUNDLE_DELIMITER = new ClientboundDelimiterPacket(Unpooled.EMPTY_BUFFER);
    /** Experience orbs of {@code SpawnExperienceOrb} have no Bedrock entity: they are collected after this many ticks. */
    private static final int TEMPORARY_ORB_TICKS = 20;
    private static final long UUID_MOST_BITS = 0x6A74_6265_0000_4000L;

    private final ProxySession session;
    private final Long2IntMap javaIds = new Long2IntOpenHashMap();
    private final Int2LongMap runtimeIds = new Int2LongOpenHashMap();
    private final Long2ObjectMap<TrackedEntity> entities = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectMap<TrackedEntity> byJavaId = new Int2ObjectOpenHashMap<>();
    private final Long2LongMap uniqueToRuntime = new Long2LongOpenHashMap();
    private final LongOpenHashSet withEffects = new LongOpenHashSet();
    private final Int2IntMap temporaryEntities = new Int2IntOpenHashMap();
    private final Set<String> availableIdentifiers = new HashSet<>();
    private final MetadataWriter metadata = new MetadataWriter();
    private final PlayerListState playerList;
    private final PlayerNametags nametags;
    private final EntityProperties properties = new EntityProperties();
    private CustomEntityRenderer customRenderer = DefaultCustomEntityRenderer.INSTANCE;
    private int nextJavaId = 1;
    private long localRuntimeId = Long.MIN_VALUE;
    private long localUniqueId = Long.MIN_VALUE;
    private @Nullable TrackedEntity local;

    private EntityTracker(ProxySession session) {
        this.session = session;
        this.javaIds.defaultReturnValue(-1);
        this.runtimeIds.defaultReturnValue(-1);
        this.uniqueToRuntime.defaultReturnValue(Long.MIN_VALUE);
        this.playerList = new PlayerListState(session, this);
        this.nametags = new PlayerNametags(session);
    }

    // ------------------------------------------------------------------ ids

    /** Registers the local player (runtime id -> Java id) before anything else spawns. */
    public void onStartGame(StartGamePacket packet) {
        this.localRuntimeId = packet.getRuntimeID();
        this.localUniqueId = packet.getEntityID();
        int javaId = bind(packet.getRuntimeID());
        TrackedEntity entity = new TrackedEntity(this.localRuntimeId, this.localUniqueId, javaId,
                this.session.getProfile().getId(), "minecraft:player", EntityDefinitions.forJava(EntityType.PLAYER),
                TrackedEntity.Kind.LOCAL_PLAYER);
        this.local = entity;
        this.entities.put(this.localRuntimeId, entity);
        this.byJavaId.put(javaId, entity);
        this.uniqueToRuntime.put(this.localUniqueId, this.localRuntimeId);
        GameType gameType = packet.getGameType() == null || packet.getGameType() == GameType.DEFAULT
                ? packet.getSettings().getGameType() : packet.getGameType();
        for (PlayerListState.Entry entry : this.playerList.entries()) {
            if (entry.local) {
                entry.uniqueId = this.localUniqueId;
                entry.javaEntityId = javaId;
                entity.playerEntry = entry;
                this.playerList.updateGameMode(entry, GameModes.toJava(gameType));
            }
        }
    }

    /** Assigns (or returns the existing) Java id of a Bedrock runtime id. */
    public int bind(long runtimeId) {
        int id = this.javaIds.get(runtimeId);
        if (id == -1) {
            id = allocateJavaId();
            this.javaIds.put(runtimeId, id);
            this.runtimeIds.put(id, runtimeId);
        }
        return id;
    }

    /** Forgets a Bedrock entity's id mapping; returns its Java id or -1. */
    public int unbind(long runtimeId) {
        int id = this.javaIds.remove(runtimeId);
        if (id != -1) {
            this.runtimeIds.remove(id);
        }
        return id;
    }

    /** Java entity id for a Bedrock runtime id, or -1 when the entity is unknown. */
    public int javaId(long runtimeId) {
        return this.javaIds.get(runtimeId);
    }

    /**
     * Bedrock runtime id for a Java entity id, or -1 when unknown. Parts of a custom entity (hit box,
     * displays) resolve to the custom entity.
     */
    public long runtimeId(int javaId) {
        return this.runtimeIds.get(javaId);
    }

    /** Allocates a Java entity id for a proxy-side entity (display, interaction, marker...). */
    public int allocateJavaId() {
        return this.nextJavaId++;
    }

    /**
     * Allocates the Java entity id of an extra Java entity drawing {@code owner} (custom entity parts):
     * it maps back to the owner's runtime id, follows its moves and is removed with it.
     */
    public int addPart(TrackedEntity owner) {
        int id = allocateJavaId();
        this.runtimeIds.put(id, owner.runtimeId());
        this.byJavaId.put(id, owner);
        int[] parts = Arrays.copyOf(owner.parts, owner.parts.length + 1);
        parts[parts.length - 1] = id;
        owner.parts = parts;
        return id;
    }

    /** Bedrock runtime id of the local player ({@link Long#MIN_VALUE} before StartGame). */
    public long localRuntimeId() {
        return this.localRuntimeId;
    }

    /** Bedrock unique id of the local player ({@link Long#MIN_VALUE} before StartGame). */
    public long localUniqueId() {
        return this.localUniqueId;
    }

    public int localJavaId() {
        return this.local == null ? 1 : this.local.javaId();
    }

    public @Nullable TrackedEntity localEntity() {
        return this.local;
    }

    public @Nullable TrackedEntity entity(long runtimeId) {
        return this.entities.get(runtimeId);
    }

    /** The entity drawn by Java entity {@code javaId} (custom entity parts included), or null. */
    public @Nullable TrackedEntity entityByJavaId(int javaId) {
        return this.byJavaId.get(javaId);
    }

    public @Nullable TrackedEntity entityByUniqueId(long uniqueId) {
        long runtimeId = this.uniqueToRuntime.get(uniqueId);
        return runtimeId == Long.MIN_VALUE ? null : this.entities.get(runtimeId);
    }

    /** Runtime id of a unique id, or the unique id itself when unknown (both are equal on PowerNukkitX). */
    public long runtimeIdOfUnique(long uniqueId) {
        long runtimeId = this.uniqueToRuntime.get(uniqueId);
        return runtimeId == Long.MIN_VALUE ? uniqueId : runtimeId;
    }

    /** Runtime id of the entity the local player rides, or -1. */
    public long localVehicle() {
        return this.local == null ? -1 : this.local.vehicle;
    }

    /**
     * Java UUID of the entity with this Bedrock unique id: the profile UUID of a player, the entity's
     * UUID otherwise, and a stable derived UUID for entities not (or no longer) spawned.
     */
    public UUID javaUuidOfUnique(long uniqueId) {
        TrackedEntity entity = entityByUniqueId(uniqueId);
        if (entity != null) {
            return entity.uuid();
        }
        PlayerListState.Entry entry = this.playerList.entryByUniqueId(uniqueId);
        return entry != null ? entry.javaUuid : derivedUuid(uniqueId);
    }

    static UUID derivedUuid(long uniqueId) {
        return new UUID(UUID_MOST_BITS, uniqueId);
    }

    /**
     * The name a Java scoreboard uses for the entity with this Bedrock unique id: a player's profile
     * name, any other entity's UUID; null when unknown.
     */
    public @Nullable String scoreboardName(long uniqueId) {
        PlayerListState.Entry entry = this.playerList.entryByUniqueId(uniqueId);
        if (entry != null) {
            return entry.profileName;
        }
        TrackedEntity entity = entityByUniqueId(uniqueId);
        return entity == null ? null : entity.uuid().toString();
    }

    /** Another player's game mode changed ({@code UpdatePlayerGameType}): keeps the tab list in step. */
    public void updatePlayerGameMode(long uniqueId, GameType gameType) {
        PlayerListState.Entry entry = this.playerList.entryByUniqueId(uniqueId);
        if (entry != null) {
            this.playerList.updateGameMode(entry, GameModes.toJava(gameType));
        }
    }

    /** Whether {@code AvailableActorIdentifiers} announced this identifier (custom entities included). */
    public boolean isAvailableIdentifier(String identifier) {
        return this.availableIdentifiers.contains(identifier);
    }

    public CustomEntityRenderer customRenderer() {
        return this.customRenderer;
    }

    /** Installs the renderer of custom entities (resource pack models); applies to entities spawned afterwards. */
    public void setCustomEntityRenderer(CustomEntityRenderer renderer) {
        this.customRenderer = renderer;
    }

    // ------------------------------------------------------------------ registration

    MetadataWriter metadata() {
        return this.metadata;
    }

    PlayerListState playerList() {
        return this.playerList;
    }

    PlayerNametags nametags() {
        return this.nametags;
    }

    EntityProperties properties() {
        return this.properties;
    }

    Set<String> availableIdentifiers() {
        return this.availableIdentifiers;
    }

    /** Sends the packets of {@code sends} as one Java bundle, applied by the client in the same tick. */
    void bundle(Runnable sends) {
        this.session.sendJava(BUNDLE_DELIMITER);
        try {
            sends.run();
        } finally {
            this.session.sendJava(BUNDLE_DELIMITER);
        }
    }

    /**
     * Registers a new Bedrock entity. An entity already registered with this runtime id (re-added by
     * the server) is removed from the Java client first.
     */
    TrackedEntity register(long uniqueId, long runtimeId, String identifier, JavaEntityDefinition definition,
                           TrackedEntity.Kind kind, @Nullable UUID uuid) {
        TrackedEntity previous = this.entities.get(runtimeId);
        if (previous != null && previous.kind() != TrackedEntity.Kind.LOCAL_PLAYER) {
            remove(previous);
        }
        int javaId = bind(runtimeId);
        TrackedEntity entity = new TrackedEntity(runtimeId, uniqueId, javaId, uuid != null ? uuid : derivedUuid(uniqueId),
                identifier, definition, kind);
        this.entities.put(runtimeId, entity);
        this.byJavaId.put(javaId, entity);
        this.uniqueToRuntime.put(uniqueId, runtimeId);
        return entity;
    }

    /** Removes an entity (and its parts) from the Java client and forgets it. */
    void remove(TrackedEntity entity) {
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            return;
        }
        if (entity.kind() == TrackedEntity.Kind.CUSTOM) {
            this.customRenderer.remove(this.session, this, entity);
        }
        int[] ids = Arrays.copyOf(entity.parts, entity.parts.length + 1);
        ids[ids.length - 1] = entity.javaId();
        this.session.sendJava(new ClientboundRemoveEntitiesPacket(ids));
        forget(entity);
    }

    /** Drops the bookkeeping of an entity without telling the client. */
    private void forget(TrackedEntity entity) {
        this.entities.remove(entity.runtimeId());
        this.byJavaId.remove(entity.javaId());
        unbind(entity.runtimeId());
        for (int part : entity.parts) {
            this.byJavaId.remove(part);
            this.runtimeIds.remove(part);
        }
        if (this.uniqueToRuntime.get(entity.uniqueId()) == entity.runtimeId()) {
            this.uniqueToRuntime.remove(entity.uniqueId());
        }
        this.withEffects.remove(entity.runtimeId());
        if (entity.vehicle != -1) {
            TrackedEntity vehicle = this.entities.get(entity.vehicle);
            if (vehicle != null && vehicle.passengers != null) {
                vehicle.passengers.rem(entity.runtimeId());
                sendPassengers(vehicle);
            }
        }
        if (entity.passengers != null) {
            for (long passenger : entity.passengers) {
                TrackedEntity rider = this.entities.get(passenger);
                if (rider != null) {
                    rider.vehicle = -1;
                }
            }
        }
        if (entity.kind() == TrackedEntity.Kind.PLAYER) {
            this.nametags.remove(entity);
            if (entity.playerEntry != null) {
                this.playerList.onEntityRemoved(entity.playerEntry);
            }
        }
    }

    /**
     * The Java client changed dimension (a Java respawn drops every entity). The world subsystem calls
     * this after sending the respawn; the tab list and the local player are kept.
     */
    public void onDimensionChange() {
        List<TrackedEntity> all = new ArrayList<>(this.entities.values());
        for (TrackedEntity entity : all) {
            if (entity.kind() != TrackedEntity.Kind.LOCAL_PLAYER) {
                forget(entity);
            }
        }
        this.temporaryEntities.clear();
        this.nametags.clear();
        if (this.local != null) {
            this.local.vehicle = -1;
            this.local.passengers = null;
            this.local.resetSent();
        }
    }

    // ------------------------------------------------------------------ movement

    /** Java byte angle, as the client receives it. */
    static byte angle(float degrees) {
        return (byte) (int) (degrees * 256f / 360f);
    }

    private static long quantize(double coordinate) {
        return Math.round(coordinate * 4096.0);
    }

    /**
     * Moves an entity on the Java client to a feet position. Small moves are relative
     * ({@code MoveEntityPos}/{@code PosRot}, interpolated by the client, deltas computed from the
     * client's quantized position so they never drift), larger ones absolute ({@code EntityPositionSync}),
     * and Bedrock teleports are {@code TeleportEntity}. Passengers are placed on their vehicle by the
     * Java client: only their rotation is sent. Parts of custom entities get the same moves.
     */
    void moveTo(TrackedEntity entity, double x, double y, double z, float yaw, float pitch, float headYaw,
                boolean onGround, boolean teleport) {
        if (entity.vehicle != -1) {
            x = entity.x;
            y = entity.y;
            z = entity.z;
            teleport = false;
        }
        int id = entity.javaId();
        long dx = quantize(x) - quantize(entity.x);
        long dy = quantize(y) - quantize(entity.y);
        long dz = quantize(z) - quantize(entity.z);
        boolean moved = dx != 0 || dy != 0 || dz != 0;
        boolean turned = angle(yaw) != angle(entity.yaw) || angle(pitch) != angle(entity.pitch);
        boolean far = Math.abs(dx) > Short.MAX_VALUE || Math.abs(dy) > Short.MAX_VALUE || Math.abs(dz) > Short.MAX_VALUE;
        Packet move = null;
        if (teleport && (moved || turned)) {
            move = new ClientboundTeleportEntityPacket(id, Vector3d.from(x, y, z), Vector3d.ZERO, yaw, pitch, List.of(), onGround);
        } else if (moved && far) {
            move = new ClientboundEntityPositionSyncPacket(id, false, Vector3d.from(x, y, z), null, yaw, pitch, onGround);
        } else if (moved) {
            move = turned
                    ? new ClientboundMoveEntityPosRotPacket(id, onGround, 0, null, dx / 4096.0, dy / 4096.0, dz / 4096.0, yaw, pitch)
                    : new ClientboundMoveEntityPosPacket(id, onGround, 0, null, dx / 4096.0, dy / 4096.0, dz / 4096.0);
        } else if (turned || onGround != entity.onGround) {
            // A zero move rather than MoveEntityRot, whose field order MCProtocolLib and ViaBedrock disagree on.
            move = new ClientboundMoveEntityPosRotPacket(id, onGround, 0, null, 0, 0, 0, yaw, pitch);
        }
        if (move != null) {
            this.session.sendJava(move);
            for (int part : entity.parts) {
                this.session.sendJava(withId(move, part));
            }
        }
        if (angle(headYaw) != angle(entity.headYaw)) {
            this.session.sendJava(new ClientboundRotateHeadPacket(id, headYaw));
        }
        entity.x = x;
        entity.y = y;
        entity.z = z;
        entity.yaw = yaw;
        entity.pitch = pitch;
        entity.headYaw = headYaw;
        entity.onGround = onGround;
        if (move != null && entity.kind() == TrackedEntity.Kind.CUSTOM) {
            this.customRenderer.moved(this.session, this, entity);
        }
    }

    private static Packet withId(Packet move, int id) {
        if (move instanceof ClientboundMoveEntityPosPacket pos) {
            return pos.withEntityId(id);
        }
        if (move instanceof ClientboundMoveEntityPosRotPacket posRot) {
            return posRot.withEntityId(id);
        }
        if (move instanceof ClientboundEntityPositionSyncPacket sync) {
            return sync.withId(id);
        }
        return ((ClientboundTeleportEntityPacket) move).withId(id);
    }

    /**
     * Height above the feet of a player's Bedrock position. PowerNukkitX uses the bounding box height
     * minus 0.18 (1.62 standing, 1.32 sneaking, 0.42 swimming), from the {@code HEIGHT} and
     * {@code SCALE} entity data it sends with every pose change.
     */
    static float playerEyeHeight(TrackedEntity player) {
        float height = player.bedrockHeight >= 0 ? player.bedrockHeight * player.scale : 1.8f;
        return height > 0.18f ? height - 0.18f : PlayerState.EYE_HEIGHT;
    }

    /** {@code MovePlayer} for another player (the player module handles the local one). */
    public void onRemotePlayerMove(MovePlayerPacket packet) {
        TrackedEntity entity = this.entities.get(packet.getPlayerRuntimeID());
        if (entity == null || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            return;
        }
        Vector3f position = packet.getPosition();
        Vector3f rotation = packet.getRotation();
        PositionMode mode = packet.getPositionMode();
        if (mode == PositionMode.ONLY_HEAD_ROT || position == null) {
            moveTo(entity, entity.x, entity.y, entity.z, rotation.getY(), rotation.getX(), rotation.getZ(),
                    packet.isOnGround(), false);
            return;
        }
        float eye = entity.definition().isPlayerLike() ? playerEyeHeight(entity) : entity.definition().offset();
        boolean teleport = mode == PositionMode.TELEPORT || mode == PositionMode.RESPAWN;
        moveTo(entity, position.getX(), position.getY() - eye, position.getZ(), rotation.getY(), rotation.getX(),
                rotation.getZ(), packet.isOnGround(), teleport);
    }

    // ------------------------------------------------------------------ riding

    /**
     * Bedrock {@code ActorLink}: {@code vehicleUid} carries {@code riderUid} ({@code RIDING}: the rider
     * controls it and sits first; {@code PASSENGER}: behind), or the rider gets off ({@code NONE}).
     */
    void link(long vehicleUid, long riderUid, ActorLinkType type) {
        TrackedEntity rider = entityByUniqueId(riderUid);
        if (rider == null) {
            return;
        }
        TrackedEntity previous = rider.vehicle == -1 ? null : this.entities.get(rider.vehicle);
        if (type == ActorLinkType.NONE) {
            rider.vehicle = -1;
            if (previous != null && previous.passengers != null && previous.passengers.rem(rider.runtimeId())) {
                sendPassengers(previous);
            }
            return;
        }
        TrackedEntity vehicle = entityByUniqueId(vehicleUid);
        if (vehicle == null || vehicle == rider) {
            return;
        }
        if (previous != null && previous != vehicle && previous.passengers != null && previous.passengers.rem(rider.runtimeId())) {
            sendPassengers(previous);
        }
        LongArrayList passengers = vehicle.passengers;
        if (passengers == null) {
            passengers = new LongArrayList(2);
            vehicle.passengers = passengers;
        }
        passengers.rem(rider.runtimeId());
        if (type == ActorLinkType.RIDING) {
            passengers.add(0, rider.runtimeId());
        } else {
            passengers.add(rider.runtimeId());
        }
        rider.vehicle = vehicle.runtimeId();
        sendPassengers(vehicle);
    }

    /** Sends the full passenger list of {@code vehicle} (riders in Bedrock seat order). */
    public void sendPassengers(TrackedEntity vehicle) {
        IntArrayList ids = new IntArrayList();
        if (vehicle.passengers != null) {
            LongIterator iterator = vehicle.passengers.iterator();
            while (iterator.hasNext()) {
                TrackedEntity rider = this.entities.get(iterator.nextLong());
                if (rider != null) {
                    ids.add(rider.javaId());
                }
            }
        }
        this.session.sendJava(new ClientboundSetPassengersPacket(vehicle.javaId(), ids.toIntArray()));
    }

    // ------------------------------------------------------------------ side state of entity data

    /** Leash holder ({@code LEASH_HOLDER}) as a Java entity link. */
    void sendLeash(TrackedEntity entity) {
        TrackedEntity holder = entity.leashHolder == -1 || entity.leashHolder == 0 ? null : entityByUniqueId(entity.leashHolder);
        this.session.sendJava(new ClientboundSetEntityLinkPacket(entity.javaId(), holder == null ? 0 : holder.javaId()));
    }

    /** Bedrock {@code SCALE} as the Java {@code minecraft:scale} attribute (sent when it changes). */
    void sendScale(TrackedEntity entity) {
        float scale = javaScale(entity);
        if (scale != entity.javaScale) {
            entity.javaScale = scale;
            this.session.sendJava(new ClientboundUpdateAttributesPacket(entity.javaId(),
                    List.of(new Attribute(AttributeType.Builtin.SCALE, scale))));
        }
    }

    /**
     * Java scale of an entity: babies and slimes are drawn small by Java itself (Bedrock shrinks them
     * with {@code SCALE}), helpers below Java's minimum scale get the minimum.
     */
    static float javaScale(TrackedEntity entity) {
        if (entity.flags.contains(ActorFlags.BABY)) {
            return 1;
        }
        EntityType type = entity.javaType();
        if (type == EntityType.SLIME || type == EntityType.MAGMA_CUBE || type == EntityType.PHANTOM
                || type == EntityType.SULFUR_CUBE) {
            return 1;
        }
        float scale = entity.scale;
        if (scale < ActorDataTranslator.MIN_JAVA_SCALE) {
            return ActorDataTranslator.MIN_JAVA_SCALE;
        }
        return Math.abs(scale - 1) < 0.001f ? 1 : Math.min(16, scale);
    }

    /** Bedrock {@code SADDLED} flag as the Java saddle equipment slot. */
    void sendSaddle(TrackedEntity entity) {
        boolean saddled = entity.flags.contains(ActorFlags.SADDLED);
        this.session.sendJava(new ClientboundSetEquipmentPacket(entity.javaId(), new Equipment[]{
                new Equipment(EquipmentSlot.SADDLE, saddled ? EntityItems.saddle(this.session) : null)}));
    }

    // ------------------------------------------------------------------ effects and temporary entities

    /** Tracks a Bedrock effect of another entity so it is removed on Java when it runs out (-1: infinite). */
    void trackEffect(TrackedEntity entity, int bedrockEffectId, int durationTicks) {
        if (entity.effects == null) {
            entity.effects = new Int2IntOpenHashMap(2);
        }
        entity.effects.put(bedrockEffectId, durationTicks);
        this.withEffects.add(entity.runtimeId());
    }

    void untrackEffect(TrackedEntity entity, int bedrockEffectId) {
        if (entity.effects != null) {
            entity.effects.remove(bedrockEffectId);
            if (entity.effects.isEmpty()) {
                this.withEffects.remove(entity.runtimeId());
            }
        }
    }

    /** A proxy-side experience orb ({@code SpawnExperienceOrb}): shown, then collected by the local player. */
    void addTemporaryOrb(int javaId) {
        this.temporaryEntities.put(javaId, TEMPORARY_ORB_TICKS);
    }

    @Override
    public void tick() {
        if (!this.withEffects.isEmpty()) {
            tickEffects();
        }
        if (!this.temporaryEntities.isEmpty()) {
            tickTemporary();
        }
    }

    private void tickEffects() {
        LongIterator iterator = this.withEffects.iterator();
        while (iterator.hasNext()) {
            TrackedEntity entity = this.entities.get(iterator.nextLong());
            if (entity == null || entity.effects == null) {
                iterator.remove();
                continue;
            }
            ObjectIterator<Int2IntMap.Entry> effects = entity.effects.int2IntEntrySet().iterator();
            while (effects.hasNext()) {
                Int2IntMap.Entry effect = effects.next();
                int remaining = effect.getIntValue();
                if (remaining < 0) {
                    continue;
                }
                if (remaining <= 1) {
                    effects.remove();
                    Effect javaEffect = EntityDefinitions.javaEffect(effect.getIntKey());
                    if (javaEffect != null) {
                        this.session.sendJava(new ClientboundRemoveMobEffectPacket(entity.javaId(), javaEffect));
                    }
                } else {
                    effect.setValue(remaining - 1);
                }
            }
            if (entity.effects.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void tickTemporary() {
        IntArrayList expired = new IntArrayList();
        ObjectIterator<Int2IntMap.Entry> iterator = this.temporaryEntities.int2IntEntrySet().iterator();
        while (iterator.hasNext()) {
            Int2IntMap.Entry entry = iterator.next();
            if (entry.getIntValue() <= 1) {
                expired.add(entry.getIntKey());
                iterator.remove();
            } else {
                entry.setValue(entry.getIntValue() - 1);
            }
        }
        if (!expired.isEmpty()) {
            for (int i = 0; i < expired.size(); i++) {
                this.session.sendJava(new ClientboundTakeItemEntityPacket(expired.getInt(i), localJavaId(), 1));
            }
            this.session.sendJava(new ClientboundRemoveEntitiesPacket(expired.toIntArray()));
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean isBoat(EntityType type) {
        String name = type.name();
        return name.endsWith("_BOAT") || name.endsWith("_RAFT");
    }

    /** Number of tracked Bedrock entities (the local player included). */
    public int size() {
        return this.entities.size();
    }
}
