package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.packet.AwardAchievementPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistActorPriorityPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraInstructionPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraShakePacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraSplinePacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundMapItemDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundTextureShiftPacket;
import org.cloudburstmc.protocol.bedrock.packet.LocatorBarPacket;
import org.cloudburstmc.protocol.bedrock.packet.OnScreenTextureAnimationPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerLocationPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHudPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScoreboardIdentityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.packet.ShowProfilePacket;
import org.cloudburstmc.protocol.bedrock.packet.ToastRequestPacket;
import org.geysermc.mcprotocollib.protocol.data.game.advancement.Advancement;

/**
 * Translators of the ui subsystem (see docs/DEVELOPING.md): titles, boss bars, scoreboards,
 * toasts, camera, maps and the locator bar.
 * <p>
 * Consumed without a Java equivalent (vanilla Java offers no server control over them):
 * <ul>
 *     <li>{@code SetHud}: Java cannot hide single HUD elements (hotbar, health, hunger, crosshair...);</li>
 *     <li>{@code CameraShake}: no camera shake on Java;</li>
 *     <li>{@code Camera} (Education Edition photo camera), aim assist presets/requests, camera splines
 *     (only used by spline instructions, which are ignored);</li>
 *     <li>{@code OnScreenTextureAnimation} (the Bedrock effect icon animation), {@code ShowProfile}
 *     (Xbox profile card), {@code ClientboundTextureShift} (texture shift animations);</li>
 *     <li>{@code SetScoreboardIdentity}: every Bedrock score entry already carries the player's id.</li>
 * </ul>
 */
public final class UiModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        MapColors.warmUp();

        registry.bedrock(SetTitlePacket.class, new Titles());
        registry.bedrock(BossEventPacket.class, (session, packet) -> session.get(BossBarState.KEY).onBossEvent(packet));

        registry.bedrock(SetDisplayObjectivePacket.class, (session, packet) -> session.get(ScoreboardState.KEY).onDisplayObjective(packet));
        registry.bedrock(SetScorePacket.class, (session, packet) -> session.get(ScoreboardState.KEY).onScores(packet.getScoreInfo()));
        registry.bedrock(RemoveObjectivePacket.class, (session, packet) -> {
            if (packet.getObjectiveName() != null) {
                session.get(ScoreboardState.KEY).onRemoveObjective(packet.getObjectiveName());
            }
        });

        registry.bedrock(ToastRequestPacket.class, (session, packet) -> {
            var text = RawText.translator(session);
            session.get(ToastState.KEY).show(text.apply(packet.getTitle() == null ? "" : packet.getTitle()),
                    packet.getContent() == null || packet.getContent().isEmpty() ? Component.empty() : text.apply(packet.getContent()),
                    ToastState.ICON_PAPER, Advancement.DisplayData.AdvancementType.GOAL);
        });
        registry.bedrock(AwardAchievementPacket.class, (session, packet) ->
                session.get(ToastState.KEY).show(Component.text("Succès débloqué"), Component.text("n°" + packet.getAchievementID()),
                        ToastState.ICON_KNOWLEDGE_BOOK, Advancement.DisplayData.AdvancementType.CHALLENGE));

        registry.bedrock(CameraPresetsPacket.class, (session, packet) -> session.get(CameraState.KEY).onPresets(packet));
        registry.bedrock(CameraInstructionPacket.class, (session, packet) -> session.get(CameraState.KEY).onInstruction(packet));

        registry.bedrock(ClientboundMapItemDataPacket.class, (session, packet) -> session.get(MapState.KEY).onMapData(packet));

        registry.bedrock(LocatorBarPacket.class, (session, packet) -> session.get(WaypointState.KEY).onLocatorBar(packet));
        registry.bedrock(PlayerLocationPacket.class, (session, packet) -> session.get(WaypointState.KEY).onPlayerLocation(packet));

        consume(registry, SetHudPacket.class);
        consume(registry, CameraShakePacket.class);
        consume(registry, CameraPacket.class);
        consume(registry, CameraAimAssistPacket.class);
        consume(registry, CameraAimAssistPresetsPacket.class);
        consume(registry, CameraAimAssistActorPriorityPacket.class);
        consume(registry, CameraSplinePacket.class);
        consume(registry, OnScreenTextureAnimationPacket.class);
        consume(registry, ShowProfilePacket.class);
        consume(registry, ClientboundTextureShiftPacket.class);
        consume(registry, SetScoreboardIdentityPacket.class);
    }

    private static <P extends BedrockPacket> void consume(TranslatorRegistry registry, Class<P> type) {
        registry.bedrock(type, (session, packet) -> {
        });
    }
}
