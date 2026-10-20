package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.world.blockentity.BlockEntityTranslator;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.ClockNetworkState;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.LimitedCraftingValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RespawnScreenValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CONTRACT (owner: world subsystem). The world as the Java client sees it: current dimension, the
 * loaded chunks (Bedrock and Java blocks, block entities), game rules and time, kept so other
 * subsystems can look blocks up (interaction, light, sounds...). Coordinates are absolute block
 * coordinates; the same in both editions. Must be used on the session loop.
 */
public final class WorldState implements SessionState {

    public static final StateKey<WorldState> KEY = StateKey.of("world", WorldState::new);

    /** Bedrock game rules as servers spell them, lower-cased. */
    public static final String RULE_DAYLIGHT_CYCLE = "dodaylightcycle";
    public static final String RULE_IMMEDIATE_RESPAWN = "doimmediaterespawn";
    public static final String RULE_SHOW_COORDINATES = "showcoordinates";
    public static final String RULE_LIMITED_CRAFTING = "dolimitedcrafting";

    private final ProxySession session;
    private final WorldBiomes biomes = new WorldBiomes();
    private final Map<String, Object> gameRules = new HashMap<>();
    private final Map<Integer, int[]> dimensionHeights = new HashMap<>();
    private final List<Runnable> dimensionListeners = new ArrayList<>();
    private int dimension;
    private Key worldKey = JavaDimensions.OVERWORLD;
    private @Nullable ChunkManager chunks;
    private long gameTime;
    private long dayTime;
    private boolean clockPaused;
    private long overworldClockId = -1;
    private @Nullable Vector3i openSign;
    private boolean openSignFront;
    private @Nullable Integer loadingScreenId;
    private boolean changingDimension;

    private WorldState(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------ join

    /** Reads the initial dimension, time and game rules, and prepares the chunk pipeline. */
    public void onStartGame(StartGamePacket packet) {
        this.dimension = packet.getSettings().getSpawnSettings().getDimension().getValue();
        this.worldKey = JavaDimensions.key(this.dimension);
        for (GameRuleData<?> rule : packet.getSettings().getRuleData().getRulesList()) {
            this.gameRules.put(rule.getName().toLowerCase(Locale.ROOT), rule.getValue());
        }
        this.gameTime = Math.max(0, packet.getLevelCurrentTime());
        this.dayTime = packet.getSettings().getDayCycleStopTime();
        this.clockPaused = !gameRule(RULE_DAYLIGHT_CYCLE, true);
        this.chunks = new ChunkManager(this.session, this, layout(this.dimension), this.biomes.table());
        sendTime();
    }

    /**
     * Custom biomes of the server ({@code BiomeDefinitionList}); registered as Java biomes while the
     * client is still configuring.
     */
    void onBiomeDefinitions(BiomeDefinitionListPacket packet) {
        JavaConfiguration configuration = this.session.get(JavaConfiguration.KEY);
        if (this.biomes.onDefinitions(packet, configuration, this.session.logger(), this.session.getName()) && this.chunks != null) {
            this.chunks.setBiomes(this.biomes.table());
        }
    }

    /** Heights of custom-sized vanilla dimensions ({@code DimensionDataPacket}). */
    void onDimensionData(List<DimensionDefinition> definitions) {
        for (DimensionDefinition definition : definitions) {
            int id = switch (definition.getName()) {
                case "minecraft:overworld" -> 0;
                case "minecraft:nether", "minecraft:the_nether" -> 1;
                case "minecraft:the_end" -> 2;
                default -> -1;
            };
            if (id >= 0) {
                this.dimensionHeights.put(id, new int[]{definition.getHeightMinimum(), definition.getHeightMaximum()});
            } else {
                this.session.logger().debug("[{}] Custom Bedrock dimension {} is not supported", this.session.getName(), definition.getName());
            }
        }
    }

    private ChunkLayout layout(int bedrockDimension) {
        int[] heights = this.dimensionHeights.get(bedrockDimension);
        int bedrockMin = heights != null ? heights[0] >> 4 : (bedrockDimension == 0 ? -4 : 0);
        int bedrockSections = heights != null ? Math.max(1, (heights[1] - heights[0] + 16) >> 4)
                : (bedrockDimension == 0 ? 24 : bedrockDimension == 1 ? 8 : 16);
        String biome = switch (bedrockDimension) {
            case 1 -> "minecraft:nether_wastes";
            case 2 -> "minecraft:the_end";
            default -> "minecraft:plains";
        };
        int defaultBiome = JavaRegistries.registry("minecraft:worldgen/biome").id(biome);
        return new ChunkLayout(bedrockMin, bedrockSections, JavaDimensions.minY(bedrockDimension) >> 4,
                JavaDimensions.height(bedrockDimension) >> 4, bedrockDimension != 1, defaultBiome >= 0 ? defaultBiome : BiomeMappings.plains());
    }

    // ------------------------------------------------------------------ contract

    /** Bedrock dimension id: 0 overworld, 1 nether, 2 end. */
    public int dimension() {
        return this.dimension;
    }

    /** Java level key of the current world (it alternates when a respawn keeps the dimension). */
    public Key worldKey() {
        return this.worldKey;
    }

    /** Lowest block Y of the current dimension (overworld -64). */
    public int minY() {
        return JavaDimensions.minY(this.dimension);
    }

    /** World height in blocks of the current dimension (overworld 384). */
    public int height() {
        return JavaDimensions.height(this.dimension);
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return this.chunks != null && this.chunks.isLoaded(chunkX, chunkZ);
    }

    /** Java state id at a position (air when not loaded). */
    public int javaBlockAt(int x, int y, int z) {
        return this.chunks == null ? JavaBlockStates.AIR : this.chunks.javaBlockAt(x, y, z);
    }

    /** Bedrock runtime id at a position in layer 0 (block) or 1 (liquid); air when not loaded. */
    public int bedrockBlockAt(int x, int y, int z, int layer) {
        return this.chunks == null ? this.session.get(BlockMapper.KEY).bedrockAir() : this.chunks.bedrockBlockAt(x, y, z, layer);
    }

    /** The Bedrock block entity NBT at a position, or null. */
    public @Nullable NbtMap bedrockBlockEntityAt(int x, int y, int z) {
        return this.chunks == null ? null : this.chunks.bedrockBlockEntityAt(x, y, z);
    }

    /**
     * The item frame a Java entity id stands for, or null when the id is not a frame. Bedrock frames
     * are blocks the Java client sees as {@code item_frame} / {@code glow_item_frame} entities; the
     * player module turns Java attacks and uses of those entities into clicks on the block.
     */
    public @Nullable ItemFrame itemFrame(int javaEntityId) {
        return this.chunks == null ? null : this.chunks.itemFrame(javaEntityId);
    }

    /** A boolean Bedrock game rule (name in any case), or {@code fallback} when the server did not send it. */
    public boolean gameRule(String name, boolean fallback) {
        return this.gameRules.get(name.toLowerCase(Locale.ROOT)) instanceof Boolean value ? value : fallback;
    }

    /** A numeric Bedrock game rule, or {@code fallback}. */
    public int gameRuleInt(String name, int fallback) {
        return this.gameRules.get(name.toLowerCase(Locale.ROOT)) instanceof Number value ? value.intValue() : fallback;
    }

    /** Whether the Java client should hide coordinates (Bedrock {@code showCoordinates} off). */
    public boolean reducedDebugInfo() {
        return !gameRule(RULE_SHOW_COORDINATES, false);
    }

    /**
     * Registers a task run on the session loop right after the Java client was moved to another
     * world ({@code ClientboundRespawnPacket} for a Bedrock dimension change). A respawn gives the
     * Java client a fresh player: abilities, inventory, held slot, effects and attributes must be
     * sent again, and the entities of the old dimension are gone.
     */
    public void onDimensionChange(Runnable listener) {
        this.dimensionListeners.add(listener);
    }

    // ------------------------------------------------------------------ internal access for translators

    @Nullable ChunkManager chunks() {
        return this.chunks;
    }

    void setOpenSign(Vector3i position, boolean front) {
        this.openSign = position;
        this.openSignFront = front;
    }

    /** Whether {@code position} is the sign the server opened for editing; forgets it. */
    boolean takeOpenSign(Vector3i position) {
        Vector3i sign = this.openSign;
        this.openSign = null;
        return sign != null && sign.equals(position);
    }

    boolean openSignFront() {
        return this.openSignFront;
    }

    /** Bedrock sign NBT for a Java sign edit, built from what the server last sent for that sign. */
    NbtMap signUpdate(Vector3i position, boolean front, List<String> lines) {
        NbtMap stored = bedrockBlockEntityAt(position.getX(), position.getY(), position.getZ());
        int state = javaBlockAt(position.getX(), position.getY(), position.getZ());
        String block = JavaBlockStates.blockName(JavaBlockStates.blockId(state));
        return BlockEntityTranslator.signUpdate(stored, position.getX(), position.getY(), position.getZ(),
                block.endsWith("hanging_sign"), front, lines);
    }

    // ------------------------------------------------------------------ dimension change

    /**
     * Prepares the move into {@code bedrockDimension}: a new chunk store, and a new Java world key
     * (alternating when the dimension does not change, so the client still drops its chunks).
     *
     * @return the Java world key to respawn into
     */
    Key changeDimension(int bedrockDimension, @Nullable Integer loadingScreen) {
        Key target = JavaDimensions.key(bedrockDimension);
        if (target.equals(this.worldKey)) {
            target = Key.key("jtb", target.value());
        }
        this.dimension = bedrockDimension;
        this.worldKey = target;
        this.loadingScreenId = loadingScreen;
        this.changingDimension = true;
        this.openSign = null;
        if (this.chunks != null) {
            this.chunks.reset(layout(bedrockDimension), this.biomes.table());
        }
        return target;
    }

    void runDimensionListeners() {
        for (Runnable listener : List.copyOf(this.dimensionListeners)) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                this.session.logger().error("[{}] Dimension change listener failed", this.session.getName(), e);
            }
        }
    }

    boolean isChangingDimension() {
        return this.changingDimension;
    }

    /** The server acknowledged the dimension change; returns the loading screen id to close. */
    @Nullable Integer finishDimensionChange() {
        this.changingDimension = false;
        Integer id = this.loadingScreenId;
        this.loadingScreenId = null;
        return id;
    }

    // ------------------------------------------------------------------ time and rules

    void onTime(long time, boolean paused) {
        this.dayTime = time;
        this.clockPaused = paused || !gameRule(RULE_DAYLIGHT_CYCLE, true);
        sendTime();
    }

    void onClockRegistry(long overworldClock) {
        this.overworldClockId = overworldClock;
    }

    /** Whether a Bedrock clock id is the overworld clock (any id before the registry is known). */
    boolean isOverworldClock(long clockId) {
        return this.overworldClockId < 0 || this.overworldClockId == clockId;
    }

    void sendTime() {
        Map<Integer, ClockNetworkState> clocks = new HashMap<>();
        int overworld = JavaRegistries.registry("minecraft:world_clock").id("minecraft:overworld");
        if (overworld >= 0) {
            clocks.put(overworld, new ClockNetworkState(Math.max(0, this.dayTime), 0f, this.clockPaused ? 0f : 1f));
        }
        this.session.sendJava(new ClientboundSetTimePacket(this.gameTime, clocks));
    }

    void onGameRules(List<GameRuleData<?>> rules) {
        for (GameRuleData<?> rule : rules) {
            String name = rule.getName().toLowerCase(Locale.ROOT);
            Object value = rule.getValue();
            if (value == null || value.equals(this.gameRules.put(name, value))) {
                continue;
            }
            boolean on = Boolean.TRUE.equals(value);
            switch (name) {
                case RULE_DAYLIGHT_CYCLE -> {
                    this.clockPaused = !on;
                    sendTime();
                }
                case RULE_IMMEDIATE_RESPAWN -> this.session.sendJava(new ClientboundGameEventPacket(GameEvent.IMMEDIATE_RESPAWN,
                        on ? RespawnScreenValue.IMMEDIATE_RESPAWN : RespawnScreenValue.ENABLE_RESPAWN_SCREEN));
                case RULE_LIMITED_CRAFTING -> this.session.sendJava(new ClientboundGameEventPacket(GameEvent.LIMITED_CRAFTING,
                        on ? LimitedCraftingValue.LIMITED_CRAFTING : LimitedCraftingValue.UNLIMITED_CRAFTING));
                case RULE_SHOW_COORDINATES -> this.session.sendJava(new ClientboundEntityEventPacket(
                        this.session.get(PlayerState.KEY).javaEntityId(),
                        on ? EntityEvent.PLAYER_DISABLE_REDUCED_DEBUG : EntityEvent.PLAYER_ENABLE_REDUCED_DEBUG));
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void tick() {
        this.gameTime++;
        if (!this.clockPaused) {
            this.dayTime++;
        }
        if (this.chunks != null) {
            this.chunks.tick();
        }
    }

    @Override
    public void close() {
        if (this.chunks != null) {
            this.chunks.close();
        }
    }
}
