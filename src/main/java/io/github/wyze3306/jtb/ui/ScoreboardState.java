package io.github.wyze3306.jtb.ui;

import org.geysermc.mcprotocollib.protocol.data.game.chat.numbers.NumberFormat;
import org.geysermc.mcprotocollib.protocol.data.game.chat.numbers.BlankFormat;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.data.ObjectiveSortOrder;
import org.cloudburstmc.protocol.bedrock.data.payload.scoreboard.ChangeEntityScore;
import org.cloudburstmc.protocol.bedrock.data.payload.scoreboard.ChangeFakePlayerScore;
import org.cloudburstmc.protocol.bedrock.data.payload.scoreboard.ChangePlayerScore;
import org.cloudburstmc.protocol.bedrock.data.payload.scoreboard.RemoveScore;
import org.cloudburstmc.protocol.bedrock.data.payload.scoreboard.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.data.game.chat.numbers.FixedFormat;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock scoreboards to Java objectives and scores.
 * <p>
 * Bedrock clients only know objectives that are displayed: {@code SetDisplayObjective} creates the
 * Java objective (integer scores) and assigns it to {@code sidebar}, {@code list} (tab list) or
 * {@code belowname}; an empty objective name clears the slot. Bedrock scores are lines of an
 * objective identified by a scoreboard id (PowerNukkitX gives each line of each objective its own
 * id); their Java score holder is:
 * <ul>
 *     <li>players: the Java profile name ({@link EntityTracker#scoreHolder}; the local player's own
 *     name), so tab list and name tag scores land on the right player;</li>
 *     <li>entities: the entity's Java UUID string ({@link EntityTracker#scoreHolder});</li>
 *     <li>fake players: their name, shown with its formatting through the score's display name and
 *     made unique within the objective when two lines share it (Bedrock allows that, Java
 *     holders are unique per objective).</li>
 * </ul>
 * Java always sorts the sidebar by descending score; an ascending Bedrock sidebar is rendered by
 * sending negated scores with a fixed number format that shows the real value (in the default red).
 */
public final class ScoreboardState implements SessionState {

    public static final StateKey<ScoreboardState> KEY = StateKey.of("ui-scoreboard", ScoreboardState::new);

    private final ProxySession session;
    private final Map<String, Objective> objectives = new HashMap<>();
    private final EnumMap<ScoreboardPosition, String> slots = new EnumMap<>(ScoreboardPosition.class);

    /** One Java line: holder, how it is labelled and its Bedrock score. */
    private record Line(String holder, String base, @Nullable Component display, int value) {
    }

    private static final class Objective {
        final String name;
        Component displayName;
        boolean ascending;
        /** Bedrock scoreboard id -> line. */
        final Long2ObjectMap<Line> lines = new Long2ObjectOpenHashMap<>();
        /** Java holder -> Bedrock scoreboard id, to keep holders unique. */
        final Object2LongMap<String> holders = new Object2LongOpenHashMap<>();

        Objective(String name, Component displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    private ScoreboardState(ProxySession session) {
        this.session = session;
    }

    /** Bedrock display slot name to the Java position, or null for unknown slots. */
    static @Nullable ScoreboardPosition position(@Nullable String slot) {
        if (slot == null) {
            return null;
        }
        return switch (slot) {
            case "sidebar" -> ScoreboardPosition.SIDEBAR;
            case "list" -> ScoreboardPosition.PLAYER_LIST;
            case "belowname" -> ScoreboardPosition.BELOW_NAME;
            default -> null;
        };
    }

    public void onDisplayObjective(SetDisplayObjectivePacket packet) {
        ScoreboardPosition position = position(packet.getDisplaySlotName());
        if (position == null) {
            this.session.logger().debug("[{}] Unknown scoreboard slot {}", this.session.getName(), packet.getDisplaySlotName());
            return;
        }
        String name = packet.getObjectiveName();
        if (name == null || name.isEmpty()) {
            if (this.slots.remove(position) != null) {
                this.session.sendJava(new ClientboundSetDisplayObjectivePacket(position, ""));
            }
            return;
        }
        Component displayName = RawText.translator(this.session).apply(
                packet.getObjectiveDisplayName() == null ? name : packet.getObjectiveDisplayName());
        Objective objective = this.objectives.get(name);
        if (objective == null) {
            objective = new Objective(name, displayName);
            this.objectives.put(name, objective);
            this.session.sendJava(new ClientboundSetObjectivePacket(name, ObjectiveAction.ADD, displayName, ScoreType.INTEGER, numberFormat()));
        } else if (!objective.displayName.equals(displayName)) {
            objective.displayName = displayName;
            this.session.sendJava(new ClientboundSetObjectivePacket(name, ObjectiveAction.UPDATE, displayName, ScoreType.INTEGER, numberFormat()));
        }
        if (position == ScoreboardPosition.SIDEBAR) {
            boolean ascending = packet.getSortOrder() == ObjectiveSortOrder.ASCENDING;
            if (objective.ascending != ascending) {
                objective.ascending = ascending;
                for (Line line : objective.lines.values()) {
                    sendScore(objective, line);
                }
            }
        }
        this.slots.put(position, name);
        this.session.sendJava(new ClientboundSetDisplayObjectivePacket(position, name));
    }

    public void onRemoveObjective(String name) {
        if (this.objectives.remove(name) == null) {
            return;
        }
        this.slots.values().removeIf(name::equals);
        this.session.sendJava(new ClientboundSetObjectivePacket(name));
    }

    public void onScores(List<ScoreInfo> scores) {
        for (ScoreInfo info : scores) {
            switch (info) {
                case ChangePlayerScore player -> change(player.getScoreboardId(), player.getObjectiveName(), player.getScoreValue(),
                        playerHolder(player.getPlayerUniqueId()), false);
                case ChangeEntityScore entity -> change(entity.getScoreboardId(), entity.getObjectiveName(), entity.getScoreValue(),
                        entityHolder(entity.getActorId()), false);
                case ChangeFakePlayerScore fake -> change(fake.getScoreboardId(), fake.getObjectiveName(), fake.getScoreValue(),
                        fake.getFakePlayerName() == null ? "" : fake.getFakePlayerName(), true);
                case RemoveScore remove -> remove(remove.getScoreboardId(), remove.getObjectiveName());
                default -> {
                }
            }
        }
    }

    private void change(long id, @Nullable String objectiveName, int value, String name, boolean fake) {
        Objective objective = objectiveName == null ? null : this.objectives.get(objectiveName);
        if (objective == null) {
            // Bedrock ignores scores of objectives it does not display.
            return;
        }
        Line line = objective.lines.get(id);
        if (line != null && !line.base().equals(name)) {
            // The same Bedrock line now shows someone else: drop the old Java holder.
            removeLine(objective, id);
            line = null;
        }
        if (line == null) {
            String holder = name;
            if (fake) {
                while (objective.holders.containsKey(holder) && objective.holders.getLong(holder) != id) {
                    // Renders the same: "§r" is a formatting reset.
                    holder = holder + "§r";
                }
            } else if (objective.holders.containsKey(holder)) {
                // A player or entity has one line per objective: the older line is stale.
                objective.lines.remove(objective.holders.getLong(holder));
            }
            Component display = fake && (name.indexOf('§') >= 0 || name.indexOf('%') >= 0 || !holder.equals(name))
                    ? RawText.translator(this.session).apply(name) : null;
            line = new Line(holder, name, display, value);
            objective.holders.put(holder, id);
        } else {
            line = new Line(line.holder(), line.base(), line.display(), value);
        }
        objective.lines.put(id, line);
        sendScore(objective, line);
    }

    private void remove(long id, @Nullable String objectiveName) {
        if (objectiveName == null) {
            for (Objective objective : this.objectives.values()) {
                removeLine(objective, id);
            }
        } else {
            Objective objective = this.objectives.get(objectiveName);
            if (objective != null) {
                removeLine(objective, id);
            }
        }
    }

    /** {@link BlankFormat} when the configuration hides the scores ({@code features.scoreboard-numbers: false}), else null. */
    private @Nullable NumberFormat numberFormat() {
        return this.session.config().features().scoreboardNumbers() ? null : BlankFormat.INSTANCE;
    }

    private void removeLine(Objective objective, long id) {
        Line line = objective.lines.remove(id);
        if (line != null) {
            objective.holders.removeLong(line.holder());
            this.session.sendJava(new ClientboundResetScorePacket(line.holder(), objective.name));
        }
    }

    private void sendScore(Objective objective, Line line) {
        ClientboundSetScorePacket packet;
        if (objective.ascending) {
            packet = new ClientboundSetScorePacket(line.holder(), objective.name, negate(line.value()))
                    .withNumberFormat(numberFormat() != null ? numberFormat() : new FixedFormat(Component.text(line.value(), NamedTextColor.RED)));
        } else {
            packet = new ClientboundSetScorePacket(line.holder(), objective.name, line.value());
        }
        if (line.display() != null) {
            packet = packet.withDisplay(line.display());
        }
        this.session.sendJava(packet);
    }

    static int negate(int value) {
        return value == Integer.MIN_VALUE ? Integer.MAX_VALUE : -value;
    }

    private String playerHolder(long uniqueId) {
        String name = this.session.get(EntityTracker.KEY).scoreHolder(uniqueId);
        if (name != null) {
            return name;
        }
        // PowerNukkitX uses the runtime id as unique id; the local player is always known.
        if (uniqueId == this.session.get(PlayerState.KEY).runtimeId()) {
            return this.session.getName();
        }
        return Long.toString(uniqueId);
    }

    private String entityHolder(long uniqueId) {
        String name = this.session.get(EntityTracker.KEY).scoreHolder(uniqueId);
        return name != null ? name : Long.toString(uniqueId);
    }
}
