package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundChangeGameModePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The local player against PowerNukkitX, played like a Java client (one {@code ClientTickEnd} per
 * tick, key state, sprint/flight commands): walks, sprints, jumps and sneaks, then probes what the
 * server accepted by sending an impossible move, which PowerNukkitX reverts to its own idea of the
 * player's position. Then switches to creative with the game mode switcher (needs op: add the tester
 * to the server's ops.txt), flies, breaks and uses a block, goes back to survival, falls to death and
 * respawns.
 * <p>
 * Run it for 30 s. Checks that rely on other subsystems (attributes, experience) are only reported.
 */
public final class PlayerScenario implements E2eScenario {

    /** Phase starts, in ticks after joining. */
    private static final int WALK = 60, SPRINT = 100, JUMP = 130, SNEAK = 150, PROBE = 185, CREATIVE = 215,
            FLY = 235, DIG = 260, SURVIVAL = 285, CLIMB = 305, FALL = 335, RESPAWN_WAIT = 400, END = 520;
    private static final double PROBE_TOLERANCE = 0.15;
    /** Height above the ground of the stone block the fall ends on. */
    private static final int PLATFORM = 35;

    private final List<ClientboundPlayerPositionPacket> teleports = new CopyOnWriteArrayList<>();
    private final List<GameMode> gameModes = new CopyOnWriteArrayList<>();
    private final List<ClientboundPlayerAbilitiesPacket> abilities = new CopyOnWriteArrayList<>();
    private final List<Integer> acks = new CopyOnWriteArrayList<>();
    private final AtomicInteger respawns = new AtomicInteger();
    private volatile ClientboundPlayerCombatKillPacket death;
    private volatile float lastHealth = -1;

    private boolean forward, jump, shift, sprint;
    private double groundY;
    private double probeX, probeY, probeZ;
    private int teleportsBeforeMoves = -1;
    private ClientboundPlayerPositionPacket probeAnswer;
    private int sequence;
    private boolean respawnRequested;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundPlayerPositionPacket teleport) {
            this.teleports.add(teleport);
        } else if (packet instanceof ClientboundGameEventPacket event && event.getNotification() == GameEvent.CHANGE_GAME_MODE) {
            this.gameModes.add((GameMode) event.getValue());
        } else if (packet instanceof ClientboundPlayerAbilitiesPacket abilitiesPacket) {
            this.abilities.add(abilitiesPacket);
        } else if (packet instanceof ClientboundBlockChangedAckPacket ack) {
            this.acks.add(ack.getSequence());
        } else if (packet instanceof ClientboundPlayerCombatKillPacket kill) {
            this.death = kill;
            context.log("Death: " + PlainTextComponentSerializer.plainText().serialize(kill.getMessage()));
        } else if (packet instanceof ClientboundRespawnPacket) {
            this.respawns.incrementAndGet();
        } else if (packet instanceof ClientboundSetHealthPacket health) {
            this.lastHealth = health.getHealth();
        }
    }

    @Override
    public void onJoined(ClientContext context) {
        this.groundY = context.y();
    }

    @Override
    public void tick(ClientContext context, int tick) {
        if (tick < WALK) {
            if (tick == WALK - 1) {
                this.groundY = context.y();
                this.teleportsBeforeMoves = this.teleports.size();
            }
        } else if (tick < SPRINT) {
            keys(context, true, false, false, false);
            move(context, 0.2, 0, true);
        } else if (tick < JUMP) {
            if (tick == SPRINT) {
                context.send(new ServerboundPlayerCommandPacket(entityId(context), PlayerState.START_SPRINTING));
            }
            keys(context, true, false, false, true);
            move(context, 0.28, 0, true);
            if (tick == JUMP - 1) {
                context.send(new ServerboundPlayerCommandPacket(entityId(context), PlayerState.STOP_SPRINTING));
            }
        } else if (tick < SNEAK) {
            // One jump: up for 6 ticks, down for 6, like the Java physics roughly does.
            int t = tick - JUMP;
            keys(context, true, t < 3, false, false);
            double height = t < 12 ? 1.25 * Math.sin(Math.PI * t / 12) : 0;
            context.setPosition(context.x() + 0.2, this.groundY + height, context.z());
            sendPosition(context, t == 0 || t >= 12);
        } else if (tick < PROBE) {
            int t = tick - SNEAK;
            boolean sneaking = t < 25;
            keys(context, sneaking, false, sneaking, false);
            move(context, sneaking ? 0.06 : 0, 0, true);
        } else if (tick < CREATIVE) {
            keys(context, false, false, false, false);
            if (tick == PROBE) {
                this.probeX = context.x();
                this.probeY = context.y();
                this.probeZ = context.z();
                context.check("no rubber band while walking, sprinting, jumping and sneaking",
                        this.teleports.size() == this.teleportsBeforeMoves);
                // An impossible move: PowerNukkitX reverts it to where it thinks the player is.
                context.send(new ServerboundMovePlayerPosRotPacket(true, false, this.probeX + 40, this.probeY, this.probeZ,
                        context.yaw(), context.pitch()));
            } else if (this.probeAnswer == null && this.teleports.size() > this.teleportsBeforeMoves) {
                this.probeAnswer = this.teleports.getLast();
            }
        } else if (tick < FLY) {
            if (tick == CREATIVE) {
                context.send(new ServerboundChangeGameModePacket(GameMode.CREATIVE));
            }
        } else if (tick < DIG) {
            int t = tick - FLY;
            if (t == 0) {
                context.send(new ServerboundPlayerAbilitiesPacket(true));
            }
            keys(context, false, t < 10, false, false);
            context.setPosition(context.x(), this.groundY + (t < 10 ? t * 0.3 : Math.max(0, 3 - (t - 10) * 0.3)), context.z());
            sendPosition(context, t >= 20);
            if (t == 24) {
                context.send(new ServerboundPlayerAbilitiesPacket(false));
            }
        } else if (tick < SURVIVAL) {
            int t = tick - DIG;
            Vector3i target = Vector3i.from(Math.floor(context.x()) + 2, Math.floor(this.groundY) - 1, Math.floor(context.z()));
            if (t == 0) {
                context.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, target, Direction.UP, ++this.sequence));
                context.send(ServerboundPunchPacket.INSTANCE);
            } else if (t == 5) {
                context.send(new ServerboundUseItemOnPacket(target.add(0, -1, 0), Direction.UP, Hand.MAIN_HAND, 0.5f, 1f, 0.5f,
                        false, false, ++this.sequence));
                context.send(ServerboundPunchPacket.INSTANCE);
            } else if (t == 8) {
                context.send(ServerboundPunchPacket.INSTANCE);
            }
        } else if (tick < CLIMB) {
            if (tick == SURVIVAL) {
                context.send(new ServerboundChangeGameModePacket(GameMode.SURVIVAL));
            } else if (tick == SURVIVAL + 5) {
                // The fall ends on a stone block high above the terrain: water nearby, a village's hay
                // bales or beds would soften a fall to the ground.
                context.send(new ServerboundChatCommandPacket("setblock " + (int) Math.floor(context.x()) + " "
                        + ((int) Math.floor(this.groundY) + PLATFORM - 1) + " " + (int) Math.floor(context.z()) + " stone"));
            }
        } else if (tick < FALL) {
            // Climb 30 blocks above the platform (Bedrock servers do not check flight): the way down kills.
            context.setPosition(context.x(), this.groundY + (tick - CLIMB + 1) * (PLATFORM + 30) / (double) (FALL - CLIMB), context.z());
            sendPosition(context, false);
        } else if (tick < RESPAWN_WAIT) {
            int t = tick - FALL;
            double landing = Math.floor(this.groundY) + PLATFORM;
            double y = Math.max(landing, landing + 30 - t * 3);
            context.setPosition(context.x(), y, context.z());
            sendPosition(context, y == landing);
        } else if (tick < END) {
            if (this.death != null && !this.respawnRequested) {
                this.respawnRequested = true;
                context.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
            }
        }
        context.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    private void keys(ClientContext context, boolean forward, boolean jump, boolean shift, boolean sprint) {
        if (forward != this.forward || jump != this.jump || shift != this.shift || sprint != this.sprint) {
            this.forward = forward;
            this.jump = jump;
            this.shift = shift;
            this.sprint = sprint;
            context.send(new ServerboundPlayerInputPacket(forward, false, false, false, jump, shift, sprint));
        }
    }

    /** Walks along +X (yaw -90). */
    private void move(ClientContext context, double dx, double dy, boolean onGround) {
        context.setPosition(context.x() + dx, context.y() + dy, context.z());
        sendPosition(context, onGround);
    }

    private static void sendPosition(ClientContext context, boolean onGround) {
        context.send(new ServerboundMovePlayerPosRotPacket(onGround, false, context.x(), context.y(), context.z(), -90, 10));
    }

    private static int entityId(ClientContext context) {
        ClientboundLoginPacket login = context.last(ClientboundLoginPacket.class);
        return login == null ? 0 : login.getEntityId();
    }

    @Override
    public void finish(ClientContext context) {
        ClientboundSetHealthPacket health = context.last(ClientboundSetHealthPacket.class);
        context.check("received health and food (SetHealth)", health != null && health.getFood() >= 0);

        ClientboundPlayerPositionPacket answer = this.probeAnswer;
        context.check("server reverted the impossible move", answer != null);
        if (answer != null) {
            double dx = answer.getPosition().getX() - this.probeX;
            double dy = answer.getPosition().getY() - this.probeY;
            double dz = answer.getPosition().getZ() - this.probeZ;
            context.log(String.format("Server position %.3f %.3f %.3f vs client %.3f %.3f %.3f", answer.getPosition().getX(),
                    answer.getPosition().getY(), answer.getPosition().getZ(), this.probeX, this.probeY, this.probeZ));
            context.check("server accepted every move (its position is the client's)",
                    Math.abs(dx) < PROBE_TOLERANCE && Math.abs(dy) < PROBE_TOLERANCE && Math.abs(dz) < PROBE_TOLERANCE);
            context.check("correction keeps the camera (relative rotation)",
                    answer.getRelatives().contains(PositionElement.Y_ROT) && answer.getRelatives().contains(PositionElement.X_ROT));
        }

        context.check("game mode switched to creative and back", this.gameModes.contains(GameMode.CREATIVE)
                && !this.gameModes.isEmpty() && this.gameModes.getLast() == GameMode.SURVIVAL);
        context.check("creative abilities (may fly, instabuild)", this.abilities.stream().anyMatch(a -> a.isCanFly() && a.isCreative()));
        context.check("survival abilities restored", !this.abilities.isEmpty() && !this.abilities.getLast().isCanFly());
        context.check("block actions acknowledged", this.acks.contains(1) || this.acks.contains(2));
        context.check("died from the fall (death screen)", this.death != null);
        context.check("respawned after the death screen", this.respawns.get() > 0);
        context.check("alive after respawn (health)", this.lastHealth > 0);

        ClientboundSetExperiencePacket experience = context.last(ClientboundSetExperiencePacket.class);
        context.log("Experience packet: " + (experience == null ? "none (attributes come through the entity subsystem)"
                : "level " + experience.getLevel() + ", progress " + experience.getExperience()));
        ClientboundUpdateAttributesPacket attributes = context.last(ClientboundUpdateAttributesPacket.class);
        context.log("Attributes packet: " + (attributes == null ? "none" : attributes.getAttributes().size() + " attributes"));
    }
}
