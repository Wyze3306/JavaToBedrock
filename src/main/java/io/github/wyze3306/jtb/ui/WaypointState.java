package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.world.WorldState;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.WorldPosition;
import org.cloudburstmc.protocol.bedrock.data.payload.locatorbar.LocatorBarWaypointPayload;
import org.cloudburstmc.protocol.bedrock.data.payload.locatorbar.ServerWaypointGroupAction;
import org.cloudburstmc.protocol.bedrock.data.payload.locatorbar.ServerWaypointPayload;
import org.cloudburstmc.protocol.bedrock.data.payload.locatorbar.VanillaWaypointManagerConstants.UpdateFlag;
import org.cloudburstmc.protocol.bedrock.data.payload.location.CoordinatesLocation;
import org.cloudburstmc.protocol.bedrock.packet.LocatorBarPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerLocationPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.Vec3iWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.WaypointOperation;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Bedrock locator bar on the Java 26.3 locator bar (tracked waypoints):
 * <ul>
 *     <li>{@code LocatorBar}: server waypoint groups, keyed by their group handle; the payload's
 *     update flags say which fields an update carries. A waypoint is shown at its world position, in
 *     its colour, while visible and in the player's dimension;</li>
 *     <li>{@code PlayerLocation}: where another player is ({@code COORDINATES}) or that they are hidden.</li>
 * </ul>
 * Bedrock icon textures have no Java equivalent: every waypoint uses the default style.
 */
public final class WaypointState implements SessionState {

    public static final StateKey<WaypointState> KEY = StateKey.of("ui-waypoints", WaypointState::new);

    private static final Key STYLE = Key.key("minecraft:default");
    private static final long PLAYER_UUID_HIGH = 0x6A74625F706C6F63L; // "jtb_ploc"

    private final ProxySession session;
    private final Map<UUID, Waypoint> waypoints = new HashMap<>();

    private static final class Waypoint {
        @Nullable Vector3f position;
        int dimension;
        boolean visible = true;
        @Nullable Integer color;
        boolean shown;
    }

    private WaypointState(ProxySession session) {
        this.session = session;
    }

    public void onLocatorBar(LocatorBarPacket packet) {
        for (LocatorBarWaypointPayload payload : packet.getWaypoints()) {
            UUID handle = payload.getGroupHandle();
            if (handle == null || payload.getActionFlag() == null) {
                continue;
            }
            switch (payload.getActionFlag()) {
                case REMOVE -> remove(handle);
                case ADD, UPDATE -> {
                    Waypoint waypoint = this.waypoints.computeIfAbsent(handle, k -> new Waypoint());
                    merge(waypoint, payload.getServerWaypointPayload(), payload.getActionFlag() == ServerWaypointGroupAction.ADD);
                    publish(handle, waypoint);
                }
                case NONE -> {
                }
            }
        }
    }

    public void onPlayerLocation(PlayerLocationPacket packet) {
        UUID id = new UUID(PLAYER_UUID_HIGH, packet.getTargetActorID());
        if (packet.getLocation() instanceof CoordinatesLocation coordinates && coordinates.getPosition() != null) {
            Waypoint waypoint = this.waypoints.computeIfAbsent(id, k -> new Waypoint());
            waypoint.position = coordinates.getPosition();
            waypoint.dimension = this.session.get(WorldState.KEY).dimension();
            waypoint.visible = true;
            publish(id, waypoint);
        } else {
            remove(id);
        }
    }

    private static void merge(Waypoint waypoint, @Nullable ServerWaypointPayload payload, boolean full) {
        if (payload == null) {
            return;
        }
        boolean all = full || payload.getUpdateFlags().isEmpty();
        if ((all || payload.getUpdateFlags().contains(UpdateFlag.WORLD_POS)) && payload.getWorldPosition() != null) {
            WorldPosition position = payload.getWorldPosition();
            waypoint.position = position.getPosition();
            waypoint.dimension = position.getDimensionType() == null ? 0 : position.getDimensionType().getValue();
        }
        if ((all || payload.getUpdateFlags().contains(UpdateFlag.VISIBILITY)) && payload.getIsVisible().isPresent()) {
            waypoint.visible = payload.getIsVisible().getAsBoolean();
        }
        if ((all || payload.getUpdateFlags().contains(UpdateFlag.COLOR)) && payload.getColor() != null) {
            waypoint.color = payload.getColor();
        }
    }

    private void publish(UUID id, Waypoint waypoint) {
        boolean show = waypoint.visible && waypoint.position != null
                && waypoint.dimension == this.session.get(WorldState.KEY).dimension();
        if (!show) {
            if (waypoint.shown) {
                waypoint.shown = false;
                this.session.sendJava(new ClientboundTrackedWaypointPacket(WaypointOperation.UNTRACK, empty(id)));
            }
            return;
        }
        Vector3f position = waypoint.position;
        TrackedWaypoint tracked = new TrackedWaypoint(id, null,
                new TrackedWaypoint.Icon(STYLE, Optional.ofNullable(waypoint.color).map(color -> color & 0xFFFFFF)),
                TrackedWaypoint.Type.VEC3I,
                new Vec3iWaypointData(Vector3i.from(position.getFloorX(), position.getFloorY(), position.getFloorZ())));
        this.session.sendJava(new ClientboundTrackedWaypointPacket(waypoint.shown ? WaypointOperation.UPDATE : WaypointOperation.TRACK, tracked));
        waypoint.shown = true;
    }

    private void remove(UUID id) {
        Waypoint waypoint = this.waypoints.remove(id);
        if (waypoint != null && waypoint.shown) {
            this.session.sendJava(new ClientboundTrackedWaypointPacket(WaypointOperation.UNTRACK, empty(id)));
        }
    }

    private static TrackedWaypoint empty(UUID id) {
        return new TrackedWaypoint(id, null, new TrackedWaypoint.Icon(STYLE, Optional.empty()), TrackedWaypoint.Type.EMPTY, null);
    }
}
