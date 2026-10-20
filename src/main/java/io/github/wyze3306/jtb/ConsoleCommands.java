package io.github.wyze3306.jtb;

import io.github.wyze3306.jtb.network.bedrock.BedrockPinger;
import io.github.wyze3306.jtb.network.bedrock.BedrockReconnect;
import io.github.wyze3306.jtb.network.bedrock.BedrockServers;
import io.github.wyze3306.jtb.network.java.JavaKeepAlive;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The proxy console: {@code list}, {@code servers}, {@code send <player|*> <server>}, {@code stop}.
 * Per-session details are read on each session's event loop.
 */
final class ConsoleCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleCommands.class);

    private final JavaToBedrock proxy;

    ConsoleCommands(JavaToBedrock proxy) {
        this.proxy = proxy;
    }

    /** Runs one console line; returns false for {@code stop}. */
    boolean run(String line) {
        String[] args = line.trim().split("\\s+");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "" -> {
            }
            case "stop", "end", "exit" -> {
                return false;
            }
            case "list" -> list();
            case "servers" -> servers();
            case "send" -> send(args);
            default -> LOGGER.info("Commands: list, servers, send <player|*> <server>, stop");
        }
        return true;
    }

    private void list() {
        List<CompletableFuture<String>> lines = new ArrayList<>();
        for (ProxySession session : this.proxy.sessions().all()) {
            lines.add(CompletableFuture.supplyAsync(() -> describe(session), session.eventLoop())
                    .completeOnTimeout(session.getName() + " (busy)", 1, TimeUnit.SECONDS));
        }
        LOGGER.info("{} player(s){}", lines.size(), lines.isEmpty() ? "" : ":");
        for (CompletableFuture<String> line : lines) {
            LOGGER.info("  {}", line.join());
        }
    }

    private String describe(ProxySession session) {
        BedrockReconnect reconnect = session.get(BedrockReconnect.KEY);
        InetSocketAddress server = reconnect.server();
        String where = server == null ? "-" : server.getHostString() + ":" + server.getPort();
        String state = reconnect.isWaiting() ? "waiting room" : session.javaPhase().name().toLowerCase(Locale.ROOT);
        long bedrockPing = session.bedrockPingMillis();
        return session.getName() + " (Bedrock: " + session.getBedrockName() + ") on " + where + ", " + state
                + ", ping Java " + session.get(JavaKeepAlive.KEY).latencyMs() + " ms / Bedrock "
                + (bedrockPing < 0 ? "?" : bedrockPing + " ms");
    }

    private void servers() {
        BedrockServers servers = this.proxy.bedrockConnector().servers();
        for (BedrockServers.Target target : servers.all()) {
            BedrockPinger.Status status = this.proxy.pinger().status(servers.address(target));
            LOGGER.info("  {} {}:{} {}", target.name(), target.host(), target.port(), status == null ? "offline"
                    : "online, \"" + BedrockText.stripFormatting(status.motd()) + "\" " + status.version()
                    + ", " + status.online() + "/" + status.max() + " players");
        }
    }

    private void send(String[] args) {
        if (args.length != 3) {
            LOGGER.info("Usage: send <player|*> <server>");
            return;
        }
        BedrockServers.Target target = this.proxy.bedrockConnector().servers().byName(args[2]);
        if (target == null) {
            LOGGER.info("Unknown server {}; servers: {}", args[2],
                    this.proxy.bedrockConnector().servers().all().stream().map(BedrockServers.Target::name).toList());
            return;
        }
        List<ProxySession> players = new ArrayList<>();
        if (args[1].equals("*")) {
            players.addAll(this.proxy.sessions().all());
        } else {
            ProxySession session = this.proxy.sessions().getByName(args[1]);
            if (session == null) {
                LOGGER.info("No player named {}", args[1]);
                return;
            }
            players.add(session);
        }
        for (ProxySession session : players) {
            session.execute(() -> session.switchServer(target.host(), target.port()));
        }
        LOGGER.info("Sending {} player(s) to {}", players.size(), target.name());
    }
}
