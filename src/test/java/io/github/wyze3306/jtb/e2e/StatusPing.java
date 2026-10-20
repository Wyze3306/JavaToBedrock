package io.github.wyze3306.jtb.e2e;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerInfoHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Prints what the Java server list shows for a proxy: {@code -Dhost -Dport}. Exits with 1 when the
 * proxy does not answer.
 */
public final class StatusPing {

    private StatusPing() {
    }

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("host", "127.0.0.1");
        int port = Integer.getInteger("port", 25565);
        CountDownLatch answered = new CountDownLatch(1);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(new MinecraftProtocol())
                .create();
        session.setFlag(MinecraftConstants.SERVER_INFO_HANDLER_KEY, (ServerInfoHandler) (s, info) -> {
            System.out.println("[status] motd: " + PlainTextComponentSerializer.plainText().serialize(info.getDescription()).replace('\n', '|'));
            if (info.getPlayerInfo() != null) {
                System.out.println("[status] players: " + info.getPlayerInfo().getOnlinePlayers() + "/" + info.getPlayerInfo().getMaxPlayers());
            }
            if (info.getVersionInfo() != null) {
                System.out.println("[status] version: " + info.getVersionInfo().getVersionName() + " (" + info.getVersionInfo().getProtocolVersion() + ")");
            }
            answered.countDown();
        });
        session.connect(true);
        boolean ok = answered.await(10, TimeUnit.SECONDS);
        session.disconnect("done");
        System.exit(ok ? 0 : 1);
    }
}
