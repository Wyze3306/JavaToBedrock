package io.github.wyze3306.jtb.entity;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.BooleanMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.ByteMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.FloatMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.IntMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.LongMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.LongEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

/**
 * One Bedrock entity known to the Java client: its ids on both sides, the Java type it is shown as,
 * the last position sent to the client (Java feet coordinates), the Bedrock state the translation
 * needs to keep (flags, name, variants, scale...) and a cache of the entity data last sent, so only
 * changes go out.
 * <p>
 * Session-confined (event loop only). Other subsystems may read it; only the entity subsystem
 * changes it.
 */
public final class TrackedEntity {

    /** How the entity is represented on the Java side. */
    public enum Kind {
        /** A vanilla Java entity type. */
        VANILLA,
        /** Another Bedrock player (Java player entity with a player list entry). */
        PLAYER,
        /** The local player itself (Java entity id of the client). */
        LOCAL_PLAYER,
        /** A Bedrock entity without Java equivalent, drawn by a {@link CustomEntityRenderer}. */
        CUSTOM
    }

    private static final int[] NO_PARTS = new int[0];

    private final long runtimeId;
    private final long uniqueId;
    private final int javaId;
    private final UUID uuid;
    private final String identifier;
    private final JavaEntityDefinition definition;
    private final Kind kind;

    // Last state sent to the Java client (feet position).
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    float headYaw;
    boolean onGround;

    // Bedrock state kept for translation.
    final EnumSet<ActorFlags> flags = EnumSet.noneOf(ActorFlags.class);
    String nameTag = "";
    @Nullable Component nameComponent;
    boolean nameTagAlwaysShow;
    float scale = 1;
    float javaScale = 1;
    float bedrockWidth = -1;
    float bedrockHeight = -1;
    int variant;
    int markVariant;
    int color;
    int color2;
    int tradeTier;
    int effectColor;
    boolean effectAmbient;
    int beeFlags;
    float health = -1;
    float maxHealth = -1;
    float absorption;
    boolean dead;
    int itemCount = 1;
    long leashHolder = -1;
    long ownerUniqueId = -1;
    @Nullable Vector3i bedPosition;

    // Riding.
    long vehicle = -1;
    @Nullable LongArrayList passengers;

    // Proxy-side Java entities of a custom entity (interaction, displays...).
    int[] parts = NO_PARTS;
    @Nullable Object rendererState;

    // Bedrock effect id -> remaining ticks (-1: infinite), counted down for removal on Java.
    @Nullable Int2IntOpenHashMap effects;

    // Bedrock identifiers of the held items (shield blocking pose).
    @Nullable String mainHandItem;
    @Nullable String offHandItem;
    // Java equipment last sent, by EquipmentSlot ordinal (null until the first equipment packet).
    @Nullable ItemStack @Nullable [] equipment;

    // Player list entry of a player entity.
    PlayerListState.@Nullable Entry playerEntry;

    // Entity data last sent: one slot per Java field index.
    private final long[] sentValues;
    private final @Nullable Object[] sentObjects;
    private final @Nullable MetadataType<?>[] sentTypes;
    private long sentMask;

    TrackedEntity(long runtimeId, long uniqueId, int javaId, UUID uuid, String identifier,
                  JavaEntityDefinition definition, Kind kind) {
        this.runtimeId = runtimeId;
        this.uniqueId = uniqueId;
        this.javaId = javaId;
        this.uuid = uuid;
        this.identifier = identifier;
        this.definition = definition;
        this.kind = kind;
        this.sentValues = new long[definition.fieldCount()];
        this.sentObjects = new Object[definition.fieldCount()];
        this.sentTypes = new MetadataType<?>[definition.fieldCount()];
    }

    public long runtimeId() {
        return this.runtimeId;
    }

    public long uniqueId() {
        return this.uniqueId;
    }

    /** Java entity id (for a custom entity: its hit box). */
    public int javaId() {
        return this.javaId;
    }

    public UUID uuid() {
        return this.uuid;
    }

    /** Bedrock entity identifier, e.g. {@code minecraft:zombie}. */
    public String identifier() {
        return this.identifier;
    }

    public JavaEntityDefinition definition() {
        return this.definition;
    }

    public EntityType javaType() {
        return this.definition.type();
    }

    public Kind kind() {
        return this.kind;
    }

    public boolean isPlayer() {
        return this.kind == Kind.PLAYER || this.kind == Kind.LOCAL_PLAYER;
    }

    /** Java feet position last sent to the client. */
    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public float headYaw() {
        return this.headYaw;
    }

    public boolean onGround() {
        return this.onGround;
    }

    /** Current Bedrock flags (live view, do not modify). */
    public EnumSet<ActorFlags> flags() {
        return this.flags;
    }

    /** Bedrock name tag text (raw, with formatting codes), empty when none. */
    public String nameTag() {
        return this.nameTag;
    }

    /** Bedrock scale ({@code SCALE} entity data). */
    public float scale() {
        return this.scale;
    }

    /** Bedrock hit box width (unscaled), or the Java type's when the server did not send one. */
    public float bedrockWidth() {
        return this.bedrockWidth >= 0 ? this.bedrockWidth : this.definition.width();
    }

    public float bedrockHeight() {
        return this.bedrockHeight >= 0 ? this.bedrockHeight : this.definition.height();
    }

    /** Runtime id of the entity this one rides, or -1. */
    public long vehicle() {
        return this.vehicle;
    }

    public boolean isDead() {
        return this.dead;
    }

    /** Proxy-side Java entity ids that belong to this entity (custom entity parts). */
    public int[] parts() {
        return this.parts;
    }

    /** Free slot for the {@link CustomEntityRenderer} drawing this entity. */
    public @Nullable Object rendererState() {
        return this.rendererState;
    }

    public void setRendererState(@Nullable Object rendererState) {
        this.rendererState = rendererState;
    }

    // ------------------------------------------------------------------ entity data cache

    /** Records a primitive value for field {@code index}; true when it differs from what was sent. */
    boolean updateValue(int index, MetadataType<?> type, long bits) {
        long bit = 1L << index;
        if ((this.sentMask & bit) != 0 && this.sentValues[index] == bits) {
            return false;
        }
        this.sentMask |= bit;
        this.sentValues[index] = bits;
        this.sentTypes[index] = type;
        return true;
    }

    /** Records an object value for field {@code index}; true when it differs from what was sent. */
    boolean updateObject(int index, MetadataType<?> type, @Nullable Object value) {
        long bit = 1L << index;
        if ((this.sentMask & bit) != 0 && Objects.equals(this.sentObjects[index], value)) {
            return false;
        }
        this.sentMask |= bit;
        this.sentObjects[index] = value;
        this.sentTypes[index] = type;
        return true;
    }

    /**
     * Everything the client was sent, rebuilt from the cache: the entity data of a Java entity spawned
     * again (a player re-added with its skin), or {@code null} when nothing was sent.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    EntityMetadata<?, ?> @Nullable [] sentMetadata() {
        if (this.sentMask == 0) {
            return null;
        }
        EntityMetadata<?, ?>[] out = new EntityMetadata<?, ?>[Long.bitCount(this.sentMask)];
        int n = 0;
        for (int i = 0; i < this.sentTypes.length; i++) {
            if ((this.sentMask & (1L << i)) == 0) {
                continue;
            }
            MetadataType<?> type = this.sentTypes[i];
            long bits = this.sentValues[i];
            if (type instanceof ByteMetadataType t) {
                out[n++] = new ByteEntityMetadata(i, t, (byte) bits);
            } else if (type instanceof IntMetadataType t) {
                out[n++] = new IntEntityMetadata(i, t, (int) bits);
            } else if (type instanceof LongMetadataType t) {
                out[n++] = new LongEntityMetadata(i, t, bits);
            } else if (type instanceof FloatMetadataType t) {
                out[n++] = new FloatEntityMetadata(i, t, Float.intBitsToFloat((int) bits));
            } else if (type instanceof BooleanMetadataType t) {
                out[n++] = new BooleanEntityMetadata(i, t, bits != 0);
            } else {
                out[n++] = new ObjectEntityMetadata(i, (MetadataType) type, this.sentObjects[i]);
            }
        }
        return out;
    }

    /** Forgets what was sent (the Java client recreated the entity). */
    void resetSent() {
        this.sentMask = 0;
    }

    @Override
    public String toString() {
        return "TrackedEntity[" + this.identifier + ", runtime " + this.runtimeId + ", java " + this.javaId + "]";
    }
}
