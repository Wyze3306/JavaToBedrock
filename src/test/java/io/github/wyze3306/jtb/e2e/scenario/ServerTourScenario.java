package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A tour of a real server with its own plugins and resource packs (Linesia, for instance): records
 * everything a Java player gets and checks it is usable. Driven by system properties:
 * <ul>
 *     <li>{@code -Dcommands="give @s linesia:rubis_sword;summon linesia:sylven"}: commands run one per
 *     second after spawning (the tester must be an operator);</li>
 *     <li>{@code -Dclick=true}: every dialog (Bedrock form) gets its first button clicked, like a player
 *     who goes through the menus;</li>
 *     <li>{@code -Dexpect.items=N}, {@code -Dexpect.entities=N}, {@code -Dexpect.sounds=N},
 *     {@code -Dexpect.blocks=N}: the converted pack must hold at least that many custom item models,
 *     entity models, sound events and block models.</li>
 * </ul>
 * Checks: the converted pack is pushed, served and loads (SHA-1); every custom item the server gives
 * shows its pack model ({@code jtb:} item model); summoned custom entities appear as display entities;
 * dialogs come with at least one button; the player stays connected.
 */
public final class ServerTourScenario implements E2eScenario {

    private final List<String> commands = new ArrayList<>();
    private final boolean click = Boolean.getBoolean("click");
    /** {@code -Dinteract=true}: right clicks every interaction entity (custom entity hit box) within 6 blocks, once, after the commands. */
    private final boolean interact = Boolean.getBoolean("interact");
    private final Map<Integer, double[]> interactions = new ConcurrentHashMap<>();
    private final AtomicInteger dialogsAfterInteract = new AtomicInteger();
    private volatile boolean interacted;
    private final CountDownLatch inspected = new CountDownLatch(1);
    private volatile boolean pushed;
    private final Map<String, Integer> packCounts = new ConcurrentHashMap<>();

    private final Set<Key> itemModels = ConcurrentHashMap.newKeySet();
    /** The last stack of every (container, slot): what the player ends up seeing. */
    private final Map<String, ItemStack> slots = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> entityTypes = new ConcurrentHashMap<>();
    private final Set<Key> displayModels = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Boolean> displays = new ConcurrentHashMap<>();
    private final Set<String> customSounds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger builtinSounds = new AtomicInteger();
    private final List<String> dialogs = new CopyOnWriteArrayList<>();
    private final AtomicInteger dialogsWithoutButtons = new AtomicInteger();
    private final Set<String> titles = ConcurrentHashMap.newKeySet();
    private final Set<String> objectives = ConcurrentHashMap.newKeySet();
    private final Set<String> bossBars = ConcurrentHashMap.newKeySet();
    private final Set<String> screens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger actionBars = new AtomicInteger();
    private final Set<String> actionBarTexts = ConcurrentHashMap.newKeySet();
    /** Sidebar lines by score holder: [score, text]. */
    private final Map<String, Object[]> sidebar = new ConcurrentHashMap<>();

    public ServerTourScenario() {
        String list = System.getProperty("commands", "");
        for (String command : list.split(";")) {
            if (!command.isBlank()) {
                this.commands.add(command.strip().replaceFirst("^/", ""));
            }
        }
    }

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundResourcePackPushPacket push && !this.pushed) {
            this.pushed = true;
            Thread.ofVirtual().start(() -> {
                try {
                    inspect(context, push);
                } catch (Exception e) {
                    context.check("converted pack readable (" + e + ")", false);
                } finally {
                    this.inspected.countDown();
                }
            });
        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            this.slots.keySet().removeIf(key -> key.startsWith(content.getContainerId() + ":"));
            ItemStack[] items = content.getItems();
            for (int i = 0; i < items.length; i++) {
                slot(content.getContainerId(), i, items[i]);
            }
        } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            slot(slot.getContainerId(), slot.getSlot(), slot.getItem());
        } else if (packet instanceof ClientboundAddEntityPacket add) {
            this.entityTypes.computeIfAbsent(add.getType().name(), k -> new AtomicInteger()).incrementAndGet();
            if (add.getType() == EntityType.ITEM_DISPLAY) {
                this.displays.put(add.getEntityId(), true);
            } else if (add.getType() == EntityType.INTERACTION) {
                this.interactions.put(add.getEntityId(), new double[]{add.getX(), add.getY(), add.getZ()});
            }
        } else if (packet instanceof ClientboundSetEntityDataPacket data && this.displays.containsKey(data.getEntityId())) {
            for (EntityMetadata<?, ?> entry : data.getMetadata()) {
                if (entry.getValue() instanceof ItemStack stack && stack.getDataComponentsPatch() != null) {
                    Key model = stack.getDataComponentsPatch().get(DataComponentTypes.ITEM_MODEL);
                    if (model != null) {
                        this.displayModels.add(model);
                    }
                }
            }
        } else if (packet instanceof ClientboundSoundPacket sound) {
            if (sound.getSound() instanceof CustomSound custom) {
                this.customSounds.add(custom.getName());
            } else {
                this.builtinSounds.incrementAndGet();
            }
        } else if (packet instanceof ClientboundShowDialogGamePacket show && show.getDialog().isCustom()) {
            NbtMap dialog = show.getDialog().custom();
            String title = DialogClicks.title(dialog);
            NbtMap first = DialogClicks.firstButton(dialog);
            if (first == null) {
                first = dialog.getCompound("action", null);
            }
            this.dialogs.add(title + " [" + dialog.getString("type") + "]");
            if (this.interacted) {
                this.dialogsAfterInteract.incrementAndGet();
            }
            if (first == null) {
                this.dialogsWithoutButtons.incrementAndGet();
            } else if (this.click) {
                NbtMap button = first;
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    DialogClicks.click(context, dialog, button, Map.of());
                });
            }
        } else if (packet instanceof ClientboundSetTitleTextPacket title && title.getText() != null) {
            this.titles.add(plain(title.getText()));
        } else if (packet instanceof ClientboundSetActionBarTextPacket bar) {
            this.actionBars.incrementAndGet();
            if (this.actionBarTexts.size() < 20) {
                this.actionBarTexts.add(plain(bar.getText()));
            }
        } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket score) {
            Object display = score.getDisplay();
            this.sidebar.put(score.getOwner(), new Object[]{score.getValue(), display == null ? score.getOwner() : plain(display)});
        } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket reset) {
            this.sidebar.remove(reset.getOwner());
        } else if (packet instanceof ClientboundSetObjectivePacket objective && objective.getDisplayName() != null) {
            this.objectives.add(plain(objective.getDisplayName()));
        } else if (packet instanceof ClientboundBossEventPacket boss && boss.getTitle() != null) {
            this.bossBars.add(plain(boss.getTitle()));
        } else if (packet instanceof ClientboundOpenScreenPacket screen) {
            this.screens.add(screen.getType() + " " + plain(screen.getTitle()));
        } else if (packet instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
            String text = PlainTextComponentSerializer.plainText().serialize(chat.getContent());
            if (text.toLowerCase().contains("unknown") || text.toLowerCase().contains("inconnu")) {
                context.log("chat: " + text);
            }
        }
    }

    private void slot(int container, int slot, ItemStack item) {
        String key = container + ":" + slot;
        if (item == null) {
            this.slots.remove(key);
            return;
        }
        this.slots.put(key, item);
        DataComponents components = item.getDataComponentsPatch();
        Key model = components == null ? null : components.get(DataComponentTypes.ITEM_MODEL);
        if (model != null) {
            this.itemModels.add(model);
        }
    }

    /** Custom (non-vanilla) Bedrock items of the final slots that have no pack model. */
    private Set<String> itemsWithoutModel() {
        Set<String> missing = new java.util.TreeSet<>();
        for (ItemStack item : this.slots.values()) {
            DataComponents components = item.getDataComponentsPatch();
            if (components == null || components.get(DataComponentTypes.ITEM_MODEL) != null) {
                continue;
            }
            NbtMap custom = components.get(DataComponentTypes.CUSTOM_DATA);
            NbtMap jtb = custom == null ? null : custom.getCompound("jtb", null);
            String id = jtb == null ? null : jtb.getString("id", null);
            if (id != null && !id.startsWith("minecraft:")) {
                missing.add(id);
            }
        }
        return missing;
    }

    private void inspect(ClientContext context, ClientboundResourcePackPushPacket push) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(push.getUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] zip = response.body();
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        context.check("converted pack served (HTTP 200, SHA-1 of the push)",
                response.statusCode() == 200 && sha1.equalsIgnoreCase(push.getHash()));
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                files++;
                String name = entry.getName();
                if (name.startsWith("assets/jtb/items/item/")) {
                    this.packCounts.merge("items", 1, Integer::sum);
                } else if (name.startsWith("assets/jtb/items/entity/")) {
                    this.packCounts.merge("entities", 1, Integer::sum);
                } else if (name.startsWith("assets/jtb/models/block/custom/")) {
                    this.packCounts.merge("blocks", 1, Integer::sum);
                } else if (name.startsWith("assets/jtb/textures/font/")) {
                    this.packCounts.merge("glyphs", 1, Integer::sum);
                } else if (name.startsWith("assets/jtb/lang/")) {
                    this.packCounts.merge("languages", 1, Integer::sum);
                } else if (name.startsWith("assets/jtb/equipment/")) {
                    this.packCounts.merge("armours", 1, Integer::sum);
                } else if (name.equals("assets/jtb/sounds.json")) {
                    byte[] json = in.readAllBytes();
                    this.packCounts.put("sounds", com.google.gson.JsonParser.parseString(new String(json,
                            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().size());
                }
            }
        }
        this.packCounts.put("files", files);
        context.log("Converted pack: " + zip.length / 1024 + " KiB, " + new TreeMap<>(this.packCounts));
        for (String kind : List.of("items", "entities", "sounds", "blocks")) {
            int expected = Integer.getInteger("expect." + kind, 0);
            if (expected > 0) {
                context.check("pack holds at least " + expected + " custom " + kind,
                        this.packCounts.getOrDefault(kind, 0) >= expected);
            }
        }
    }

    @Override
    public void tick(ClientContext context, int tick) {
        int interactTick = 60 + 20 * this.commands.size() + 40;
        if (this.interact && tick == interactTick) {
            this.interacted = true;
            for (Map.Entry<Integer, double[]> entry : this.interactions.entrySet()) {
                double[] p = entry.getValue();
                double dx = p[0] - context.x(), dy = p[1] - context.y(), dz = p[2] - context.z();
                if (dx * dx + dy * dy + dz * dz <= 36) {
                    context.log("interact with entity " + entry.getKey());
                    context.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket(
                            entry.getKey(), org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand.MAIN_HAND,
                            org.cloudburstmc.math.vector.Vector3d.from(0, 1, 0), false));
                }
            }
        }
        // One command a second, after 3 s.
        if (tick >= 60 && tick % 20 == 0) {
            int index = (tick - 60) / 20;
            if (index < this.commands.size()) {
                context.log("command /" + this.commands.get(index));
                context.send(new ServerboundChatCommandPacket(this.commands.get(index)));
            }
        }
    }

    @Override
    public void finish(ClientContext context) {
        try {
            this.inspected.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        context.check("resource pack pushed", this.pushed);
        context.log("Item models seen (" + this.itemModels.size() + "): " + new java.util.TreeSet<>(this.itemModels.stream().map(Key::asString).toList()));
        Set<String> missing = itemsWithoutModel();
        context.log("Custom items without model in the final inventory: " + missing);
        context.log("Entity types: " + new TreeMap<>(this.entityTypes));
        context.log("Display entity models (" + this.displayModels.size() + "): " + this.displayModels);
        context.log("Custom sounds (" + this.customSounds.size() + "): " + this.customSounds + ", builtin sounds: " + this.builtinSounds);
        context.log("Dialogs (" + this.dialogs.size() + "): " + this.dialogs);
        context.log("Titles: " + this.titles + ", action bars: " + this.actionBars + " " + this.actionBarTexts);
        java.util.List<Object[]> lines = new java.util.ArrayList<>(this.sidebar.values());
        lines.sort(java.util.Comparator.comparingInt(line -> -((Number) line[0]).intValue()));
        for (Object[] line : lines) {
            context.log("Sidebar " + line[0] + ": " + line[1]);
        }
        context.log("Scoreboards: " + this.objectives + ", boss bars: " + this.bossBars + ", screens: " + this.screens);
        context.check("every custom item of the final inventory has its pack model", missing.isEmpty());
        context.check("every dialog has a button", this.dialogsWithoutButtons.get() == 0);
        if (this.interact) {
            context.log("Dialogs opened by the interactions: " + this.dialogsAfterInteract.get());
        }
        if (this.commands.stream().anyMatch(c -> c.startsWith("give ") && !c.contains("minecraft:"))) {
            context.check("given custom items show a pack model", !this.itemModels.isEmpty());
        }
        if (this.commands.stream().anyMatch(c -> c.startsWith("summon ") && !c.contains("minecraft:"))) {
            context.check("summoned custom entities show a pack model", !this.displayModels.isEmpty());
        }
    }

    private static String plain(Object component) {
        return component instanceof net.kyori.adventure.text.Component text
                ? PlainTextComponentSerializer.plainText().serialize(text) : String.valueOf(component);
    }
}
