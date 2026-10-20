package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarAction;
import org.geysermc.mcprotocollib.protocol.data.game.advancement.Advancement;
import org.geysermc.mcprotocollib.protocol.data.game.chat.numbers.FixedFormat;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSetCameraPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateAdvancementsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Titles, scoreboards, boss bar, toast and camera, driven on PowerNukkitX by the test plugin
 * ({@code src/test/pnx-plugin}) with op commands run as the player ({@code TesterN} must be in the
 * instance's {@code ops.txt}). Records what the Java client receives.
 */
public final class UiScenario implements E2eScenario {

    private final Set<String> events = ConcurrentHashMap.newKeySet();
    private volatile int playerId = -1;
    private volatile int cameraEntity = -1;
    private volatile String toastId;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        switch (packet) {
            case ClientboundLoginPacket login -> this.playerId = login.getEntityId();
            case ClientboundSetTitleTextPacket title -> record(context, "title:" + plain(title.getText()));
            case ClientboundSetSubtitleTextPacket subtitle -> record(context, "subtitle:" + plain(subtitle.getText()));
            case ClientboundSetActionBarTextPacket bar -> record(context, "actionbar:" + plain(bar.getText()));
            case ClientboundSetTitlesAnimationPacket times ->
                    record(context, "times:" + times.getFadeIn() + "/" + times.getStay() + "/" + times.getFadeOut());
            case ClientboundSetObjectivePacket objective -> record(context, "objective:" + objective.getAction() + ":" + objective.getName()
                    + (objective.getDisplayName() == null ? "" : ":" + plain(objective.getDisplayName())));
            case ClientboundSetDisplayObjectivePacket display -> record(context, "display:" + display.getPosition() + ":" + display.getName());
            case ClientboundSetScorePacket score -> record(context, "score:" + score.getOwner() + ":" + score.getObjective() + ":" + score.getValue()
                    + (score.getNumberFormat() instanceof FixedFormat fixed ? ":fixed=" + plain(fixed.getValue()) : ""));
            case ClientboundResetScorePacket reset -> record(context, "reset:" + reset.getOwner() + ":" + reset.getObjective());
            case ClientboundBossEventPacket boss when boss.getAction() == BossBarAction.ADD ->
                    record(context, "boss:" + plain(boss.getTitle()) + ":" + boss.getHealth() + ":" + boss.getColor());
            case ClientboundUpdateAdvancementsPacket advancements -> onAdvancements(context, advancements);
            case ClientboundAddEntityPacket add when add.getType() == EntityType.ITEM_DISPLAY -> {
                this.cameraEntity = add.getEntityId();
                record(context, "camera-entity");
            }
            case ClientboundSetCameraPacket camera -> {
                if (camera.getCameraEntityId() == this.cameraEntity) {
                    record(context, "camera:set");
                } else if (camera.getCameraEntityId() == this.playerId) {
                    record(context, "camera:player");
                }
            }
            case ClientboundRemoveEntitiesPacket remove when this.cameraEntity >= 0
                    && Arrays.stream(remove.getEntityIds()).anyMatch(id -> id == this.cameraEntity) -> record(context, "camera-entity:removed");
            default -> {
            }
        }
    }

    private void onAdvancements(ClientContext context, ClientboundUpdateAdvancementsPacket packet) {
        for (Advancement advancement : packet.getAdvancements()) {
            if (advancement.getId().startsWith("jtb:toast/") && advancement.getDisplayData() != null
                    && advancement.getDisplayData().isShowToast() && !packet.isReset()
                    && packet.getProgress().containsKey(advancement.getId())) {
                this.toastId = advancement.getId();
                record(context, "toast:" + plain(advancement.getDisplayData().getTitle()));
            }
        }
        if (this.toastId != null && Arrays.asList(packet.getRemovedAdvancements()).contains(this.toastId)) {
            record(context, "toast:removed");
        }
    }

    private void record(ClientContext context, String event) {
        if (this.events.add(event)) {
            context.log("ui " + event);
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Override
    public void finish(ClientContext context) {
        String player = System.getProperty("name", "JtbTester");
        context.check("title", this.events.contains("title:Titre JTB"));
        context.check("subtitle", this.events.contains("subtitle:Sous-titre JTB"));
        context.check("action bar", this.events.contains("actionbar:Barre JTB"));
        // PowerNukkitX's "/title times" only logs; its title command sends its own times (20/20/5).
        context.check("title times", this.events.contains("times:20/20/5"));
        context.check("sidebar objective created", this.events.contains("objective:ADD:jtbside:JTBSide"));
        context.check("sidebar displayed", this.events.contains("display:" + ScoreboardPosition.SIDEBAR + ":jtbside"));
        context.check("player score", this.events.contains("score:" + player + ":jtbside:42"));
        context.check("fake player score", this.events.contains("score:Ligne:jtbside:7"));
        context.check("score reset", this.events.contains("reset:Ligne:jtbside"));
        context.check("tab list objective", this.events.contains("display:" + ScoreboardPosition.PLAYER_LIST + ":jtblist")
                && this.events.contains("score:" + player + ":jtblist:5"));
        context.check("objective removed", this.events.contains("objective:" + ObjectiveAction.REMOVE + ":jtblist"));
        context.check("ascending sidebar shows the real value", this.events.contains("score:" + player + ":jtbasc:-3:fixed=3"));
        context.check("boss bar", this.events.stream().anyMatch(event -> event.startsWith("boss:Boss JTB:0.6")));
        context.check("toast shown", this.events.stream().anyMatch(event -> event.startsWith("toast:Toast JTB")));
        context.check("toast advancement removed", this.events.contains("toast:removed"));
        context.check("free camera on a proxy entity", this.events.contains("camera-entity") && this.events.contains("camera:set"));
        context.check("camera cleared", this.events.contains("camera:player") && this.events.contains("camera-entity:removed"));
    }
}
