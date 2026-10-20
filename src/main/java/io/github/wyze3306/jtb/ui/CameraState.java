package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.ResourcePackState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.world.WorldState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraFadeInstruction;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraSetInstruction;
import org.cloudburstmc.protocol.bedrock.packet.CameraInstructionPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSetCameraPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Bedrock camera instructions on Java, as far as Java allows:
 * <ul>
 *     <li>{@code set} with a free camera preset ({@code minecraft:free} or one inheriting from it):
 *     the view moves to a proxy-side invisible {@code item_display} placed at the camera position and
 *     rotation, through {@code ClientboundSetCamera}. Eased moves use the display entity's teleport
 *     interpolation (at most 59 ticks). Without a position the camera starts at the player's eyes.
 *     While the view is on another entity the vanilla Java client stops moving the player (Bedrock
 *     lets the player walk), and chunks around a far camera may not be loaded;</li>
 *     <li>{@code set} with any other preset (first person, third person, boom, orbit...) and
 *     {@code clear}: back to the player's own view (Java cannot force third person);</li>
 *     <li>{@code attach_to_entity}: the view follows that entity; {@code detach}: back to the player;</li>
 *     <li>{@code fade}: a full-screen tinted title glyph when the converted pack provides the overlay
 *     font ({@link ConversionArtifacts#fadeOverlay()}), otherwise skipped;</li>
 *     <li>target, field of view and spline instructions have no Java equivalent and are ignored.</li>
 * </ul>
 * Presets come from {@code CameraPresets}; the codec resolves instruction presets by name.
 */
public final class CameraState implements SessionState {

    public static final StateKey<CameraState> KEY = StateKey.of("ui-camera", CameraState::new);

    private static final String FREE = "minecraft:free";
    /** Display entity data: position/rotation interpolation duration in ticks (max 59). */
    private static final int TELEPORT_DURATION_INDEX = 10;
    private static final Key OVERLAY_FONT = Key.key("jtb", "overlay");

    private final ProxySession session;
    private final Map<String, CameraPreset> presets = new HashMap<>();
    private int cameraEntity = -1;
    private int cameraDimension;
    private boolean viewingOther;

    private CameraState(ProxySession session) {
        this.session = session;
    }

    public void onPresets(CameraPresetsPacket packet) {
        this.presets.clear();
        for (CameraPreset preset : packet.getCameraPresets()) {
            if (preset.getName() != null) {
                this.presets.put(preset.getName(), preset);
            }
        }
    }

    public void onInstruction(CameraInstructionPacket packet) {
        if (packet.getClear().isPresent() && packet.getClear().getAsBoolean()) {
            resetView();
        }
        if (packet.getFadeInstruction() != null) {
            fade(packet.getFadeInstruction());
        }
        if (packet.getSetInstruction() != null) {
            set(packet.getSetInstruction());
        }
        if (packet.getAttachToEntityInstruction() != null) {
            long target = packet.getAttachToEntityInstruction().getEntityActorID();
            int javaId = this.session.get(EntityTracker.KEY).javaId(target);
            if (javaId >= 0) {
                removeCameraEntity();
                this.viewingOther = true;
                this.session.sendJava(new ClientboundSetCameraPacket(javaId));
            }
        }
        if (packet.getDetachFromEntity().isPresent() && packet.getDetachFromEntity().getAsBoolean()) {
            resetView();
        }
    }

    private void set(CameraSetInstruction instruction) {
        String presetName = instruction.getPreset() == null ? null : instruction.getPreset().getIdentifier();
        if (!isFree(presetName)) {
            resetView();
            return;
        }
        CameraPreset preset = presetName == null ? null : this.presets.get(presetName);
        PlayerState player = this.session.get(PlayerState.KEY);

        Vector3f position = instruction.getPos();
        if (position == null && preset != null && !FREE.equals(presetName)) {
            position = inherited(preset, CameraPreset::getPos);
        }
        if (position == null) {
            position = player.position().add(0, PlayerState.EYE_HEIGHT, 0);
        }

        float yaw;
        float pitch;
        if (instruction.getRot() != null) {
            pitch = instruction.getRot().getX();
            yaw = instruction.getRot().getY();
        } else if (instruction.getFacing() != null) {
            Vector3f direction = instruction.getFacing().sub(position);
            double horizontal = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
            yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            pitch = (float) Math.toDegrees(-Math.atan2(direction.getY(), horizontal));
        } else {
            Float presetYaw = preset != null && !FREE.equals(presetName) ? inherited(preset, CameraPreset::getYaw) : null;
            Float presetPitch = preset != null && !FREE.equals(presetName) ? inherited(preset, CameraPreset::getPitch) : null;
            yaw = presetYaw != null ? presetYaw : player.yaw();
            pitch = presetPitch != null ? presetPitch : player.pitch();
        }

        int dimension = this.session.get(WorldState.KEY).dimension();
        if (this.cameraEntity >= 0 && this.cameraDimension == dimension) {
            CameraSetInstruction.EaseOption ease = instruction.getEase();
            int ticks = ease == null ? 0 : Math.max(0, Math.min(59, Math.round(ease.getTime() * 20f)));
            this.session.sendJava(new ClientboundSetEntityDataPacket(this.cameraEntity,
                    new EntityMetadata<?, ?>[]{new IntEntityMetadata(TELEPORT_DURATION_INDEX, MetadataTypes.INT, ticks)}));
            this.session.sendJava(new ClientboundTeleportEntityPacket(this.cameraEntity,
                    Vector3d.from(position.getX(), position.getY(), position.getZ()), Vector3d.ZERO, yaw, pitch, List.of(), false));
        } else {
            removeCameraEntity();
            this.cameraEntity = this.session.get(EntityTracker.KEY).allocateJavaId();
            this.cameraDimension = dimension;
            this.session.sendJava(new ClientboundAddEntityPacket(this.cameraEntity, UUID.randomUUID(), EntityType.ITEM_DISPLAY,
                    position.getX(), position.getY(), position.getZ(), yaw, pitch, yaw));
        }
        this.viewingOther = true;
        this.session.sendJava(new ClientboundSetCameraPacket(this.cameraEntity));
    }

    /** True for {@code minecraft:free} and presets inheriting from it. */
    private boolean isFree(@Nullable String presetName) {
        String name = presetName;
        for (int depth = 0; name != null && !name.isEmpty() && depth < 16; depth++) {
            if (FREE.equals(name)) {
                return true;
            }
            CameraPreset preset = this.presets.get(name);
            name = preset == null ? null : preset.getInheritFrom();
        }
        return false;
    }

    private <T> @Nullable T inherited(CameraPreset preset, Function<CameraPreset, @Nullable T> getter) {
        CameraPreset current = preset;
        for (int depth = 0; current != null && depth < 16; depth++) {
            T value = getter.apply(current);
            if (value != null) {
                return value;
            }
            String parent = current.getInheritFrom();
            current = parent == null || parent.isEmpty() || FREE.equals(parent) ? null : this.presets.get(parent);
        }
        return null;
    }

    private void fade(CameraFadeInstruction fade) {
        ConversionArtifacts artifacts = this.session.get(ResourcePackState.KEY).artifacts();
        if (artifacts == null || !artifacts.fadeOverlay()) {
            this.session.logger().debug("[{}] Camera fade skipped: no overlay glyph in the resource pack", this.session.getName());
            return;
        }
        CameraFadeInstruction.TimeOption time = fade.getTime();
        CameraFadeInstruction.ColorOption color = fade.getColor();
        int fadeIn = time == null ? 10 : seconds(time.getFadeInTime());
        int hold = time == null ? 20 : seconds(time.getHoldTime());
        int fadeOut = time == null ? 10 : seconds(time.getFadeOutTime());
        int rgb = color == null ? 0 : channel(color.getRed()) << 16 | channel(color.getGreen()) << 8 | channel(color.getBlue());
        this.session.sendJava(new ClientboundSetTitlesAnimationPacket(fadeIn, hold, fadeOut));
        this.session.sendJava(new ClientboundSetTitleTextPacket(Component.text("").font(OVERLAY_FONT).color(TextColor.color(rgb))));
    }

    private static int seconds(float seconds) {
        return Float.isFinite(seconds) ? Math.max(0, Math.round(seconds * 20f)) : 0;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    /** Back to the player's own view. */
    private void resetView() {
        removeCameraEntity();
        if (this.viewingOther) {
            this.viewingOther = false;
            this.session.sendJava(new ClientboundSetCameraPacket(this.session.get(PlayerState.KEY).javaEntityId()));
        }
    }

    private void removeCameraEntity() {
        if (this.cameraEntity >= 0) {
            this.session.sendJava(new ClientboundRemoveEntitiesPacket(new int[]{this.cameraEntity}));
            this.cameraEntity = -1;
        }
    }
}
