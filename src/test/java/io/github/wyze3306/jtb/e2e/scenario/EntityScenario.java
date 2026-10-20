package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import io.github.wyze3306.jtb.entity.EntityDefinitions;
import io.github.wyze3306.jtb.entity.JavaEntityDefinition;
import io.github.wyze3306.jtb.entity.MetaField;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entities end to end (needs the test player in PowerNukkitX's {@code ops.txt} for {@code /summon},
 * {@code /damage} and {@code /kill}): the entities PowerNukkitX shows around the player spawn with valid Java types,
 * their moves and entity data only concern spawned entities and every entity data entry has the
 * index and type of the Java 26.3 tables, the tab list holds the local player with its own profile,
 * a summoned zombie appears next to the player with its data, a summoned named sheep shows its name,
 * the zombie is hurt (damage event), then dies (health 0, death event) and goes away when damaged to
 * death, and the sheep goes away when killed (PowerNukkitX's {@code /kill} only removes the entity).
 * Takes about 10 s after the player spawned: run it for 40 s.
 */
public final class EntityScenario implements E2eScenario {

    private static final String SHEEP_NAME = "JtbSheep";
    private static final double NEAR = 6;

    private final String playerName = System.getProperty("name", "Tester");
    private final Map<Integer, EntityType> alive = new ConcurrentHashMap<>();
    private final Map<Integer, EntityType> everSpawned = new ConcurrentHashMap<>();
    private final AtomicInteger spawns = new AtomicInteger();
    private final AtomicInteger invalidSpawns = new AtomicInteger();
    private final AtomicInteger duplicateSpawns = new AtomicInteger();
    private final AtomicInteger livingSpawns = new AtomicInteger();
    private final AtomicInteger moves = new AtomicInteger();
    private final AtomicInteger orphanMoves = new AtomicInteger();
    private final AtomicInteger dataPackets = new AtomicInteger();
    private final AtomicInteger dataEntries = new AtomicInteger();
    private final AtomicInteger badEntries = new AtomicInteger();
    private final AtomicInteger orphanData = new AtomicInteger();
    private volatile UUID localUuid;
    private volatile int localId = Integer.MIN_VALUE;
    private volatile boolean localListed;
    private volatile boolean summoned;
    private volatile int zombieId = Integer.MIN_VALUE;
    private volatile boolean zombieData;
    private volatile boolean zombieDied;
    private volatile boolean zombieHealthZero;
    private volatile boolean zombieRemoved;
    private volatile int sheepId = Integer.MIN_VALUE;
    private volatile boolean sheepNamed;
    private volatile boolean killSent;
    private volatile boolean zombieHurt;
    private volatile boolean sheepKillSent;
    private volatile boolean sheepRemoved;
    private int start = -1;

    public EntityScenario() {
        EntityDefinitions.load();
    }

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundLoginFinishedPacket finished) {
            this.localUuid = finished.getProfile().getId();
        } else if (packet instanceof ClientboundLoginPacket login) {
            this.localId = login.getEntityId();
            this.alive.put(login.getEntityId(), EntityType.PLAYER);
        } else if (packet instanceof ClientboundAddEntityPacket add) {
            onSpawn(context, add);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : remove.getEntityIds()) {
                this.alive.remove(id);
                if (id == this.zombieId) {
                    this.zombieRemoved = true;
                } else if (id == this.sheepId) {
                    this.sheepRemoved = true;
                }
            }
        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
            onData(context, data);
        } else if (packet instanceof ClientboundDamageEventPacket damage) {
            if (damage.getEntityId() == this.zombieId) {
                this.zombieHurt = true;
            }
        } else if (packet instanceof ClientboundEntityEventPacket event) {
            if (event.getEntityId() == this.zombieId && event.getEvent() == EntityEvent.LIVING_DEATH) {
                this.zombieDied = true;
            }
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
            if (info.getActions().contains(PlayerListEntryAction.ADD_PLAYER)) {
                for (PlayerListEntry entry : info.getEntries()) {
                    if (entry.getProfile() != null && entry.getProfileId().equals(this.localUuid)
                            && this.playerName.equals(entry.getProfile().getName())) {
                        this.localListed = true;
                    }
                }
            }
        } else {
            int id = moveTarget(packet);
            if (id != Integer.MIN_VALUE) {
                this.moves.incrementAndGet();
                if (!this.alive.containsKey(id)) {
                    this.orphanMoves.incrementAndGet();
                    context.log("Move for an entity that is not spawned: " + id + " " + packet.getClass().getSimpleName());
                }
            }
        }
    }

    private void onSpawn(ClientContext context, ClientboundAddEntityPacket add) {
        this.spawns.incrementAndGet();
        EntityType type = add.getType();
        if (type == null || add.getUuid() == null) {
            this.invalidSpawns.incrementAndGet();
            context.log("Invalid spawn: " + add);
            return;
        }
        if (this.alive.put(add.getEntityId(), type) != null) {
            this.duplicateSpawns.incrementAndGet();
            context.log("Entity spawned twice: " + add.getEntityId() + " " + type);
        }
        this.everSpawned.put(add.getEntityId(), type);
        if (type != EntityType.PLAYER && EntityDefinitions.forJava(type).isLiving()) {
            this.livingSpawns.incrementAndGet();
        }
        double distance = Math.sqrt(square(add.getX() - context.x()) + square(add.getY() - context.y()) + square(add.getZ() - context.z()));
        if (this.summoned && distance < NEAR) {
            if (type == EntityType.ZOMBIE && this.zombieId == Integer.MIN_VALUE) {
                this.zombieId = add.getEntityId();
                context.log("Summoned zombie: entity " + add.getEntityId() + " at " + add.getX() + " " + add.getY() + " " + add.getZ());
            } else if (type == EntityType.SHEEP && this.sheepId == Integer.MIN_VALUE) {
                this.sheepId = add.getEntityId();
                context.log("Summoned sheep: entity " + add.getEntityId());
            }
        }
    }

    private void onData(ClientContext context, ClientboundSetEntityDataPacket data) {
        this.dataPackets.incrementAndGet();
        EntityType type = this.alive.get(data.getEntityId());
        if (type == null) {
            this.orphanData.incrementAndGet();
            context.log("Entity data for an entity that is not spawned: " + data.getEntityId());
            return;
        }
        JavaEntityDefinition definition = EntityDefinitions.forJava(type);
        for (EntityMetadata<?, ?> entry : data.getMetadata()) {
            this.dataEntries.incrementAndGet();
            int index = entry.getId();
            if (index < 0 || index >= definition.fieldCount() || definition.fieldType(index) != entry.getType()) {
                this.badEntries.incrementAndGet();
                context.log("Entity data " + index + " (" + entry.getType() + ") does not fit " + type + " "
                        + (index >= 0 && index < definition.fieldCount() ? definition.fieldName(index) + " " + definition.fieldType(index) : "-"));
            }
        }
        if (data.getEntityId() == this.zombieId) {
            this.zombieData = true;
            for (EntityMetadata<?, ?> entry : data.getMetadata()) {
                if (this.killSent && entry.getId() == definition.index(MetaField.HEALTH)
                        && entry.getValue() instanceof Float health && health <= 0) {
                    this.zombieHealthZero = true;
                }
            }
        }
        if (data.getEntityId() == this.sheepId && sheepName(definition, data.getMetadata())) {
            this.sheepNamed = true;
        }
    }

    /** Whether the sheep's data sets a visible custom name with the summoned name. */
    private static boolean sheepName(JavaEntityDefinition definition, EntityMetadata<?, ?>[] metadata) {
        boolean named = false;
        boolean visible = false;
        for (EntityMetadata<?, ?> entry : metadata) {
            if (entry.getId() == definition.index(MetaField.CUSTOM_NAME) && entry.getValue() instanceof Optional<?> name
                    && name.isPresent() && name.get() instanceof Component component) {
                named = PlainTextComponentSerializer.plainText().serialize(component).contains(SHEEP_NAME);
            } else if (entry.getId() == definition.index(MetaField.CUSTOM_NAME_VISIBLE) && Boolean.TRUE.equals(entry.getValue())) {
                visible = true;
            }
        }
        return named && visible;
    }

    private static int moveTarget(Packet packet) {
        if (packet instanceof ClientboundMoveEntityPosPacket move) {
            return move.getEntityId();
        } else if (packet instanceof ClientboundMoveEntityPosRotPacket move) {
            return move.getEntityId();
        } else if (packet instanceof ClientboundMoveEntityRotPacket move) {
            return move.getEntityId();
        } else if (packet instanceof ClientboundEntityPositionSyncPacket move) {
            return move.getId();
        } else if (packet instanceof ClientboundTeleportEntityPacket move) {
            return move.getId();
        } else if (packet instanceof ClientboundRotateHeadPacket move) {
            return move.getEntityId();
        }
        return Integer.MIN_VALUE;
    }

    private static double square(double value) {
        return value * value;
    }

    @Override
    public void tick(ClientContext context, int tick) {
        // The server sends its commands while spawning the player: summon one second after them.
        if (this.start < 0) {
            if (context.last(ClientboundCommandsPacket.class) == null) {
                return;
            }
            this.start = tick + 20;
        }
        switch (tick - this.start) {
            case 0 -> {
                this.summoned = true;
                context.send(new ServerboundChatCommandPacket("summon minecraft:zombie ~2 ~ ~"));
            }
            // PowerNukkitX's parser only takes a name tag after the position and rotation.
            case 5 -> context.send(new ServerboundChatCommandPacket("summon minecraft:sheep ~-2 ~ ~ 0 0 " + SHEEP_NAME));
            case 60 -> context.send(new ServerboundChatCommandPacket("damage @e[type=zombie,r=8] 2"));
            case 100 -> {
                this.killSent = true;
                context.send(new ServerboundChatCommandPacket("damage @e[type=zombie,r=8] 100"));
            }
            case 140 -> {
                this.sheepKillSent = true;
                context.send(new ServerboundChatCommandPacket("kill @e[type=sheep,name=" + SHEEP_NAME + "]"));
            }
            default -> {
            }
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.log("Entities: " + this.spawns.get() + " spawned (" + this.livingSpawns.get() + " mobs), " + this.alive.size()
                + " alive, " + this.moves.get() + " moves, " + this.dataPackets.get() + " entity data packets ("
                + this.dataEntries.get() + " entries)");
        Map<EntityType, Integer> types = new ConcurrentHashMap<>();
        this.everSpawned.values().forEach(type -> types.merge(type, 1, Integer::sum));
        context.log("Spawned types: " + types);
        context.check("entities spawned, all with a Java type and UUID", this.spawns.get() > 0 && this.invalidSpawns.get() == 0);
        context.check("no entity spawned twice", this.duplicateSpawns.get() == 0);
        context.check("mobs spawned around the player", this.livingSpawns.get() > 0);
        context.check("entities move", this.moves.get() > 0);
        context.check("moves only for spawned entities", this.orphanMoves.get() == 0);
        context.check("entity data received", this.dataEntries.get() > 0);
        context.check("entity data only for spawned entities", this.orphanData.get() == 0);
        context.check("entity data indices and types match Java 26.3", this.badEntries.get() == 0);
        context.check("tab list has the local player with its profile", this.localListed);
        context.check("summoned zombie spawned next to the player", this.zombieId != Integer.MIN_VALUE);
        context.check("summoned zombie has entity data", this.zombieData);
        context.check("summoned named sheep shows its name", this.sheepNamed);
        context.check("damaged zombie shows the hurt animation", this.zombieHurt);
        context.check("killed zombie dies (health 0 and death event)", this.killSent && this.zombieHealthZero && this.zombieDied);
        context.check("killed zombie is removed", this.killSent && this.zombieRemoved);
        context.check("sheep removed by /kill", this.sheepKillSent && this.sheepRemoved);
    }
}
