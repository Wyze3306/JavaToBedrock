package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two Java players on the same Bedrock server through the proxy (run another client named
 * {@code Peer...} alongside, e.g. with {@code e2e-load.sh}): each sees the other in the tab list, as a
 * player entity whose UUID is its tab list entry, and reads its chat.
 */
public final class PeersScenario implements E2eScenario {

    private final Set<UUID> peerProfiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> playerEntities = ConcurrentHashMap.newKeySet();

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
            for (PlayerListEntry entry : info.getEntries()) {
                if (entry.getProfile() != null && entry.getProfile().getName().startsWith("Load")) {
                    this.peerProfiles.add(entry.getProfileId());
                }
            }
        } else if (packet instanceof ClientboundAddEntityPacket add && add.getType() == EntityType.PLAYER) {
            this.playerEntities.add(add.getUuid());
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.check("peer in the tab list", !this.peerProfiles.isEmpty());
        context.check("peer spawned as a player entity with its profile UUID",
                this.peerProfiles.stream().anyMatch(this.playerEntities::contains));
        context.check("peer's chat received", context.chat().stream().anyMatch(line -> line.contains("Load1") && line.contains("Bonjour")));
    }
}
