package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.actor.FloatEntityProperty;
import org.cloudburstmc.protocol.bedrock.data.actor.IntEntityProperty;
import org.cloudburstmc.protocol.bedrock.data.actor.PropertySyncData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.ArmadilloState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.CopperGolemState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.IntMetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.WeatheringCopperState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock entity properties: the per-type schemas of {@code SyncActorProperty} and the values of
 * {@code AddActor}/{@code SetActorData} ({@link PropertySyncData}, indexed by schema position). Vanilla
 * properties that Java keeps as entity data (climate and sound variants, copper golem, armadillo,
 * creaking and bee states...) are translated; properties of custom entities go to the
 * {@link CustomEntityRenderer}.
 */
final class EntityProperties {

    private static final int TYPE_INT = 0;
    private static final int TYPE_FLOAT = 1;
    private static final int TYPE_BOOL = 2;
    private static final int TYPE_ENUM = 3;

    record Property(String name, int type, String[] values) {
    }

    private final Map<String, Property[]> schemas = new HashMap<>();

    /** {@code SyncActorProperty}: {@code {type: "minecraft:pig", properties: [{name, type, enum?...}]}}. */
    void onSync(NbtMap data) {
        String type = data.getString("type", "");
        List<NbtMap> list = data.getList("properties", NbtType.COMPOUND);
        Property[] properties = new Property[list.size()];
        for (int i = 0; i < properties.length; i++) {
            NbtMap entry = list.get(i);
            properties[i] = new Property(entry.getString("name", ""), entry.getInt("type", TYPE_INT),
                    entry.getList("enum", NbtType.STRING).toArray(String[]::new));
        }
        this.schemas.put(type, properties);
    }

    boolean isEmpty(PropertySyncData data) {
        return data.getIntProperties().isEmpty() && data.getFloatProperties().isEmpty();
    }

    void apply(ProxySession session, EntityTracker tracker, TrackedEntity entity, PropertySyncData data, MetadataWriter out) {
        Property[] schema = this.schemas.get(entity.identifier());
        if (schema == null) {
            return;
        }
        for (IntEntityProperty property : data.getIntProperties()) {
            int index = property.getIndex();
            if (index < 0 || index >= schema.length) {
                continue;
            }
            Property definition = schema[index];
            int value = property.getValue();
            if (entity.kind() == TrackedEntity.Kind.CUSTOM) {
                Object shown = switch (definition.type()) {
                    case TYPE_ENUM -> value >= 0 && value < definition.values().length ? definition.values()[value] : "";
                    case TYPE_BOOL -> value != 0;
                    default -> value;
                };
                tracker.customRenderer().property(session, tracker, entity, definition.name(), shown);
            } else if (definition.type() == TYPE_ENUM) {
                if (value >= 0 && value < definition.values().length) {
                    applyEnum(entity, definition.name(), definition.values()[value], out);
                }
            } else {
                applyInt(entity, definition.name(), value, out);
            }
        }
        if (entity.kind() == TrackedEntity.Kind.CUSTOM) {
            for (FloatEntityProperty property : data.getFloatProperties()) {
                int index = property.getIndex();
                if (index >= 0 && index < schema.length && schema[index].type() == TYPE_FLOAT) {
                    tracker.customRenderer().property(session, tracker, entity, schema[index].name(), property.getValue());
                }
            }
        }
    }

    private static void applyInt(TrackedEntity entity, String name, int value, MetadataWriter out) {
        switch (name) {
            case "minecraft:has_nectar" -> {
                if (entity.javaType() == EntityType.BEE) {
                    entity.beeFlags = (entity.beeFlags & ~0x08) | (value != 0 ? 0x08 : 0);
                    out.putByte(MetaField.FLAGS, entity.beeFlags);
                }
            }
            case "minecraft:can_move" -> out.putBoolean(MetaField.STAYS_STILL, value == 0);
            default -> {
            }
        }
    }

    private static void applyEnum(TrackedEntity entity, String name, String value, MetadataWriter out) {
        EntityType type = entity.javaType();
        switch (name) {
            case "minecraft:climate_variant" -> {
                switch (type) {
                    case PIG -> putVariant(out, MetaField.VARIANT, MetadataTypes.PIG_VARIANT, "minecraft:pig_variant", value);
                    case COW -> putVariant(out, MetaField.VARIANT, MetadataTypes.COW_VARIANT, "minecraft:cow_variant", value);
                    case CHICKEN -> putVariant(out, MetaField.VARIANT, MetadataTypes.CHICKEN_VARIANT, "minecraft:chicken_variant", value);
                    case ZOMBIE_NAUTILUS -> putVariant(out, MetaField.VARIANT, MetadataTypes.ZOMBIE_NAUTILUS_VARIANT,
                            "minecraft:zombie_nautilus_variant", value);
                    default -> {
                    }
                }
            }
            case "minecraft:sound_variant" -> {
                String sound = switch (value) {
                    case "default" -> "classic";
                    case "mad" -> "angry";
                    default -> value;
                };
                switch (type) {
                    case PIG -> putVariant(out, MetaField.SOUND_VARIANT, MetadataTypes.PIG_SOUND_VARIANT, "minecraft:pig_sound_variant", sound);
                    case COW -> putVariant(out, MetaField.SOUND_VARIANT, MetadataTypes.COW_SOUND_VARIANT, "minecraft:cow_sound_variant", sound);
                    case CHICKEN -> putVariant(out, MetaField.SOUND_VARIANT, MetadataTypes.CHICKEN_SOUND_VARIANT,
                            "minecraft:chicken_sound_variant", sound);
                    case WOLF -> putVariant(out, MetaField.SOUND_VARIANT, MetadataTypes.WOLF_SOUND_VARIANT, "minecraft:wolf_sound_variant", sound);
                    case CAT -> putVariant(out, MetaField.SOUND_VARIANT, MetadataTypes.CAT_SOUND_VARIANT, "minecraft:cat_sound_variant", sound);
                    default -> {
                    }
                }
            }
            case "minecraft:chest_interaction" -> {
                CopperGolemState state = switch (value) {
                    case "take" -> CopperGolemState.GETTING_ITEM;
                    case "take_fail" -> CopperGolemState.GETTING_NO_ITEM;
                    case "put" -> CopperGolemState.DROPPING_ITEM;
                    case "put_fail" -> CopperGolemState.DROPPING_NO_ITEM;
                    default -> CopperGolemState.IDLE;
                };
                out.putObject(MetaField.COPPER_GOLEM_STATE, MetadataTypes.COPPER_GOLEM_STATE, state);
            }
            case "minecraft:oxidation_level" -> {
                WeatheringCopperState state = switch (value) {
                    case "exposed" -> WeatheringCopperState.EXPOSED;
                    case "weathered" -> WeatheringCopperState.WEATHERED;
                    case "oxidized" -> WeatheringCopperState.OXIDIZED;
                    default -> WeatheringCopperState.UNAFFECTED;
                };
                out.putObject(MetaField.WEATHER_STATE, MetadataTypes.WEATHERING_COPPER_STATE, state);
            }
            case "minecraft:armadillo_state" -> {
                ArmadilloState state = switch (value) {
                    case "rolled_up", "rolled_up_relaxing" -> ArmadilloState.ROLLING;
                    case "rolled_up_peeking" -> ArmadilloState.SCARED;
                    case "rolled_up_unrolling" -> ArmadilloState.UNROLLING;
                    default -> ArmadilloState.IDLE;
                };
                out.putObject(MetaField.ARMADILLO_STATE, MetadataTypes.ARMADILLO_STATE, state);
            }
            case "minecraft:creaking_state" -> {
                boolean hostile = value.startsWith("hostile") || value.equals("twitching");
                out.putBoolean(MetaField.IS_ACTIVE, hostile);
                out.putBoolean(MetaField.CAN_MOVE, !value.equals("hostile_observed") && !value.equals("twitching"));
                out.putBoolean(MetaField.IS_TEARING_DOWN, value.equals("crumbling"));
            }
            default -> {
            }
        }
    }

    private static void putVariant(MetadataWriter out, MetaField field, IntMetadataType type, String registry, String value) {
        int id = JavaRegistries.registry(registry).id("minecraft:" + value);
        if (id >= 0) {
            out.putInt(field, type, id);
        }
    }

    @Nullable Property[] schema(String identifier) {
        return this.schemas.get(identifier);
    }
}
