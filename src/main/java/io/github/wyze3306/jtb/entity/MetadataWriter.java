package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.IntMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.LongEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

/**
 * Collects the Java entity data of one entity, keeping only the values that differ from what the
 * client already has ({@link TrackedEntity}'s cache) and fields the entity's Java type actually has.
 * Primitive values are compared before anything is allocated, so a Bedrock update that changes
 * nothing visible costs no allocation. One instance per session, reused for every entity.
 */
final class MetadataWriter {

    private static final EntityMetadata<?, ?>[] EMPTY = new EntityMetadata<?, ?>[0];

    private final ArrayList<EntityMetadata<?, ?>> pending = new ArrayList<>(16);
    private TrackedEntity entity;
    private JavaEntityDefinition definition;

    /** Starts collecting for {@code entity}; anything not flushed before is dropped. */
    MetadataWriter begin(TrackedEntity entity) {
        this.entity = entity;
        this.definition = entity.definition();
        this.pending.clear();
        return this;
    }

    TrackedEntity entity() {
        return this.entity;
    }

    boolean has(MetaField field) {
        return this.definition.has(field);
    }

    void putByte(MetaField field, int value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateValue(index, (byte) value)) {
            this.pending.add(new ByteEntityMetadata(index, MetadataTypes.BYTE, (byte) value));
        }
    }

    void putInt(MetaField field, int value) {
        putInt(field, MetadataTypes.INT, value);
    }

    void putInt(MetaField field, IntMetadataType type, int value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateValue(index, value)) {
            this.pending.add(new IntEntityMetadata(index, type, value));
        }
    }

    void putLong(MetaField field, long value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateValue(index, value)) {
            this.pending.add(new LongEntityMetadata(index, MetadataTypes.LONG, value));
        }
    }

    void putFloat(MetaField field, float value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateValue(index, Float.floatToIntBits(value))) {
            this.pending.add(new FloatEntityMetadata(index, MetadataTypes.FLOAT, value));
        }
    }

    void putBoolean(MetaField field, boolean value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateValue(index, value ? 1 : 0)) {
            this.pending.add(new BooleanEntityMetadata(index, MetadataTypes.BOOLEAN, value));
        }
    }

    /** Object-valued field; {@code value} is compared with {@code equals}, so pass cached instances where possible. */
    <T> void putObject(MetaField field, MetadataType<T> type, T value) {
        int index = this.definition.index(field);
        if (index >= 0 && this.entity.updateObject(index, value)) {
            this.pending.add(new ObjectEntityMetadata<>(index, type, value));
        }
    }

    boolean isEmpty() {
        return this.pending.isEmpty();
    }

    /** The collected entries (null when nothing changed); clears the writer. */
    EntityMetadata<?, ?> @Nullable [] drain() {
        if (this.pending.isEmpty()) {
            return null;
        }
        EntityMetadata<?, ?>[] out = this.pending.toArray(EMPTY);
        this.pending.clear();
        return out;
    }

    /** Sends what changed as one {@link ClientboundSetEntityDataPacket}. */
    void flush(ProxySession session) {
        EntityMetadata<?, ?>[] metadata = drain();
        if (metadata != null) {
            session.sendJava(new ClientboundSetEntityDataPacket(this.entity.javaId(), metadata));
        }
    }
}
