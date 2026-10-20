package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorLink;
import org.cloudburstmc.protocol.bedrock.data.actor.PropertySyncData;
import org.cloudburstmc.protocol.bedrock.packet.AvailableActorIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeMobPropertyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncActorPropertyPacket;

/**
 * Entity data and bookkeeping packets: {@code SetActorData}, {@code SetActorLink}, {@code PlayerList},
 * {@code SyncActorProperty}, {@code AvailableActorIdentifiers} and {@code ChangeMobProperty}.
 */
final class DataTranslators {

    private DataTranslators() {
    }

    /**
     * Entity data. For the local player, {@link PlayerState#onLocalActorData} gets the map for the
     * player-specific state; the generic entity data (fire, invisibility, air, freezing, scale) is
     * translated here like for any entity, as vanilla servers also send a player its own entity data.
     */
    static void setActorData(ProxySession session, SetActorDataPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        if (entity == null) {
            return;
        }
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            session.get(PlayerState.KEY).onLocalActorData(packet.getActorData());
        }
        MetadataWriter writer = tracker.metadata().begin(entity);
        ActorDataTranslator.translate(session, tracker, entity, packet.getActorData(), writer);
        PropertySyncData properties = packet.getSyncedProperties();
        if (properties != null && !tracker.properties().isEmpty(properties)) {
            tracker.properties().apply(session, tracker, entity, properties, writer);
        }
        writer.flush(session);
    }

    static void setActorLink(ProxySession session, SetActorLinkPacket packet) {
        ActorLink link = packet.getLink();
        if (link != null) {
            session.get(EntityTracker.KEY).link(link.getTargetA(), link.getTargetB(), link.getType());
        }
    }

    static void playerList(ProxySession session, PlayerListPacket packet) {
        session.get(EntityTracker.KEY).playerList().onPlayerList(packet);
    }

    static void syncActorProperty(ProxySession session, SyncActorPropertyPacket packet) {
        NbtMap data = packet.getPropertyData();
        if (data != null) {
            session.get(EntityTracker.KEY).properties().onSync(data);
        }
    }

    /** {@code {idlist: [{id: "minecraft:zombie", ...}]}}: every entity type the server may spawn. */
    static void availableActorIdentifiers(ProxySession session, AvailableActorIdentifiersPacket packet) {
        NbtMap list = packet.getActorInfoList();
        if (list == null) {
            return;
        }
        var identifiers = session.get(EntityTracker.KEY).availableIdentifiers();
        for (NbtMap entry : list.getList("idlist", NbtType.COMPOUND)) {
            String id = entry.getString("id", "");
            if (!id.isEmpty()) {
                identifiers.add(id);
            }
        }
    }

    /** Education-edition style component change on a mob; nothing Java can show. */
    static void changeMobProperty(ProxySession session, ChangeMobPropertyPacket packet) {
        session.logger().debug("[{}] ChangeMobProperty {} on {}", session.getName(), packet.getPropertyName(), packet.getActorId());
    }
}
