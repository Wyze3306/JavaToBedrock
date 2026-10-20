package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stand-in for custom entities when no converted model is available: an {@code interaction} entity
 * the size of the Bedrock hit box (so Java players can hit and use it, with the hit box outline shown
 * when aimed at), a translucent box ({@code item_display} of a stained glass block scaled to the hit
 * box) and a label ({@code text_display}) with the entity's name tag or, without one, its identifier.
 * <p>
 * Subclasses can draw a real model by overriding {@link #modelItem} (e.g. a paper item whose
 * {@code item_model} points to a converted model of the resource pack).
 */
public class DefaultCustomEntityRenderer implements CustomEntityRenderer {

    public static final DefaultCustomEntityRenderer INSTANCE = new DefaultCustomEntityRenderer();

    private static final float MIN_SIZE = 0.2f;
    private static final String PLACEHOLDER_ITEM = "minecraft:light_gray_stained_glass";
    private static final int TEXT_DISPLAY_BILLBOARD_CENTER = 3;
    private static final int ITEM_DISPLAY_CONTEXT_NONE = 0;

    /** What was spawned for one entity. */
    protected record State(int modelId, int labelId, float width, float height, Component label) {
    }

    @Override
    public void spawn(ProxySession session, EntityTracker tracker, TrackedEntity entity) {
        float width = width(entity);
        float height = height(entity);
        Component label = label(session, entity);
        int modelId = tracker.addPart(entity);
        int labelId = tracker.addPart(entity);
        session.sendJava(new ClientboundAddEntityPacket(entity.javaId(), entity.uuid(), EntityType.INTERACTION,
                entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch(), entity.headYaw()));
        session.sendJava(new ClientboundSetEntityDataPacket(entity.javaId(), interactionData(entity, width, height)));
        session.sendJava(new ClientboundAddEntityPacket(modelId, partUuid(entity, 1), EntityType.ITEM_DISPLAY,
                entity.x(), entity.y(), entity.z(), entity.yaw(), 0, entity.yaw()));
        session.sendJava(new ClientboundSetEntityDataPacket(modelId, modelData(session, entity, width, height)));
        session.sendJava(new ClientboundAddEntityPacket(labelId, partUuid(entity, 2), EntityType.TEXT_DISPLAY,
                entity.x(), entity.y(), entity.z(), 0, 0, 0));
        session.sendJava(new ClientboundSetEntityDataPacket(labelId, labelData(entity, height, label)));
        entity.setRendererState(new State(modelId, labelId, width, height, label));
    }

    @Override
    public void update(ProxySession session, EntityTracker tracker, TrackedEntity entity) {
        if (!(entity.rendererState() instanceof State state)) {
            return;
        }
        float width = width(entity);
        float height = height(entity);
        Component label = label(session, entity);
        if (width != state.width() || height != state.height()) {
            session.sendJava(new ClientboundSetEntityDataPacket(entity.javaId(), interactionData(entity, width, height)));
            session.sendJava(new ClientboundSetEntityDataPacket(state.modelId(), modelData(session, entity, width, height)));
        }
        if (width != state.width() || height != state.height() || !label.equals(state.label())) {
            session.sendJava(new ClientboundSetEntityDataPacket(state.labelId(), labelData(entity, height, label)));
        }
        entity.setRendererState(new State(state.modelId(), state.labelId(), width, height, label));
    }

    /**
     * The item shown as the entity's body, scaled to its hit box; null for none. The default is a
     * translucent block marking the hit box.
     */
    protected @Nullable ItemStack modelItem(ProxySession session, TrackedEntity entity) {
        return EntityItems.javaItem(session, PLACEHOLDER_ITEM, null);
    }

    /** Hit box width in blocks (Bedrock size times scale). */
    protected static float width(TrackedEntity entity) {
        return Math.max(MIN_SIZE, entity.bedrockWidth() * Math.max(entity.scale(), 0));
    }

    protected static float height(TrackedEntity entity) {
        return Math.max(MIN_SIZE, entity.bedrockHeight() * Math.max(entity.scale(), 0));
    }

    /** The Bedrock name tag, or the identifier made readable ({@code mypack:fire_golem} -> "Fire Golem"). */
    protected static Component label(ProxySession session, TrackedEntity entity) {
        if (!entity.nameTag().isEmpty()) {
            return ActorDataTranslator.nameComponent(session, entity);
        }
        String id = entity.identifier();
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder words = new StringBuilder();
        for (String word : path.split("[_/.]")) {
            if (!word.isEmpty()) {
                if (!words.isEmpty()) {
                    words.append(' ');
                }
                words.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return Component.text(words.toString(), NamedTextColor.GRAY);
    }

    private static UUID partUuid(TrackedEntity entity, int part) {
        return new UUID(entity.uuid().getMostSignificantBits() ^ part, entity.uuid().getLeastSignificantBits());
    }

    private static EntityMetadata<?, ?>[] interactionData(TrackedEntity entity, float width, float height) {
        JavaEntityDefinition interaction = EntityDefinitions.forJava(EntityType.INTERACTION);
        return new EntityMetadata<?, ?>[]{
                new FloatEntityMetadata(interaction.index(MetaField.WIDTH), MetadataTypes.FLOAT, width),
                new FloatEntityMetadata(interaction.index(MetaField.HEIGHT), MetadataTypes.FLOAT, height),
                new BooleanEntityMetadata(interaction.index(MetaField.RESPONSE), MetadataTypes.BOOLEAN, true)};
    }

    private EntityMetadata<?, ?>[] modelData(ProxySession session, TrackedEntity entity, float width, float height) {
        JavaEntityDefinition display = EntityDefinitions.forJava(EntityType.ITEM_DISPLAY);
        List<EntityMetadata<?, ?>> data = new ArrayList<>(5);
        ItemStack item = modelItem(session, entity);
        if (item != null) {
            data.add(new ObjectEntityMetadata<>(display.index(MetaField.ITEM_STACK), MetadataTypes.ITEM_STACK, item));
        }
        data.add(new ByteEntityMetadata(display.index(MetaField.ITEM_DISPLAY), MetadataTypes.BYTE, (byte) ITEM_DISPLAY_CONTEXT_NONE));
        data.add(new ObjectEntityMetadata<>(display.index(MetaField.SCALE), MetadataTypes.VECTOR3, Vector3f.from(width, height, width)));
        // An item display is drawn centred on its position: lift it so the box stands on the ground.
        data.add(new ObjectEntityMetadata<>(display.index(MetaField.TRANSLATION), MetadataTypes.VECTOR3, Vector3f.from(0, height / 2, 0)));
        data.add(new IntEntityMetadata(display.index(MetaField.POS_ROT_INTERPOLATION_DURATION), MetadataTypes.INT, 3));
        return data.toArray(new EntityMetadata<?, ?>[0]);
    }

    private static EntityMetadata<?, ?>[] labelData(TrackedEntity entity, float height, Component label) {
        JavaEntityDefinition display = EntityDefinitions.forJava(EntityType.TEXT_DISPLAY);
        return new EntityMetadata<?, ?>[]{
                new ObjectEntityMetadata<>(display.index(MetaField.TEXT), MetadataTypes.COMPONENT, label),
                new ByteEntityMetadata(display.index(MetaField.BILLBOARD_RENDER_CONSTRAINTS), MetadataTypes.BYTE,
                        (byte) TEXT_DISPLAY_BILLBOARD_CENTER),
                new ObjectEntityMetadata<>(display.index(MetaField.TRANSLATION), MetadataTypes.VECTOR3, Vector3f.from(0, height + 0.3f, 0)),
                new IntEntityMetadata(display.index(MetaField.POS_ROT_INTERPOLATION_DURATION), MetadataTypes.INT, 3)};
    }
}
