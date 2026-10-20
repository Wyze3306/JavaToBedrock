package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.Collection;
import java.util.List;

/**
 * CONTRACT (owner: entity subsystem). Entities known to the Java client and the id mapping
 * between Bedrock runtime/unique ids (longs) and Java entity ids (ints).
 */
public final class EntityTracker implements SessionState {

    public static final StateKey<EntityTracker> KEY = StateKey.of("entity-tracker", EntityTracker::new);

    private final ProxySession session;
    private final Long2IntMap javaIds = new Long2IntOpenHashMap();
    private final Int2LongMap runtimeIds = new Int2LongOpenHashMap();
    private int nextJavaId = 1;

    private EntityTracker(ProxySession session) {
        this.session = session;
        this.javaIds.defaultReturnValue(-1);
        this.runtimeIds.defaultReturnValue(-1);
    }

    /** Registers the local player (runtime id -> Java id) before anything else spawns. */
    public void onStartGame(StartGamePacket packet) {
        bind(packet.getRuntimeID());
    }

    /** Assigns (or returns the existing) Java id of a Bedrock runtime id. */
    public int bind(long runtimeId) {
        int id = this.javaIds.get(runtimeId);
        if (id == -1) {
            id = allocateJavaId();
            this.javaIds.put(runtimeId, id);
            this.runtimeIds.put(id, runtimeId);
        }
        return id;
    }

    /** Forgets a Bedrock entity; returns its Java id or -1. */
    public int unbind(long runtimeId) {
        int id = this.javaIds.remove(runtimeId);
        if (id != -1) {
            this.runtimeIds.remove(id);
        }
        return id;
    }

    /** Java entity id for a Bedrock runtime id, or -1 when the entity is unknown. */
    public int javaId(long runtimeId) {
        return this.javaIds.get(runtimeId);
    }

    /** Bedrock runtime id for a Java entity id, or -1 when unknown. */
    public long runtimeId(int javaId) {
        return this.runtimeIds.get(javaId);
    }

    /** Allocates a Java entity id for a proxy-side entity (display, interaction, marker...). */
    public int allocateJavaId() {
        return this.nextJavaId++;
    }

    /** {@code MovePlayer} for another player (the player module handles the local one). */
    public void onRemotePlayerMove(MovePlayerPacket packet) {
    }

    /**
     * Names of the players in the server's player list ({@code PlayerListPacket}), as the Bedrock
     * server spells them. Used by the chat subsystem for command suggestions.
     */
    public Collection<String> playerNames() {
        return List.of();
    }
}
