package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockText;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.CollisionRule;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.NameTagVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamColor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;

import java.util.Objects;

/**
 * Name tags of other players. Java draws a player's tag from its profile name, decorated by its team
 * (prefix, colour, suffix); a custom name has no effect on players. When the Bedrock name tag differs
 * from the profile name (formatting, rank prefixes, names longer than 16 characters, hidden tags), the
 * player is put alone in a team {@code jtb.n<javaId>} whose prefix and suffix are the parts of the
 * Bedrock tag around the name. Players whose tag is exactly their name get no team.
 */
final class PlayerNametags {

    record Team(String prefix, String suffix, TeamColor color, boolean visible) {
    }

    private final ProxySession session;
    private final Int2ObjectMap<Team> teams = new Int2ObjectOpenHashMap<>();

    PlayerNametags(ProxySession session) {
        this.session = session;
    }

    void update(TrackedEntity player) {
        PlayerListState.Entry entry = player.playerEntry;
        if (entry == null) {
            return;
        }
        Team wanted = wanted(player.nameTag, entry.profileName, isHidden(player));
        Team current = this.teams.get(player.javaId());
        if (Objects.equals(wanted, current)) {
            return;
        }
        String name = teamName(player);
        if (wanted == null) {
            this.teams.remove(player.javaId());
            this.session.sendJava(new ClientboundSetPlayerTeamPacket(name));
            return;
        }
        Component prefix = BedrockText.legacyToComponent(wanted.prefix());
        Component suffix = BedrockText.legacyToComponent(wanted.suffix());
        NameTagVisibility visibility = wanted.visible() ? NameTagVisibility.ALWAYS : NameTagVisibility.NEVER;
        if (current == null) {
            this.session.sendJava(new ClientboundSetPlayerTeamPacket(name, Component.text(name), prefix, suffix, true, false,
                    visibility, CollisionRule.ALWAYS, wanted.color(), new String[]{entry.profileName}));
        } else {
            this.session.sendJava(new ClientboundSetPlayerTeamPacket(name, Component.text(name), prefix, suffix, true, false,
                    visibility, CollisionRule.ALWAYS, wanted.color()));
        }
        this.teams.put(player.javaId(), wanted);
    }

    void remove(TrackedEntity player) {
        if (this.teams.remove(player.javaId()) != null) {
            this.session.sendJava(new ClientboundSetPlayerTeamPacket(teamName(player)));
        }
    }

    void clear() {
        this.teams.clear();
    }

    private static boolean isHidden(TrackedEntity player) {
        return player.flags.contains(ActorFlags.INVISIBLE) && !player.flags.contains(ActorFlags.ALWAYS_SHOW_NAME);
    }

    private static String teamName(TrackedEntity player) {
        return "jtb.n" + player.javaId();
    }

    /**
     * The team that makes Java draw {@code tag} for a player whose profile name is {@code profileName};
     * null when the plain profile name already looks right.
     */
    static Team wanted(String tag, String profileName, boolean hidden) {
        String flat = tag.replace('\n', ' ');
        if (hidden || flat.isBlank()) {
            return new Team("", "", TeamColor.WHITE, false);
        }
        String plain = BedrockText.stripFormatting(flat);
        if (plain.equals(profileName) && flat.indexOf(BedrockText.SECTION) < 0) {
            return null;
        }
        int start = plain.indexOf(profileName);
        if (start < 0) {
            // The tag does not contain the name at all: show it in front of the (unavoidable) profile name.
            return new Team(flat + "§r ", "", TeamColor.WHITE, true);
        }
        // Map plain-text positions back into the formatted text.
        int rawStart = rawIndex(flat, start);
        int rawEnd = rawIndex(flat, start + profileName.length());
        String prefix = flat.substring(0, rawStart);
        String formatting = activeFormatting(prefix);
        String suffix = rawEnd < flat.length() ? activeFormatting(flat.substring(0, rawEnd)) + flat.substring(rawEnd) : "";
        return new Team(prefix, suffix, color(formatting), true);
    }

    /** Index in {@code text} of the {@code plainIndex}-th visible character. */
    private static int rawIndex(String text, int plainIndex) {
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == BedrockText.SECTION && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (visible == plainIndex) {
                return i;
            }
            visible++;
        }
        return text.length();
    }

    /** The {@code §} codes still in effect at the end of {@code text} (last colour and the styles after it). */
    private static String activeFormatting(String text) {
        StringBuilder active = new StringBuilder();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != BedrockText.SECTION) {
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'r' || isColor(code)) {
                active.setLength(0);
            }
            if (code != 'r') {
                active.append(BedrockText.SECTION).append(code);
            }
            i++;
        }
        return active.toString();
    }

    private static boolean isColor(char code) {
        return code >= '0' && code <= '9' || code >= 'a' && code <= 'f' || "ghijmnpqstuv".indexOf(code) >= 0;
    }

    /** Java team colour of the last vanilla colour code in {@code formatting} (teams only know the 16 colours). */
    private static TeamColor color(String formatting) {
        TeamColor color = TeamColor.WHITE;
        for (int i = 0; i + 1 < formatting.length(); i += 2) {
            char code = formatting.charAt(i + 1);
            int digit = Character.digit(code, 16);
            if (digit >= 0) {
                color = TeamColor.from(digit);
            }
        }
        return color;
    }
}
