package io.github.wyze3306.jtb.entity;

import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Arrays;

/**
 * A Java 26.3 entity type as the proxy sees it: its entity data layout (index of every
 * {@link MetaField}), its hit box and the Bedrock position offset ({@code bedrockY = javaY + offset}).
 * Immutable, shared by every session.
 */
public final class JavaEntityDefinition {

    private final EntityType type;
    private final float width;
    private final float height;
    private final float offset;
    private final byte[] indices;
    private final String[] fieldNames;
    private final MetadataType<?>[] fieldTypes;

    JavaEntityDefinition(EntityType type, float width, float height, float offset, String[] fieldNames,
                         MetadataType<?>[] fieldTypes) {
        this.type = type;
        this.width = width;
        this.height = height;
        this.offset = offset;
        this.fieldNames = fieldNames;
        this.fieldTypes = fieldTypes;
        this.indices = new byte[MetaField.VALUES.length];
        Arrays.fill(this.indices, (byte) -1);
        for (int i = 0; i < fieldNames.length; i++) {
            MetaField field = field(fieldNames[i]);
            if (field != null) {
                this.indices[field.ordinal()] = (byte) i;
            }
        }
    }

    private static MetaField field(String name) {
        for (MetaField field : MetaField.VALUES) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    public EntityType type() {
        return this.type;
    }

    /** Hit box width in blocks (0 for entities without one, such as displays). */
    public float width() {
        return this.width;
    }

    public float height() {
        return this.height;
    }

    /** How far above the Java (feet) position Bedrock places this entity. */
    public float offset() {
        return this.offset;
    }

    /** Entity data index of {@code field} for this type, or -1 when the type has no such field. */
    public int index(MetaField field) {
        return this.indices[field.ordinal()];
    }

    public boolean has(MetaField field) {
        return this.indices[field.ordinal()] >= 0;
    }

    /** Number of entity data fields of this type. */
    public int fieldCount() {
        return this.fieldNames.length;
    }

    /** Vanilla name of the field at {@code index}. */
    public String fieldName(int index) {
        return this.fieldNames[index];
    }

    /** Java metadata type of the field at {@code index}. */
    public MetadataType<?> fieldType(int index) {
        return this.fieldTypes[index];
    }

    /** Living entities have health, effects and attributes. */
    public boolean isLiving() {
        return has(MetaField.HEALTH);
    }

    public boolean isPlayerLike() {
        return has(MetaField.PLAYER_MODE_CUSTOMISATION);
    }

    @Override
    public String toString() {
        return "JavaEntityDefinition[" + this.type + "]";
    }
}
