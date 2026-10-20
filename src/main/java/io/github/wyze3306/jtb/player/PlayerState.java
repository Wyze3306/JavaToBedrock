package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.inventory.InventoryState;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.world.JavaDimensions;
import io.github.wyze3306.jtb.world.WorldState;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerRespawnState;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.data.payload.attribute.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ShowCreditsPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.EnterCreditsValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RespawnScreenValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/**
 * CONTRACT (owner: player subsystem). The local player: identity on both sides, position and
 * rotation, game mode and abilities, movement emulation ({@code PlayerAuthInputPacket} every tick),
 * vitals, death and respawn.
 * <p>
 * Packets that address an entity by runtime id are translated by the entity subsystem; when they
 * target the local player it forwards them to the {@code onLocal...} hooks below (mounting included:
 * {@link #onLocalMount}/{@link #onLocalDismount}). The world subsystem reports game rules
 * ({@link #onGameRules}) and dimension changes ({@link #setDimensionChanging}); the sound subsystem
 * skips the cracking level events of the block being dug ({@link BlockBreaking#isDigging}).
 * <p>
 * Movement: the Java client is the source of truth for where it is; every Java client tick
 * ({@code ServerboundClientTickEnd}) becomes exactly one Bedrock auth input. If the client stops
 * ticking (frozen, still loading), a fallback timer keeps one input per proxy tick flowing, since
 * PowerNukkitX expects inputs continuously. Bursts are limited to {@value #INPUT_BURST} inputs per
 * 50 ms: PowerNukkitX queues at most 4 moves per server tick and drops the rest; a throttled tick is
 * merged into the next input (its one-shot flags and block actions are kept).
 */
public final class PlayerState implements SessionState {

    public static final StateKey<PlayerState> KEY = StateKey.of("player", PlayerState::new);

    /** Bedrock player positions are at eye level. */
    public static final float EYE_HEIGHT = 1.62f;

    /** {@link #teleport(double, double, double, float, float, int)}: keep the client's camera rotation. */
    public static final int TELEPORT_KEEP_ROTATION = 1;
    /** {@link #teleport(double, double, double, float, float, int)}: keep the client's velocity. */
    public static final int TELEPORT_KEEP_VELOCITY = 2;
    /** {@link #teleport(double, double, double, float, float, int)}: acknowledge a server teleport ({@code HANDLE_TELEPORT}). */
    public static final int TELEPORT_ACKNOWLEDGE = 4;

    private static final List<PositionElement> ABSOLUTE = List.of();
    private static final List<PositionElement> KEEP_ROTATION = List.of(PositionElement.Y_ROT, PositionElement.X_ROT);
    private static final List<PositionElement> KEEP_VELOCITY = List.of(PositionElement.DELTA_X, PositionElement.DELTA_Y, PositionElement.DELTA_Z);
    private static final List<PositionElement> KEEP_ROTATION_VELOCITY = List.of(PositionElement.Y_ROT, PositionElement.X_ROT,
            PositionElement.DELTA_X, PositionElement.DELTA_Y, PositionElement.DELTA_Z);

    /** Without a Java tick for this long, the proxy ticks the movement itself. */
    private static final long FALLBACK_NANOS = 150_000_000L;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int INPUT_BURST = 3;

    private static int genericDamageType = -1;

    private final ProxySession session;
    private final AuthInputBuilder input = new AuthInputBuilder();
    private final Abilities abilities = new Abilities();
    private final LocalStats stats;
    private final Interactions interactions;

    private long runtimeId;
    private long uniqueId;
    private int javaEntityId = 1;
    private @Nullable Vector3f position;
    private GameType gameType = GameType.SURVIVAL;
    private GameType worldGameType = GameType.SURVIVAL;
    private long hashedSeed;
    private int rewindHistorySize;
    private boolean spawned;
    private boolean dead;
    private boolean respawnRequested;
    private boolean showingCredits;
    private boolean immediateRespawn;
    private boolean changingDimension;
    private @Nullable GlobalPos deathPosition;
    private int operatorLevel = -1;
    private boolean dismountLocked;

    private int nextTeleportId = 1;
    private int pendingTeleportId;
    private boolean pendingTeleportAcknowledge;

    private long lastClientTickNanos;
    private long tokenTimeNanos;
    private int inputTokens = INPUT_BURST;

    private PlayerState(ProxySession session) {
        this.session = session;
        this.stats = new LocalStats(session, this);
        this.interactions = new Interactions(session, this);
    }

    /** Called by the join translator with the StartGame packet (runtime id, position, game mode...). */
    public void onStartGame(StartGamePacket packet) {
        this.runtimeId = packet.getRuntimeID();
        this.uniqueId = packet.getEntityID();
        this.javaEntityId = this.session.get(EntityTracker.KEY).bind(this.runtimeId);
        this.session.get(WorldState.KEY).onDimensionChange(this::onDimensionChanged);
        Vector3f eye = packet.getPosition();
        this.input.teleported(eye.getX(), eye.getY() - EYE_HEIGHT, eye.getZ(), packet.getRotation().getY(), packet.getRotation().getX());
        updatePosition();
        this.worldGameType = packet.getSettings().getGameType() == null || packet.getSettings().getGameType() == GameType.DEFAULT
                ? GameType.SURVIVAL : packet.getSettings().getGameType();
        this.gameType = packet.getGameType() == null ? GameType.DEFAULT : packet.getGameType();
        this.hashedSeed = packet.getSettings().getSeed();
        this.rewindHistorySize = packet.getMovementSettings() == null ? 0 : packet.getMovementSettings().getRewindHistorySize();
        onGameRules(packet.getSettings().getRuleData().getRulesList());
    }

    // ------------------------------------------------------------------ identity & state

    /** Bedrock runtime id of the local player. */
    public long runtimeId() {
        return this.runtimeId;
    }

    /** Bedrock unique id of the local player (what abilities and game type packets target). */
    public long uniqueId() {
        return this.uniqueId;
    }

    /** Java entity id of the local player. */
    public int javaEntityId() {
        return this.javaEntityId;
    }

    public boolean isLocal(long runtimeId) {
        return runtimeId == this.runtimeId;
    }

    /** Feet position (Java convention). */
    public Vector3f position() {
        Vector3f position = this.position;
        if (position == null) {
            position = Vector3f.from(this.input.x(), this.input.y(), this.input.z());
            this.position = position;
        }
        return position;
    }

    /** Yaw in {@code [-180, 180)}. */
    public float yaw() {
        return this.input.yaw();
    }

    public float pitch() {
        return this.input.pitch();
    }

    public boolean onGround() {
        return this.input.onGround();
    }

    /** The player's effective game type (DEFAULT resolved to the world's). */
    public GameType gameType() {
        return this.gameType == GameType.DEFAULT ? this.worldGameType : this.gameType;
    }

    public GameMode javaGameMode() {
        return GameModes.toJava(gameType());
    }

    public boolean isSpawned() {
        return this.spawned;
    }

    public void setSpawned(boolean spawned) {
        this.spawned = spawned;
    }

    public boolean isDead() {
        return this.dead;
    }

    /** Bedrock runtime id of the entity the player rides, or 0. */
    public long vehicleRuntimeId() {
        return this.input.vehicleRuntimeId();
    }

    AuthInputBuilder input() {
        return this.input;
    }

    Abilities abilities() {
        return this.abilities;
    }

    LocalStats stats() {
        return this.stats;
    }

    Interactions interactions() {
        return this.interactions;
    }

    int rewindHistorySize() {
        return this.rewindHistorySize;
    }

    /** The position changed: {@link #position()} is rebuilt on its next call only. */
    private void updatePosition() {
        this.position = null;
    }

    // ------------------------------------------------------------------ teleports

    /**
     * Moves the Java player (server-side teleport, absolute position and rotation). {@code feet} is
     * the feet position.
     *
     * @return the Java teleport id the client will acknowledge
     */
    public int teleport(Vector3f feet, float yaw, float pitch) {
        return teleport(feet.getX(), feet.getY(), feet.getZ(), yaw, pitch, 0);
    }

    /**
     * Moves the Java player. Until the client accepts this teleport its movements are ignored (like a
     * vanilla server), and the auth inputs report the destination, which is what PowerNukkitX expects
     * during its post-teleport grace period.
     *
     * @param flags {@link #TELEPORT_KEEP_ROTATION}, {@link #TELEPORT_KEEP_VELOCITY} (corrections that
     *              should not snap the camera or stop the player), {@link #TELEPORT_ACKNOWLEDGE}
     * @return the Java teleport id the client will acknowledge
     */
    public int teleport(double x, double y, double z, float yaw, float pitch, int flags) {
        boolean keepRotation = (flags & TELEPORT_KEEP_ROTATION) != 0;
        boolean keepVelocity = (flags & TELEPORT_KEEP_VELOCITY) != 0;
        int id = this.nextTeleportId++;
        this.pendingTeleportId = id;
        this.pendingTeleportAcknowledge = (flags & TELEPORT_ACKNOWLEDGE) != 0;
        this.input.teleported(x, y, z, keepRotation ? this.input.yaw() : yaw, keepRotation ? this.input.pitch() : pitch);
        updatePosition();
        List<PositionElement> relatives = keepRotation
                ? keepVelocity ? KEEP_ROTATION_VELOCITY : KEEP_ROTATION
                : keepVelocity ? KEEP_VELOCITY : ABSOLUTE;
        this.session.sendJava(new ClientboundPlayerPositionPacket(id, Vector3d.from(x, y, z), Vector3d.ZERO,
                keepRotation ? 0 : yaw, keepRotation ? 0 : pitch, relatives));
        return id;
    }

    /** Java {@code AcceptTeleportation}: the position and rotation the client applied. */
    void onTeleportAccepted(int id, double x, double y, double z, float yaw, float pitch) {
        if (id != this.pendingTeleportId) {
            return;
        }
        this.pendingTeleportId = 0;
        this.input.teleported(x, y, z, yaw, pitch);
        updatePosition();
        if (this.pendingTeleportAcknowledge) {
            this.input.handledTeleport();
        }
    }

    /** Whether Java movements are currently ignored (teleport not yet accepted, dimension change). */
    boolean ignoresJavaMoves() {
        return this.pendingTeleportId != 0 || this.changingDimension;
    }

    /** A Java movement packet; position and rotation only count when present. */
    void onJavaMove(boolean hasPosition, double x, double y, double z, boolean hasRotation, float yaw, float pitch,
                    boolean onGround, boolean horizontalCollision) {
        if (ignoresJavaMoves()) {
            return;
        }
        if (hasPosition) {
            this.input.setPosition(x, y, z);
            updatePosition();
        }
        if (hasRotation) {
            this.input.setRotation(yaw, pitch);
        }
        this.input.setStatus(onGround, horizontalCollision);
    }

    /**
     * World subsystem: a dimension change started (true) or the player is in the new dimension
     * (false). Java movements are ignored in between.
     */
    public void setDimensionChanging(boolean changing) {
        this.changingDimension = changing;
    }

    /**
     * Eye height the server adds to this player's positions ({@code MovePlayer}): it follows the
     * server-side bounding box, so it is lower while sneaking, swimming or gliding.
     */
    float serverEyeHeight() {
        if (this.input.swimming() || this.input.gliding()) {
            return 0.42f;
        }
        return this.input.sneaking() ? 1.32f : EYE_HEIGHT;
    }

    // ------------------------------------------------------------------ movement ticking

    /** Java {@code ClientTickEnd}: one auth input per Java tick. */
    void onClientTickEnd() {
        this.lastClientTickNanos = System.nanoTime();
        tickMovement();
    }

    private void tickMovement() {
        this.interactions.onMovementTick();
        if (!this.spawned || this.dead) {
            return;
        }
        long now = System.nanoTime();
        if (this.inputTokens < INPUT_BURST) {
            long refill = (now - this.tokenTimeNanos) / TICK_NANOS;
            if (refill > 0) {
                this.inputTokens = (int) Math.min(INPUT_BURST, this.inputTokens + refill);
                this.tokenTimeNanos += refill * TICK_NANOS;
            }
        }
        if (this.inputTokens == 0) {
            return;
        }
        if (this.inputTokens-- == INPUT_BURST) {
            this.tokenTimeNanos = now;
        }
        updateFluids();
        this.session.get(BlockBreaking.KEY).beforeInput(this.input);
        this.session.sendBedrock(this.input.build());
    }

    private void updateFluids() {
        if (!this.input.sprinting() && !this.input.swimming() && !this.input.gliding()) {
            this.input.setFluids(false, false);
            return;
        }
        int x = (int) Math.floor(this.input.x());
        int z = (int) Math.floor(this.input.z());
        this.input.setFluids(isWater(x, (int) Math.floor(this.input.y() + 0.1), z),
                isWater(x, (int) Math.floor(this.input.y() + EYE_HEIGHT), z));
    }

    private boolean isWater(int x, int y, int z) {
        WorldState world = this.session.get(WorldState.KEY);
        BlockMapper blocks = this.session.get(BlockMapper.KEY);
        return isWater(blocks.bedrockState(world.bedrockBlockAt(x, y, z, 0)))
                || isWater(blocks.bedrockState(world.bedrockBlockAt(x, y, z, 1)));
    }

    private static boolean isWater(@Nullable NbtMap state) {
        if (state == null) {
            return false;
        }
        String name = state.getString("name");
        return "minecraft:water".equals(name) || "minecraft:flowing_water".equals(name);
    }

    @Override
    public void tick() {
        if (this.spawned && System.nanoTime() - this.lastClientTickNanos > FALLBACK_NANOS) {
            tickMovement();
        }
        this.stats.tick();
        this.interactions.tick();
    }

    // ------------------------------------------------------------------ riding

    /**
     * Entity subsystem: the local player now rides {@code vehicleRuntimeId} ({@code SetActorLink}).
     * Boats are simulated by the client (Java {@code MoveVehicle} becomes the auth input position of a
     * "client predicted vehicle"); other mounts are moved by the server from the inputs.
     *
     * @param identifier Bedrock entity identifier of the vehicle, e.g. {@code minecraft:boat}
     */
    public void onLocalMount(long vehicleRuntimeId, String identifier) {
        this.input.setVehicle(vehicleRuntimeId, "minecraft:boat".equals(identifier) || "minecraft:chest_boat".equals(identifier));
    }

    /** Entity subsystem: the local player no longer rides anything. */
    public void onLocalDismount() {
        this.input.clearVehicle();
    }

    /** Sneaking while riding dismounts, like the Bedrock client (unless the server locked it). */
    void requestDismount() {
        long vehicle = this.input.vehicleRuntimeId();
        if (vehicle != 0 && !this.dismountLocked) {
            Interactions.interact(this.session, InteractPacket.Action.STOP_RIDING, vehicle);
        }
    }

    void setDismountLocked(boolean locked) {
        this.dismountLocked = locked;
    }

    // ------------------------------------------------------------------ game mode & abilities

    /** The player's own game type ({@code SetPlayerGameType}, {@code UpdatePlayerGameType}). */
    void setGameType(@Nullable GameType type) {
        GameMode previous = javaGameMode();
        this.gameType = type == null ? GameType.DEFAULT : type;
        if (previous != javaGameMode()) {
            onGameModeChanged();
        }
    }

    /** The world default ({@code SetDefaultGameType}). */
    void setWorldGameType(@Nullable GameType type) {
        GameMode previous = javaGameMode();
        this.worldGameType = type == null || type == GameType.DEFAULT ? GameType.SURVIVAL : type;
        if (previous != javaGameMode()) {
            onGameModeChanged();
        }
    }

    private void onGameModeChanged() {
        GameMode mode = javaGameMode();
        this.session.sendJava(new ClientboundGameEventPacket(GameEvent.CHANGE_GAME_MODE, mode));
        PlayerListEntry self = new PlayerListEntry(this.session.getProfile().getId());
        self.setGameMode(mode);
        this.session.sendJava(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(PlayerListEntryAction.UPDATE_GAME_MODE),
                new PlayerListEntry[]{self}));
        sendAbilities();
    }

    /** Sends the Java abilities and operator level from the current Bedrock abilities. */
    void sendAbilities() {
        GameMode mode = javaGameMode();
        boolean spectator = mode == GameMode.SPECTATOR;
        this.input.setServerFlight(this.abilities.mayFly() || spectator, this.abilities.flying() || spectator);
        this.session.sendJava(this.abilities.toJava(mode));
        int level = this.abilities.operatorLevel();
        if (level != this.operatorLevel) {
            this.operatorLevel = level;
            this.session.sendJava(new ClientboundEntityEventPacket(this.javaEntityId, Abilities.operatorEvent(level)));
        }
    }

    // ------------------------------------------------------------------ game rules

    /**
     * World subsystem: game rules from {@code StartGame} or {@code GameRulesChanged}. Only
     * {@code doImmediateRespawn} matters here (the Java client then skips the death screen).
     */
    public void onGameRules(List<GameRuleData<?>> rules) {
        for (int i = 0, size = rules.size(); i < size; i++) {
            GameRuleData<?> rule = rules.get(i);
            if ("doimmediaterespawn".equalsIgnoreCase(rule.getName()) && rule.getValue() instanceof Boolean value
                    && value != this.immediateRespawn) {
                this.immediateRespawn = value;
                this.session.sendJava(new ClientboundGameEventPacket(GameEvent.IMMEDIATE_RESPAWN,
                        value ? RespawnScreenValue.IMMEDIATE_RESPAWN : RespawnScreenValue.ENABLE_RESPAWN_SCREEN));
            }
        }
    }

    // ------------------------------------------------------------------ death & respawn

    /**
     * The player died: opens the Java death screen. Called with the Bedrock death message
     * ({@code DeathInfo}), or with null when only the health reached zero; a later message replaces
     * the generic one.
     */
    void onLocalDeath(@Nullable Component message) {
        if (!this.spawned) {
            return;
        }
        boolean first = !this.dead;
        if (first) {
            this.dead = true;
            this.respawnRequested = false;
            this.deathPosition = new GlobalPos(this.session.get(WorldState.KEY).worldKey(),
                    Vector3i.from(Math.floor(this.input.x()), Math.floor(this.input.y()), Math.floor(this.input.z())));
            this.session.get(BlockBreaking.KEY).reset();
            this.interactions.reset();
        }
        if (message != null || first) {
            this.session.sendJava(new ClientboundPlayerCombatKillPacket(this.javaEntityId, message != null ? message
                    : Component.translatable("death.attack.generic", Component.text(this.session.getName()))));
        }
    }

    /**
     * Java {@code PERFORM_RESPAWN}: the respawn button, or the end of the credits. Bedrock respawns in
     * two steps like the vanilla client: {@code Respawn(CLIENT_READY_TO_SPAWN)}, then, once the server
     * answered with the spawn position, {@code PlayerAction(RESPAWN)} ({@link #onReadyToSpawn}).
     */
    void requestRespawn() {
        if (this.showingCredits) {
            this.showingCredits = false;
            ShowCreditsPacket credits = new ShowCreditsPacket();
            credits.setPlayerRuntimeID(this.runtimeId);
            credits.setCreditsState(ShowCreditsPacket.CreditsState.END_CREDITS);
            this.session.sendBedrock(credits);
            return;
        }
        if (!this.dead || this.respawnRequested) {
            return;
        }
        this.respawnRequested = true;
        RespawnPacket respawn = new RespawnPacket();
        respawn.setPosition(Vector3f.ZERO);
        respawn.setState(PlayerRespawnState.CLIENT_READY_TO_SPAWN);
        respawn.setPlayerRuntimeId(this.runtimeId);
        this.session.sendBedrock(respawn);
    }

    /**
     * Bedrock {@code Respawn(READY_TO_SPAWN)}: where the player (re)spawns, eye position. After a
     * death this completes the respawn: Java {@code Respawn} (which resets the client's player, so
     * abilities, attributes, experience and the held slot are sent again), then the position.
     */
    void onReadyToSpawn(Vector3f eye) {
        double feetY = eye.getY() - EYE_HEIGHT;
        if (!this.dead || !this.respawnRequested) {
            teleport(eye.getX(), feetY, eye.getZ(), this.input.yaw(), this.input.pitch(), 0);
            return;
        }
        this.dead = false;
        this.respawnRequested = false;
        PlayerActionPacket action = new PlayerActionPacket();
        action.setPlayerRuntimeID(this.runtimeId);
        action.setAction(PlayerActionType.RESPAWN);
        action.setBlockPosition(Vector3i.ZERO);
        action.setResultPos(Vector3i.ZERO);
        action.setFace(-1);
        this.session.sendBedrock(action);

        int dimension = this.session.get(WorldState.KEY).dimension();
        // The world's current key (it alternates on same-dimension changes): another key would make
        // the client start a new world and drop the chunks the proxy believes it has.
        this.session.sendJava(new ClientboundRespawnPacket(new PlayerSpawnInfo(JavaDimensions.typeId(dimension),
                this.session.get(WorldState.KEY).worldKey(), this.hashedSeed, javaGameMode(), null, false, false, this.deathPosition, 0, 63),
                false, false));
        this.input.reset();
        this.operatorLevel = -1;
        sendAbilities();
        this.stats.onRespawn();
        this.session.sendJava(new ClientboundSetHeldSlotPacket(this.session.get(InventoryState.KEY).selectedSlot()));
        teleport(eye.getX(), feetY, eye.getZ(), this.input.yaw(), this.input.pitch(), 0);
        this.session.sendJava(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
    }

    /**
     * The world subsystem moved the Java client to another world (Bedrock dimension change): its new
     * player starts with default abilities and no effects, while nothing changed on Bedrock.
     */
    private void onDimensionChanged() {
        sendAbilities();
        this.stats.sendAttributes();
        this.stats.sendHealth();
        this.stats.sendExperience();
        this.stats.resendEffects();
        this.session.sendJava(new ClientboundSetHeldSlotPacket(this.session.get(InventoryState.KEY).selectedSlot()));
    }

    /** Bedrock {@code ShowCredits(START_CREDITS)}: the end poem; the Java client answers by respawning. */
    void showCredits() {
        this.showingCredits = true;
        this.session.sendJava(new ClientboundGameEventPacket(GameEvent.WIN_GAME, EnterCreditsValue.FIRST_TIME));
    }

    // ------------------------------------------------------------------ entity subsystem hooks

    /** {@code UpdateAttributes} of the local player: health, food, experience, speed... */
    public void onLocalAttributes(List<AttributeData> attributes) {
        this.stats.onAttributes(attributes);
    }

    /** {@code SetActorMotion} of the local player: knockback and other server-applied velocity. */
    public void onLocalMotion(Vector3f motion) {
        this.session.sendJava(new ClientboundSetEntityMotionPacket(this.javaEntityId,
                Vector3d.from(motion.getX(), motion.getY(), motion.getZ())));
    }

    /** {@code SetActorData} of the local player: fire, air, freezing, sleeping. */
    public void onLocalActorData(ActorDataMap data) {
        EnumSet<ActorFlags> flags = data.getFlags();
        if (flags != null && !flags.contains(ActorFlags.GLIDING)) {
            this.input.serverStoppedGliding();
        }
        this.stats.onActorData(data);
    }

    /** {@code MobEffect} of the local player. */
    public void onLocalEffect(MobEffectPacket packet) {
        this.stats.onEffect(packet);
    }

    /** {@code ActorEvent} of the local player: hurt animation and sound, death sound, totem. */
    public void onLocalEvent(ActorEventPacket packet) {
        if (packet.getType() == null) {
            return;
        }
        switch (packet.getType()) {
            case HURT -> this.session.sendJava(new ClientboundDamageEventPacket(this.javaEntityId, genericDamageType(), -1, -1, null));
            case DEATH -> this.session.sendJava(new ClientboundEntityEventPacket(this.javaEntityId, EntityEvent.LIVING_DEATH));
            case TALISMAN_ACTIVATE -> this.session.sendJava(new ClientboundEntityEventPacket(this.javaEntityId,
                    EntityEvent.TOTEM_OF_UNDYING_MAKE_SOUND));
            default -> {
                // Other self events (eating particles, potion drinking...) have no Java packet.
            }
        }
    }

    private static int genericDamageType() {
        if (genericDamageType < 0) {
            genericDamageType = Math.max(0, JavaRegistries.registry("minecraft:damage_type").id("minecraft:generic"));
        }
        return genericDamageType;
    }
}
