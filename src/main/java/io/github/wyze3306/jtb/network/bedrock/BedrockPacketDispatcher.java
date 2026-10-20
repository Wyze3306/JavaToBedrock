package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockDisconnects;
import io.netty.util.ReferenceCountUtil;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.net.InetSocketAddress;

/**
 * Receives every packet of a Bedrock session on its Netty thread and hands it to the session event
 * loop: first to the login sequence, then to the translators.
 * <p>
 * Reference counted packets (e.g. resource pack chunks) are retained across the thread hop and
 * released once handled.
 */
final class BedrockPacketDispatcher implements BedrockPacketHandler {

    private final ProxySession session;
    private final BedrockSession bedrock;
    private final BedrockLoginHandler login;

    BedrockPacketDispatcher(ProxySession session, BedrockSession bedrock, BedrockLoginHandler login) {
        this.session = session;
        this.bedrock = bedrock;
        this.login = login;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        // Must happen before the next packet of the batch is decoded, so on this (Netty) thread.
        DefinitionRegistry<ItemDefinition> items = CodecRegistries.onDecoded(this.bedrock, packet);
        if (items != null && packet instanceof ItemRegistryPacket registry) {
            this.session.execute(() -> this.session.get(ItemRegistry.KEY).load(registry.getItemData(), items));
        }
        ReferenceCountUtil.retain(packet);
        this.session.execute(() -> {
            try {
                dispatch(packet);
            } finally {
                ReferenceCountUtil.release(packet);
            }
        });
        return PacketSignal.HANDLED;
    }

    private void dispatch(BedrockPacket packet) {
        if (this.session.bedrockSession() != this.bedrock) {
            // A packet from the server the player just left.
            return;
        }
        if (packet instanceof DisconnectPacket disconnect) {
            Component reason = BedrockDisconnects.message(this.session, disconnect);
            BedrockReconnect reconnect = this.session.get(BedrockReconnect.KEY);
            if (!reconnect.isShutdown(disconnect) || !reconnect.onConnectionLost(reason)) {
                this.session.disconnect(reason);
            }
            return;
        }
        if (packet instanceof TransferPacket transfer) {
            onTransfer(transfer);
            return;
        }
        if (this.login.handle(packet)) {
            return;
        }
        var proxy = this.session.proxy();
        if (!proxy.translators().translateBedrock(this.session, packet) && proxy.config().debug().logUnhandledBedrock()) {
            this.session.logger().info("[{}] Unhandled Bedrock packet {}", this.session.getName(), packet.getPacketType());
        }
    }

    private void onTransfer(TransferPacket transfer) {
        if (!this.session.config().bedrock().followTransfers()) {
            this.session.disconnect(Component.text("Le serveur Bedrock vous transfère vers " + transfer.getServerAddress()
                    + ":" + transfer.getServerPort() + " (transferts désactivés sur ce proxy)"));
            return;
        }
        InetSocketAddress target = new InetSocketAddress(transfer.getServerAddress(), transfer.getServerPort());
        this.session.switchServer(target);
    }

    @Override
    public void onDisconnect(String reason) {
        this.session.execute(() -> {
            if (this.session.bedrockSession() != this.bedrock) {
                return;
            }
            if (!this.session.isClosed()) {
                Component message = BedrockDisconnects.message(this.session, reason);
                if (!this.session.get(BedrockReconnect.KEY).onConnectionLost(message)) {
                    this.session.disconnect(message);
                }
            }
        });
    }
}
