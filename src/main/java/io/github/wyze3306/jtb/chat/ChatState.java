package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.text.BedrockText;
import io.github.wyze3306.jtb.text.TextTranslator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.data.CurrentCmdVersion;
import org.cloudburstmc.protocol.bedrock.data.TextPacketType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.payload.text.AuthorAndMessage;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSoftEnumPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Chat and commands of one session: the Java player's messages and commands on their way to the
 * Bedrock server (rate limited like the server expects), and the command tree shown to the Java
 * client (built from {@code AvailableCommandsPacket}, kept in sync with soft enum updates and the
 * commands-enabled switch).
 */
public final class ChatState implements SessionState, CommandTree.SuggestionData {

    public static final StateKey<ChatState> KEY = StateKey.of("chat", ChatState::new);

    /*
     * PowerNukkitX silently drops chat above 2 messages per second and kicks above 10 commands per
     * second (Guava RateLimiter, which stores one second of permits). It counts when its main thread
     * handles the packet, not when it arrives: a lagging server handles everything received during a
     * long tick at once, so the pace leaves room for a stall of a couple of seconds.
     */
    /** Chat: two at once after a pause, then one per second. */
    private static final int CHAT_BURST = 2;
    private static final long CHAT_INTERVAL = TimeUnit.MILLISECONDS.toNanos(1000);
    /** Commands: five at once after a pause, then four per second. */
    private static final int COMMAND_BURST = 5;
    private static final long COMMAND_INTERVAL = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_QUEUED = 64;

    private final ProxySession session;
    private final ThrottledQueue<BedrockPacket> chat;
    private final ThrottledQueue<BedrockPacket> commands;

    private List<CommandData> commandData = List.of();
    /** Commands and aliases by lower-case name, including those hidden from the tree. */
    private final Map<String, CommandData> commandsByName = new HashMap<>();
    private final Map<String, Set<String>> softEnums = new HashMap<>();
    private boolean commandsEnabled = true;
    private @Nullable CommandTree tree;

    private ChatState(ProxySession session) {
        this.session = session;
        long now = System.nanoTime();
        this.chat = new ThrottledQueue<>(CHAT_BURST, CHAT_INTERVAL, MAX_QUEUED, now);
        this.commands = new ThrottledQueue<>(COMMAND_BURST, COMMAND_INTERVAL, MAX_QUEUED, now);
    }

    // ------------------------------------------------------------------ Java -> Bedrock

    /**
     * A chat message typed by the Java player: Bedrock {@code CHAT} text with our name and XUID (the
     * server broadcasts it back, so nothing is echoed locally).
     */
    public void sendChat(String message) {
        if (message.isBlank()) {
            return;
        }
        AuthorAndMessage body = new AuthorAndMessage();
        body.setPlayerName(this.session.getBedrockName());
        body.setMessage(message);
        TextPacket packet = new TextPacket();
        packet.setMessageType(TextPacketType.CHAT);
        packet.setLocalize(false);
        packet.setBody(body);
        packet.setSendersXUID(this.session.getXuid());
        packet.setPlatformId("");
        packet.setFilteredMessage("");
        enqueue(this.chat, packet);
    }

    /**
     * A command typed by the Java player, without its leading {@code /}. Proxy commands are run
     * locally; with commands disabled (cheats off) only non-cheat commands reach the server, like
     * from a Bedrock client.
     */
    public void sendCommand(String command) {
        String trimmed = command.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        int space = trimmed.indexOf(' ');
        String name = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        if (name.equals(ProxyCommands.NAME) && !this.commandsByName.containsKey(name)) {
            ProxyCommands.execute(this.session, space < 0 ? "" : trimmed.substring(space + 1));
            return;
        }
        if (!this.commandsEnabled) {
            CommandData data = this.commandsByName.get(name);
            if (data == null || !data.getFlags().contains(CommandData.Flag.NOT_CHEAT)) {
                sendLocal(TextTranslator.translate(this.session, "§e%commands.generic.disabled"));
                return;
            }
        }
        CommandRequestPacket request = new CommandRequestPacket();
        request.setCommand("/" + trimmed);
        request.setCommandOrigin(new CommandOriginData(CommandOriginType.PLAYER, UUID.randomUUID(), "", 0L));
        request.setInternal(false);
        request.setVersion(CurrentCmdVersion.LATEST);
        enqueue(this.commands, request);
    }

    private void enqueue(ThrottledQueue<BedrockPacket> queue, BedrockPacket packet) {
        if (!queue.offer(packet)) {
            sendLocal(Component.text("Trop de messages en attente, celui-ci n'a pas été envoyé.", NamedTextColor.RED));
            return;
        }
        flush();
    }

    @Override
    public void tick() {
        if (this.chat.size() > 0 || this.commands.size() > 0) {
            flush();
        }
    }

    /** The server ignores chat and commands until the player has spawned: they wait until then. */
    private void flush() {
        if (!this.session.get(PlayerState.KEY).isSpawned()) {
            return;
        }
        long now = System.nanoTime();
        this.chat.drain(now, this.session::sendBedrock);
        this.commands.drain(now, this.session::sendBedrock);
    }

    /** A message from the proxy itself to the Java player. */
    void sendLocal(Component message) {
        this.session.sendJava(new ClientboundSystemChatPacket(message, false));
    }

    // ------------------------------------------------------------------ Bedrock command data

    void onAvailableCommands(AvailableCommandsPacket packet) {
        this.commandData = List.copyOf(packet.getCommands());
        this.commandsByName.clear();
        for (CommandData command : this.commandData) {
            this.commandsByName.putIfAbsent(command.getName().toLowerCase(Locale.ROOT), command);
            if (command.getAliases() != null) {
                for (String alias : command.getAliases().getValues().keySet()) {
                    this.commandsByName.putIfAbsent(alias.toLowerCase(Locale.ROOT), command);
                }
            }
            for (CommandOverloadData overload : command.getOverloads()) {
                for (CommandParamData param : overload.getOverloads()) {
                    CommandEnumData enumData = param.getEnumData();
                    if (enumData != null && enumData.isSoft()) {
                        this.softEnums.put(enumData.getName(), new LinkedHashSet<>(enumData.getValues().keySet()));
                    }
                }
            }
        }
        // The Bedrock client starts loading its language with the world; so does the proxy.
        TextTranslator.lang(this.session);
        sendTree();
    }

    void onSoftEnum(UpdateSoftEnumPacket packet) {
        CommandEnumData softEnum = packet.getSoftEnum();
        if (softEnum == null || packet.getUpdateType() == null) {
            return;
        }
        Set<String> values = this.softEnums.computeIfAbsent(softEnum.getName(), name -> new LinkedHashSet<>());
        Collection<String> changed = softEnum.getValues().keySet();
        switch (packet.getUpdateType()) {
            case ADD -> values.addAll(changed);
            case REMOVE -> values.removeAll(changed);
            case REPLACE -> {
                values.clear();
                values.addAll(changed);
            }
        }
    }

    void onCommandsEnabled(boolean enabled) {
        if (enabled == this.commandsEnabled) {
            return;
        }
        this.commandsEnabled = enabled;
        if (this.tree != null) {
            sendTree();
        }
    }

    private void sendTree() {
        CommandTreeBuilder builder = new CommandTreeBuilder(this.commandData).addAll(this.commandData, this.commandsEnabled);
        builder.addHelpIfMissing();
        if (!this.commandsByName.containsKey(ProxyCommands.NAME)) {
            builder.addTopLevel(ProxyCommands.node(this.session));
        }
        CommandTree built = builder.build();
        this.tree = built;
        this.session.sendJava(built.toPacket());
    }

    // ------------------------------------------------------------------ suggestions

    /** Answers a Java {@code ask_server} suggestion request ({@code text} includes the leading {@code /}). */
    void suggest(int transactionId, String text) {
        CommandTree current = this.tree;
        List<String> matches;
        int start = text.length();
        int length = 0;
        if (current == null) {
            matches = List.of();
        } else {
            CommandTree.Completion completion = current.complete(text, this);
            matches = completion.matches();
            start = completion.start();
            length = completion.length();
        }
        this.session.sendJava(new ClientboundCommandSuggestionsPacket(transactionId, start, length,
                matches.toArray(String[]::new), new Component[matches.size()]));
    }

    @Override
    public Collection<String> softEnum(String name) {
        Set<String> values = this.softEnums.get(name);
        return values == null ? List.of() : values;
    }

    @Override
    public Collection<String> playerNames() {
        Collection<String> others = this.session.get(EntityTracker.KEY).playerNames();
        List<String> names = new ArrayList<>(others.size() + 1);
        names.add(BedrockText.stripFormatting(this.session.getBedrockName()));
        for (String name : others) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public Collection<String> itemIdentifiers() {
        return this.session.get(ItemRegistry.KEY).definitions().keySet();
    }

    /** Blocks are the items with a block runtime id: below 256 (negative for the newer blocks). */
    @Override
    public Collection<String> blockIdentifiers() {
        List<String> blocks = new ArrayList<>();
        for (ItemDefinition definition : this.session.get(ItemRegistry.KEY).definitions().values()) {
            if (definition.getRuntimeId() < 256) {
                blocks.add(definition.getIdentifier());
            }
        }
        return blocks;
    }

    @Override
    public void close() {
        this.chat.clear();
        this.commands.clear();
    }
}
