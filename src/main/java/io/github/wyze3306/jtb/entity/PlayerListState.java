package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.skin.SignedSkins;
import io.github.wyze3306.jtb.skin.SkinMods;
import io.github.wyze3306.jtb.text.BedrockText;
import org.cloudburstmc.protocol.bedrock.data.payload.list.PlayerListAddEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.list.PlayerListEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.list.PlayerListRemoveEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Java tab list, kept in step with Bedrock's {@code PlayerList}. Every Bedrock player (and every
 * spawned player entity, which Java cannot spawn without a profile) has a Java player info entry:
 * <ul>
 *     <li>remote players use their Bedrock UUID and a profile name that is their Bedrock name cut to
 *     Java's 16 characters; the full, formatted name is the tab display name. Java only renders
 *     Mojang-signed skins: the profile carries the signed textures {@link SignedSkins} has for the
 *     Bedrock skin, otherwise it is added without textures (the default skin of the UUID) and, once
 *     the skin is signed in the background, added again with them, the player entity being spawned
 *     again so the client picks the new profile up. Clients with a Bedrock skin mod also get the full
 *     skin ({@link SkinMods});</li>
 *     <li>the local player's entry uses the Java client's own profile (UUID, name and its signed
 *     textures), so the client keeps its own skin.</li>
 * </ul>
 * A Bedrock removal of a player whose entity is still spawned only unlists the entry (removing it
 * would break the entity's rendering); the entry goes when the entity goes.
 */
final class PlayerListState {

    private static final EnumSet<PlayerListEntryAction> ADD_ACTIONS = EnumSet.of(PlayerListEntryAction.ADD_PLAYER,
            PlayerListEntryAction.UPDATE_GAME_MODE, PlayerListEntryAction.UPDATE_LISTED, PlayerListEntryAction.UPDATE_LATENCY,
            PlayerListEntryAction.UPDATE_DISPLAY_NAME, PlayerListEntryAction.UPDATE_HAT);
    private static final EnumSet<PlayerListEntryAction> RELIST_ACTIONS = EnumSet.of(PlayerListEntryAction.UPDATE_LISTED,
            PlayerListEntryAction.UPDATE_DISPLAY_NAME);
    private static final EnumSet<PlayerListEntryAction> LISTED_ACTION = EnumSet.of(PlayerListEntryAction.UPDATE_LISTED);
    private static final EnumSet<PlayerListEntryAction> GAME_MODE_ACTION = EnumSet.of(PlayerListEntryAction.UPDATE_GAME_MODE);
    private static final int MAX_PROFILE_NAME = 16;

    /** One Java player info entry. */
    static final class Entry {
        final UUID bedrockUuid;
        final UUID javaUuid;
        final boolean local;
        final String profileName;
        long uniqueId;
        String name;
        boolean listed;
        GameMode gameMode;
        /** Java entity id of the spawned player entity, or -1. */
        int javaEntityId = -1;
        @Nullable String xuid;
        /** Hash of the Bedrock skin ({@link SignedSkins.Skin#hash()}), null when Java cannot draw it. */
        @Nullable String skinHash;
        /** Signed textures of the skin, null while unknown. */
        SignedSkins.@Nullable Textures textures;

        Entry(UUID bedrockUuid, UUID javaUuid, boolean local, String profileName, long uniqueId, String name,
              GameMode gameMode) {
            this.bedrockUuid = bedrockUuid;
            this.javaUuid = javaUuid;
            this.local = local;
            this.profileName = profileName;
            this.uniqueId = uniqueId;
            this.name = name;
            this.gameMode = gameMode;
        }
    }

    private final ProxySession session;
    private final EntityTracker tracker;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private @Nullable SignedSkins skins;
    private boolean skinsLookedUp;

    PlayerListState(ProxySession session, EntityTracker tracker) {
        this.session = session;
        this.tracker = tracker;
    }

    @Nullable Entry entry(UUID bedrockUuid) {
        return this.entries.get(bedrockUuid);
    }

    @Nullable Entry entryByUniqueId(long uniqueId) {
        for (Entry entry : this.entries.values()) {
            if (entry.uniqueId == uniqueId) {
                return entry;
            }
        }
        return null;
    }

    Collection<Entry> entries() {
        return Collections.unmodifiableCollection(this.entries.values());
    }

    void onPlayerList(PlayerListPacket packet) {
        List<org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry> added = null;
        List<org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry> relisted = null;
        List<org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry> unlisted = null;
        List<UUID> removed = null;
        List<Entry> skinned = null;
        List<Entry> readded = null;
        for (PlayerListEntry raw : packet.getEntries()) {
            if (raw instanceof PlayerListAddEntry add) {
                Entry entry = this.entries.get(add.getUuid());
                String name = add.getPlayerName() == null ? "" : add.getPlayerName();
                SerializedSkin skin = add.getSerializedSkin();
                if (entry == null) {
                    entry = create(add.getUuid(), add.getActorUniqueID(), name, true);
                    entry.xuid = add.getXblXUID();
                    setSkin(entry, skin);
                    (added == null ? added = new ArrayList<>() : added).add(javaEntry(entry, true));
                    if (skin != null && !entry.local) {
                        (skinned == null ? skinned = new ArrayList<>() : skinned).add(entry);
                    }
                } else {
                    entry.uniqueId = add.getActorUniqueID();
                    entry.name = name;
                    entry.listed = true;
                    if (setSkin(entry, skin)) {
                        (readded == null ? readded = new ArrayList<>() : readded).add(entry);
                    } else {
                        (relisted == null ? relisted = new ArrayList<>() : relisted).add(javaEntry(entry, false));
                    }
                }
            } else if (raw instanceof PlayerListRemoveEntry remove) {
                Entry entry = this.entries.get(remove.getUuid());
                if (entry == null) {
                    continue;
                }
                if (entry.javaEntityId != -1 || entry.local) {
                    entry.listed = false;
                    (unlisted == null ? unlisted = new ArrayList<>() : unlisted).add(javaEntry(entry, false));
                } else {
                    this.entries.remove(remove.getUuid());
                    (removed == null ? removed = new ArrayList<>() : removed).add(entry.javaUuid);
                }
            }
        }
        send(ADD_ACTIONS, added);
        send(RELIST_ACTIONS, relisted);
        send(LISTED_ACTION, unlisted);
        if (removed != null) {
            this.session.sendJava(new ClientboundPlayerInfoRemovePacket(removed));
        }
        if (readded != null) {
            for (Entry entry : readded) {
                readd(entry);
            }
        }
        if (skinned != null) {
            for (PlayerListEntry raw : packet.getEntries()) {
                if (raw instanceof PlayerListAddEntry add && add.getSerializedSkin() != null) {
                    Entry entry = this.entries.get(add.getUuid());
                    if (entry != null && skinned.contains(entry)) {
                        SkinMods.send(this.session, entry.javaUuid, add.getSerializedSkin());
                    }
                }
            }
        }
    }

    /** {@code PlayerSkin}: a player changed skin. */
    void onSkin(UUID bedrockUuid, @Nullable SerializedSkin skin) {
        Entry entry = this.entries.get(bedrockUuid);
        if (entry == null || entry.local || skin == null) {
            return;
        }
        if (setSkin(entry, skin)) {
            readd(entry);
        }
        SkinMods.send(this.session, entry.javaUuid, skin);
    }

    /**
     * Records the skin of {@code entry} and takes the signed textures known for it; unknown ones are
     * signed in the background ({@link #onSigned}).
     *
     * @return whether the textures of the entry changed
     */
    private boolean setSkin(Entry entry, @Nullable SerializedSkin serialized) {
        if (entry.local) {
            return false;
        }
        SignedSkins.Skin skin = SignedSkins.Skin.of(serialized);
        String hash = skin == null ? null : skin.hash();
        if (Objects.equals(hash, entry.skinHash)) {
            return false;
        }
        entry.skinHash = hash;
        SignedSkins.Textures before = entry.textures;
        entry.textures = null;
        SignedSkins skins = skins();
        if (skin != null && skins != null) {
            SignedSkins.Textures cached = skins.cached(skin);
            if (cached != null) {
                entry.textures = cached;
            } else {
                CompletableFuture<SignedSkins.@Nullable Textures> future = skins.resolve(skin, entry.xuid);
                if (future.isDone()) {
                    entry.textures = future.getNow(null);
                } else {
                    UUID uuid = entry.bedrockUuid;
                    future.thenAccept(textures -> {
                        if (textures != null) {
                            this.session.execute(() -> onSigned(uuid, hash, textures));
                        }
                    });
                }
            }
        }
        return !Objects.equals(before, entry.textures);
    }

    /** Signed textures arrived (session loop): add the player again with them if it still has that skin. */
    private void onSigned(UUID bedrockUuid, String hash, SignedSkins.Textures textures) {
        Entry entry = this.entries.get(bedrockUuid);
        if (entry == null || !hash.equals(entry.skinHash) || textures.equals(entry.textures)) {
            return;
        }
        entry.textures = textures;
        readd(entry);
    }

    /**
     * Replaces the client's player info of {@code entry} (Java keeps the first profile of a UUID), then
     * spawns its player entity again, which caches the profile it was created with.
     */
    private void readd(Entry entry) {
        this.session.sendJava(new ClientboundPlayerInfoRemovePacket(List.of(entry.javaUuid)));
        send(ADD_ACTIONS, List.of(javaEntry(entry, true)));
        TrackedEntity entity = entry.javaEntityId == -1 ? null : this.tracker.entityByJavaId(entry.javaEntityId);
        if (entity != null) {
            this.tracker.respawnPlayer(entity);
        }
    }

    private @Nullable SignedSkins skins() {
        if (!this.skinsLookedUp) {
            this.skinsLookedUp = true;
            try {
                this.skins = this.session.proxy() == null ? null : this.session.proxy().service(SignedSkins.class);
            } catch (IllegalStateException e) {
                this.session.logger().debug("[{}] Signed skins unavailable: {}", this.session.getName(), e.getMessage());
            }
        }
        return this.skins;
    }

    private void send(EnumSet<PlayerListEntryAction> actions,
                      @Nullable List<org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry> list) {
        if (list != null) {
            this.session.sendJava(new ClientboundPlayerInfoUpdatePacket(actions,
                    list.toArray(org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry[]::new)));
        }
    }

    /**
     * The entry of a player entity about to be spawned: the existing one, or an unlisted one created
     * now (Bedrock may spawn players, e.g. NPCs, that are not in its list).
     */
    Entry ensureForSpawn(UUID bedrockUuid, long uniqueId, String name, GameMode gameMode) {
        Entry entry = this.entries.get(bedrockUuid);
        if (entry == null) {
            entry = create(bedrockUuid, uniqueId, name, false);
            entry.gameMode = gameMode;
            send(ADD_ACTIONS, List.of(javaEntry(entry, true)));
        } else {
            entry.uniqueId = uniqueId;
            if (entry.gameMode != gameMode && !entry.local) {
                entry.gameMode = gameMode;
                send(GAME_MODE_ACTION, List.of(javaEntry(entry, false)));
            }
        }
        return entry;
    }

    /** The player entity of {@code entry} is gone: drop the entry when Bedrock already removed it from its list. */
    void onEntityRemoved(Entry entry) {
        entry.javaEntityId = -1;
        if (!entry.listed && !entry.local) {
            this.entries.remove(entry.bedrockUuid);
            this.session.sendJava(new ClientboundPlayerInfoRemovePacket(List.of(entry.javaUuid)));
        }
    }

    /** Game mode change of a listed player (spectators show greyed out on Java). */
    void updateGameMode(Entry entry, GameMode gameMode) {
        if (entry.gameMode != gameMode) {
            entry.gameMode = gameMode;
            send(GAME_MODE_ACTION, List.of(javaEntry(entry, false)));
        }
    }

    /** Forgets everything (the Java client cleared its list, e.g. after a reconfiguration). */
    void clear() {
        this.entries.clear();
    }

    private Entry create(UUID bedrockUuid, long uniqueId, String name, boolean listed) {
        boolean local = isLocal(uniqueId, name);
        UUID javaUuid = local ? this.session.getProfile().getId() : bedrockUuid;
        String profileName = local ? this.session.getProfile().getName() : profileName(name);
        GameMode mode = local ? GameModes.toJava(this.session.get(PlayerState.KEY).gameType()) : GameMode.SURVIVAL;
        Entry entry = new Entry(bedrockUuid, javaUuid, local, profileName, uniqueId, name, mode);
        entry.listed = listed;
        this.entries.put(bedrockUuid, entry);
        return entry;
    }

    private boolean isLocal(long uniqueId, String name) {
        long localUniqueId = this.tracker.localUniqueId();
        if (localUniqueId != Long.MIN_VALUE) {
            return uniqueId == localUniqueId;
        }
        // PowerNukkitX sends its list before StartGame: recognise the local player by name.
        return BedrockText.stripFormatting(name).toLowerCase(Locale.ROOT)
                .equals(this.session.getBedrockName().toLowerCase(Locale.ROOT));
    }

    /** Java profile names are at most 16 characters; formatting codes are dropped. */
    static String profileName(String bedrockName) {
        String plain = BedrockText.stripFormatting(bedrockName).replace('\n', ' ').strip();
        if (plain.isEmpty()) {
            plain = "Player";
        }
        return plain.length() > MAX_PROFILE_NAME ? plain.substring(0, MAX_PROFILE_NAME) : plain;
    }

    private org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry javaEntry(Entry entry, boolean withProfile) {
        GameProfile profile = null;
        if (withProfile) {
            profile = new GameProfile(entry.javaUuid, entry.profileName);
            if (entry.local) {
                profile.setProperties(this.session.getProfile().getProperties());
            } else if (entry.textures != null) {
                profile.setProperties(List.of(entry.textures.toProperty()));
            }
        }
        return new org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry(entry.javaUuid, profile, entry.listed, 0,
                entry.gameMode, BedrockText.legacyToComponent(entry.name), true, 0, null, 0, null, null);
    }
}
