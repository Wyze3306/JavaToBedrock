# Developing JavaToBedrock

Read `docs/ARCHITECTURE.md` first. This file holds the rules every subsystem follows and who owns
which packet.

## Build & run

```bash
./gradlew build                # compile, tests, shadow jar -> build/libs/JavaToBedrock.jar
java -jar build/libs/JavaToBedrock.jar        # writes config.yml on first start
```

Java 21. Dependencies: MCProtocolLib 26.3 (Java side), PowerNukkitX Protocol `3.0.0.Beta8-SNAPSHOT` and the
CloudburstMC NetherNet transport with libdatachannel (Bedrock side), the versions PowerNukkitX 1.26.60 uses.
The protocol is a moving snapshot and its repository keeps only the latest build: an upstream API change can
break the build without any change here. The wire format is the codec the proxy picks
(`network.bedrock.BedrockCodecs`: 1.26.60, protocol 2225, only).

**Transport: NetherNet only.** `network.bedrock.BedrockConnector` opens a `NetherNetClientChannel`
(`NetherNetHTTPClientSignaling`: the SDP offer goes to the server's port over HTTP/TCP, then WebRTC data
channels) and `NetherNetClientInitializer` builds the Bedrock pipeline on it (one batch per data channel
message, no RakNet frame id; the protocol library is told the RakNet version it would read from RakNet
channel options). Every offer carries an identity assertion signed with the proxy's operator key
(`bedrock.nethernet-identity-file`, P-384, created on first start) derived per player
(`OperatorIdentity.forPlayer`): PowerNukkitX refuses unsigned offers (HTTP 401). The server's assertion is
not checked (its PEM-based token has no expiry, which `TokenTrust.ANY` requires). `BedrockPinger` probes
`GET` on the signalling endpoint (pong data: MOTD, protocol, players). The RakNet transport stays on the
class path only because the protocol library reads `RakChannelOption` in shared code.

## End-to-end test

`src/test/java/.../e2e/HeadlessJavaClient` is a display-less Java 26.3 client: it logs in, answers the
configuration phase like the real client (known packs, downloads and SHA-1 checks the resource pack),
joins, acknowledges teleports and runs a scenario (`e2e/scenario/*Scenario`, chosen with
`-Dscenario=name`). Scenarios record `context.check(name, ok)`; one failed check fails the run.

```bash
./gradlew runHeadlessClient -Phost=127.0.0.1 -Pport=25565 -Pname=Tester -Pseconds=30
```

The whole chain in one command, against any PowerNukkitX jar (a throw-away server is prepared in
`build/e2e/pnx` with xbox-auth off and the player op; exit code = the client's):

```bash
tools/e2e/run.sh path/to/powernukkitx.jar world 40
```

Scenarios (`foo_bar` = `FooBarScenario`): `default`, `world`, `world_travel`, `player`, `entity`,
`chat`, `form`, `ui`, `sound`, `resource_pack`, `inventory`, `item_frame`... Some need server-side helpers: `form`/`ui`
use the test plugin in `src/test/pnx-plugin`, `resource_pack` packs in the server's `resource_packs/`,
`PNX_JAVA=/path/to/java` runs the server with another Java than the proxy (upstream PowerNukkitX `beta` needs
Java 25; the Linesia fork runs on 21). `run.sh` prepares the server-side helpers itself (`form`/`ui`: the
test plugin; `resource_pack`/`inventory_custom_items`: the test packs and the `JtbE2eItems` plugin) and
starts every run with fresh player data. `server_tour` visits a real server with its own plugins and packs
(`-Dcommands="give @s ns:item;npc x"`, `-Dclick=true`, `-Dinteract=true`, `-Dexpect.items=N`...).
`reconnect` a server restart during the run, `server_switch` a second server
(`JTB_BEDROCK_SERVERS=bis=host:port`), `peers` a second client named `Load1`.

## Rules

1. **Threading.** Translators run on the session event loop. Never block it (no I/O, no sleeping,
   no waiting on futures). CPU-heavy work (chunk conversion, lighting) goes to
   `proxy.workers()` and comes back with `session.execute(...)`.
2. **Hot paths allocate nothing they can avoid**: chunk conversion, movement ticks, entity moves.
   Use fastutil primitive collections.
3. **Never touch `org.cloudburstmc.protocol.bedrock.util.EncryptionUtils`** (its class initialiser
   calls Mojang's discovery service and fails without internet). Use `auth.BedrockCrypto`.
4. **Codec registries** (item, block, camera preset definitions) are updated synchronously on the
   Netty thread by `network.bedrock.CodecRegistries`; translators never set them.
5. **Reference-counted Bedrock packets** (`LevelChunkPacket`, `SubChunkPacket`,
   `ResourcePackChunkDataPacket`...) are released after their translator returns: copy or `retain()`
   anything handed to another thread.
6. **One translator per packet class.** The registry rejects duplicates. Stick to the ownership table
   below; a subsystem that needs a packet owned by another calls that owner's state methods.
7. **Lossless items.** Java stacks built from Bedrock items carry `custom_data {jtb:{...}}` with the
   exact Bedrock item, so `ItemTranslator.toBedrock` can rebuild it.
8. **Bedrock positions:** players (local and others) are at **eye level** (+1.62) in `MovePlayer`,
   `AddPlayer`, `PlayerAuthInput`, `Respawn`; Java uses feet positions.
9. **PowerNukkitX specifics** worth remembering: hashed block runtime ids, server-authoritative
   movement (`PlayerAuthInputPacket` every tick) and inventory (`ItemStackRequest`), chunk radius
   ≥ 5 needed to spawn (56 chunks), placement only when `clientInteractPrediction == SUCCESS`,
   instant-break needs `START_DESTROY_BLOCK` + `PREDICT_DESTROY_BLOCK` in the same input, chat
   2/s and commands 10/s rate limits (queue them), `NetworkStackLatency` must be echoed.

## Cross-subsystem contracts

| Class | Owner | Used by |
|---|---|---|
| `block.BlockMapper` (`blockDisplay`, `blockDisplays`: custom blocks drawn by entities) | block | world, player, entity, inventory |
| `world.WorldState`, `world.ItemFrame` (`itemFrame(javaEntityId)`: item frame entities) | world | player, sound |
| `player.PlayerState` | player | entity (local player hooks), world, inventory |
| `player.BlockBreaking` (`isDigging`, `onServerCrack`) | player | sound |
| `entity.EntityTracker` | entity | player, ui, sound, world (item frames: `allocateJavaId`, and the `EntityDefinitions` data layouts) |
| `inventory.InventoryState` (`retranslate` once the pack is loaded) | inventory | player, resourcepack |
| `item.ItemTranslator`, `item.ItemRegistry` | inventory | entity (equipment, item entities), ui, world (item frames) |
| `text.TextTranslator`, `text.BedrockLang` | chat | all |
| `network.java.JavaConfiguration` | core | world (registries, ready), resourcepack |
| `network.java.JavaChannels` (client mod channels) | core | entity, skin |
| `skin.SignedSkins` (Mojang-signed textures of Bedrock skins: `cached` then `resolve`) | core | entity (player list) |
| `skin.SkinMods` (full skins for ViaBedrockUtility / BedrockSkinUtility) | core | entity (player list, PlayerSkin) |
| `sound.SoundState`, `sound.BlockCracking`, `sound.CustomSounds`, `sound.SoundTranslators#playLevelSound` | sound | player, world, entity, resourcepack |
| `resourcepack.ResourcePackState`, `resourcepack.ConversionArtifacts`, `resourcepack.JavaAssetNames` | resourcepack | inventory (item models, equipment), sound (custom events), entity (custom models), block (carrier states), chat/ui (glyphs, lang) |

The join translator (world) calls, in order: `BlockMapper.onStartGame`, `EntityTracker.onStartGame`,
`PlayerState.onStartGame`, then builds the Java login packet.

## Item frames (world, player)

Bedrock item frames are blocks (`minecraft:frame`, `minecraft:glow_frame`, `facing_direction` 0-5) with a block
entity (`Item`, `ItemRotation`); Java item frames are entities. The block shows as air (water when waterlogged).

- **Display (world).** `BlockMapper.itemFrame(runtimeId)` tells frame blocks apart (facing, glow bit) on any
  thread. The chunk pipeline finds them while decoding (layer 0 palettes) and keeps them per column; once the
  column is sent, `world.ItemFrames` spawns an `item_frame` / `glow_item_frame` at the block (AddEntity data and
  `DIRECTION` = the facing, `ITEM` = `ItemTranslator.toJava` of the block entity's item, filled maps included,
  `ROTATION` = `ItemRotation`: degrees in a float on Bedrock, the 0-7 step in a byte on PowerNukkitX). Block
  changes spawn, replace or remove entities, block entity updates send the entity data that changed, a
  forgotten chunk removes its frames; dimension changes and server switches only forget them (the Java client
  dropped its entities). Java ids come from `EntityTracker.allocateJavaId()`.
- **Interactions (player).** `ServerboundAttack`, `ServerboundInteract` and `ServerboundPickItemFromEntity` on an
  id for which `WorldState.itemFrame(id)` is not null act on the block, like a Bedrock client: attack =
  `START_DESTROY_BLOCK` on the frame's face (PowerNukkitX treats it as a left click: the item drops, or vanishes
  in creative) then `ABORT` while the frame holds an item; an empty frame is broken (`START + PREDICT` in
  creative, else a dig the proxy holds for the break time the server's `BLOCK_START_BREAK` announces, 8 ticks
  until it does). Use = the click on the block's front face (`START_ITEM_USE_ON`, `ItemUse(PLACE)` with the held
  item and the hit point as click position, `STOP_ITEM_USE_ON`), predicted `FAILURE` so it never places the held
  block. Pick = `BlockPickRequest` at the frame. PowerNukkitX ignores `ItemFrameDropItemPacket`.
- **Sounds.** The server's `SOUND_ITEMFRAME_*` level events map to the Java `entity.item_frame.*` sounds.
- **Limitations.** A frame placed with `/setblock` has no block entity on PowerNukkitX and takes no item;
  in creative with a sword the server ignores `START_DESTROY_BLOCK`, so the item stays; Java's separate glow frame
  sounds are not used (Bedrock has one set). End-to-end coverage: scenario `item_frame` (operator needed).

## Custom blocks without carrier (block, world, resourcepack, player)

Java has about 1 516 safe carrier states (opaque full cubes: `note_block`, the mushroom blocks). Servers like
Linesia define far more looks (2 447 blocks, 32 502 states, 23 640 looks), so:

- **Allocation (block).** `CustomBlockComponents.appearanceKey` is what a state looks like (geometry, material
  instances, transformation, unit cube, light, after the matching permutations). States of one block with the
  same key share a carrier. `BlockMapper` hands carriers to the blocks with the fewest looks first (decoration),
  then in definition order; deterministic, so cached packs stay valid. A state without carrier gets a display
  number (`CustomBlockDisplay`, one per look of a block, rotation from `minecraft:transformation`) and the Java
  state `barrier` (`structure_void` when its collision box is disabled). `BlockMapper.blockDisplay(runtimeId)`
  (any thread) and `blockDisplays()` expose them; their Java state never maps back to the custom block, and keeps
  its vanilla light.
- **Pack (resourcepack).** Displays are published with the carriers as `CustomBlockCarrier` entries whose
  `javaBlock` is `CustomBlockCarrier.DISPLAY` (`javaProperties.id`): the converter writes the block model and
  the item definition `JavaAssetNames.blockDisplayModel(id)` (`jtb:block_display/<id>`);
  `ConversionArtifacts.hasBlockDisplay(id)`.
- **World.** `world.BlockDisplays`, driven by `ChunkManager` like the item frames: an `item_display` at the block
  centre holding paper with that item model, yaw `y + 180` and pitch `x` (an item display turns its item half a
  turn), spawned with its column, replaced or removed with its block, removed with its chunk.
- **Breaking (player).** The Java client never finishes digging a barrier: `BlockBreaking` runs those digs like
  empty item frames (`CONTINUE` every tick, the end after the time of the server's crack event); the sound
  module draws their cracks (`drawsOwnCracks`).

## Converted pack and translated stacks (resourcepack, inventory)

Stacks the server sends while the Java client is still configuring are translated before the client loaded
the pack, without their item models. `ResourcePackState` calls `InventoryState.retranslate()` on
`SUCCESSFULLY_LOADED`, so the inventory sent at game start is translated again. Bump
`BedrockToJavaConverter.VERSION` whenever the conversion output changes. Client entity definitions in the
`minecraft` namespace that are not vanilla entities (`minecraft:npc_sylven`) are custom entities and get a model.

## Data-driven screens (form)

`DataStore` keeps the update counters the vanilla client keeps: the property version (1 until a
`DataStoreChange` replaces the property) and one version per path, advanced by the server's updates and the
proxy's (`layout.2.toggled` and `layout[2].toggled` are one path). PowerNukkitX ignores a client update whose
path version is not the next one.

## Inventories and items (inventory)

- **Server-confirmed mirror.** `InventoryState` mirrors every Bedrock container the player sees (inventory,
  armour, off hand, UI slots with the cursor and the crafting grids, the open window) with stack network ids.
  A Java click is replayed on the mirror like the vanilla server does (`ClickHandler`: pickup, shift-click per
  menu, number keys, drops, drags, double click, creative clone) and sent as `ItemStackRequest`s; the mirror
  only changes when the server answers SUCCESS, and every slot the click touched is sent to Java again from
  the mirror once all answers are in (Java's `HashedStack` guesses are ignored). Plain moves are one request
  each: PowerNukkitX applies a request's actions one by one and never rolls back the earlier ones. Java
  actions that arrive while requests are in flight wait for the answers.
- **Lossless items.** Java stacks carry `custom_data {jtb:{id, damage?, block?, tag?, can_place?, can_break?,
  blocking?}}`: `ItemTranslator.toBedrock` rebuilds the exact Bedrock item (NBT included) from it, and falls
  back to the mapping tables for stacks without it (Java creative tabs). Stacks nested in another item
  (container contents, charged projectiles) are display-only and carry no identity.
- **Custom (component-based) items** are shown as a vanilla base item (`paper`; `bow` for shooters, `snowball`
  for throwables) with `item_name` (Bedrock name key through `BedrockLang`, or `minecraft:display_name`),
  `max_stack_size`, `max_damage`, `food` + `consumable`, `equippable`, `tool`, `use_cooldown` (group named
  after the Bedrock cooldown category, like `PlayerStartItemCooldown`) and glint, plus the **`item_model`**
  (`ConversionArtifacts.itemModel(id)`, `jtb:item/<namespace>/<path>`) and the armour equipment asset
  (`equipmentAsset(id)`) of the converted resource pack, added per stack only when the client loaded the
  pack: without it the base item keeps its own look instead of a missing model.
- **Map items.** A filled map's `map_id` is `ui.MapState.javaMapId(map_uuid)` (the ui module allocates the
  Java ids and asks the server for the map content).
- **Respawns.** A Java `ClientboundRespawn` empties the client's inventory. The inventory resends everything on
  `WorldState.onDimensionChange` and whenever the server sends a whole player container (PowerNukkitX does
  after a death); `InventoryState.resendAll()` is there for any other case. `WorldState.bedrockBlockEntityAt`
  gives container titles (custom names, ender chest, barrel, shulker box) and lectern books.
- **Recipes.** Java never computes results: the proxy matches the crafting grids against `CraftingData`
  (shaped with mirroring, shapeless, item tags, molang tag queries) and shows the result; taking it sends
  `CRAFT_RECIPE` + `CONSUME` + the transfer out of the created output in one request. The stonecutter recipe
  list and the item sets Java menus filter on (furnace / smithing / brewing inputs) are sent with
  `ClientboundUpdateRecipes`.
- **PowerNukkitX specifics.** It does not give back what is left in crafting grids and menu inputs when a
  screen closes: the proxy moves it back to the inventory (or drops it) before closing, like the Bedrock
  client. `CRAFT_RECIPE` consumes the grid itself, `CRAFT_CREATIVE` fills the destination with a full stack,
  `DESTROY` only works in creative mode (and for beacon payments), multi recipes (`MULTI`, e.g. map cloning)
  cannot be used (the server fails decoding them). Dispensers and droppers placed with `/setblock` have no
  block entity and do not open; `/setblock grindstone` defaults to a hanging grindstone, which breaks
  without support.
- **Tables** are generated by `tools/gen_item_mappings.py` into `src/main/resources/mappings/items/` (sources
  in `mappings/SOURCES.md`). End-to-end coverage: scenarios `inventory` and `inventory_menus` (operator
  needed).

## Sounds, particles and level events (sound)

- **Resource pack sounds.** A Bedrock sound name with no vanilla Java equivalent (PlaySound, StopSound,
  music, pack entity sounds) plays as the Java sound event **`jtb:<name>`**, where `<name>` is the Bedrock
  name lower-cased with every character outside `[a-z0-9_.-/]` replaced by `_`
  (`Mob.Boss:Roar` -> `jtb:mob.boss_roar`). The converted pack declares them in `assets/jtb/sounds.json`;
  always build the id with `sound.CustomSounds.path(name)` / `key(name)` so both sides agree. The pack's
  `sound_definitions.json` entries go to `SoundState.registerPackSound(name, category, maxDistance, minDistance)` and its
  `sounds.json` entity events (custom entities) to `SoundState.registerPackEntitySound(...)`.
- **Local predictions.** The Java client plays some effects itself: place sounds, break particles and
  sound, doors / trapdoors / fence gates / buttons, axe stripping... PowerNukkitX echoes them back to the
  player who caused them. The player module calls `SoundState.onLocalBlockPlaced`, `onLocalBlockBroken` or
  `suppressSoundsAt(x, y, z)` when it forwards such an action, so the echo is not played twice.
- **Block cracking.** `sound.BlockCracking` turns Bedrock's per-tick crack progress (`BLOCK_START_BREAK`
  data = 65535 / ticks to break) into Java `BlockDestruction` stages, one breaker id per animation. The
  player module may drive it for the local player too.
- **Weather.** The rain and thunder levels ramp by 0.01 per tick like on a Java server. The Java client
  forgets them on respawn / dimension change: the world module calls `SoundState.resendWeather()` then.
- **Note blocks.** The `note` LevelSoundEvent carries the exact instrument and key, so the sound module
  plays the note (sound + coloured particle). Do not also forward note block `BlockEvent`s as Java block
  events: the Java client would play the default instrument of its block state a second time.
- **Mob sounds.** Bedrock clients play mob ambient and step sounds themselves; Java clients expect the
  server to. The entity module can synthesise them with `SoundTranslators.playLevelSound(session,
  SoundEvent.AMBIENT, position, -1, "minecraft:zombie", baby, false)` (same lookup as a LevelSoundEvent).
- **Particles.** MCProtocolLib 26.3 cannot write `GEYSER*`, `DRAGON_BREATH`, `EFFECT`, `INSTANT_EFFECT`,
  `TINTED_LEAVES` and `FLASH` particles (no writer branch: the client disconnects). Send particles through
  `sound.JavaEffects.particle`, which refuses them; the mapping tables use look-alikes instead.
- **Tables** are generated by `tools/gen_sound_mappings.py` into `src/main/resources/mappings/sound/`
  (sources in `mappings/SOURCES.md`); `SoundMappingsTest` checks their coverage.

## Server assets in the converted resource pack

The server's Bedrock packs become one Java pack (`resourcepack.convert.BedrockToJavaConverter`),
pushed during the configuration phase. Translators reference its assets by the names of
`resourcepack.JavaAssetNames` (pure functions, all in the `jtb` namespace):

| Asset | Name | Defined by |
|---|---|---|
| custom item model (`item_model`) | `itemModel(id)` = `jtb:item/<namespace>/<path>` | icon of the item registry entry (`item_properties."minecraft:icon"`), custom block items too |
| custom armour (`equippable.asset_id`) | `equipmentAsset(id)` = `jtb:<namespace>/<path>` | armour attachable of a wearable item (64 x 32 sheet) |
| custom sound event (`CustomSound`) | `soundEvent(name)` = `jtb:<name, lower case, invalid chars -> _>` | `sound_definitions.json` events whose `.ogg` a server pack ships (vanilla names re-defined with new files included) |
| custom entity model (`item_display` + paper) | `entityModel(id, variant)` = `jtb:entity/<namespace>/<path>/<geometry>_<texture>` | client entity definition, bones baked into one model |
| custom block model | `customBlockModel(id, n)` = `jtb:block/custom/<namespace>/<path>[_n]` | written into the carrier blockstate, translators use the carrier state id |
| glyphs | private-use code points `U+E000`-`U+F8FF` of the default font | `font/glyph_XX.png` |
| translations | `assets/jtb/lang/<locale>.json` + `BedrockLang` (fed per language) | `texts/*.lang` |

Rules for translators:

- Use an asset only when `session.get(ResourcePackState.KEY).artifacts()` is non-null **and** lists
  it (`itemModel(id)`, `soundEvent(name)`, `entityModel(id)`, `equipmentAsset(id)`, `hasGlyph(cp)`).
  `artifacts()` is null until the client reported `SUCCESSFULLY_LOADED` (declined or failed pack:
  fall back to vanilla items/sounds), `isLoaded()` tells the same.
- Entity models: spawn an `ITEM_DISPLAY` holding `minecraft:paper` with `item_model =
  variant.itemModel()`, metadata `SCALE = variant.scale() x entity scale` and `TRANSLATION = (0,
  variant.translationY() x entity scale, 0)`, the entity's yaw; the model faces north (-Z) like the
  Bedrock geometry.
- Custom blocks: while handling `StartGame` (`BlockMapper.onStartGame`), the block subsystem passes
  every custom block state with the Java carrier state it allocated to
  `ResourcePackState.setCustomBlockCarriers(List<CustomBlockCarrier>)` (Bedrock identifier, state
  values, block definition NBT from `blockProperties`, carrier block and its full property map).
  Supported carriers (their vanilla blockstate is rewritten with every state): `note_block`,
  `tripwire`, the three mushroom blocks, `chorus_plant` and the leaves
  (`mappings/resourcepack/carrier_blocks.json`). Allocate deterministically so cached packs stay valid.
- The pack waits for the item registry: `network.bedrock.BedrockPacketDispatcher` calls
  `ResourcePackState.onItemRegistry()` right after `ItemRegistry.load`.

## Packet ownership

Bedrock → Java (server packets):

| Module | Packets |
|---|---|
| world | StartGame, BiomeDefinitionList, DimensionData, LevelChunk, SubChunk, ChunkRadiusUpdated, NetworkChunkPublisherUpdate, UpdateBlock, UpdateBlockSynced, UpdateSubChunkBlocks, BlockActorData, BlockEvent, OpenSign, SetTime, SyncWorldClocks, GameRulesChanged, ChangeDimension, SetSpawnPosition, SetDifficulty, UpdateBlockProperties, PlayerFog, VoxelShapes, JigsawStructureData, TickingAreasLoadStatus |
| player | PlayStatus (PLAYER_SPAWN), MovePlayer, CorrectPlayerMovePrediction, ServerPlayerPostMovePosition, MovementEffect, UpdateAbilities, UpdateAdventureSettings, AdventureSettings, SetPlayerGameType, UpdatePlayerGameType, SetDefaultGameType, Respawn, SetHealth, DeathInfo, SetLastHurtBy, UpdateClientInputLocks, PlayerStartItemCooldown, CompletedUsingItem, NetworkStackLatency, ShowCredits, ClientboundControlSchemeSet, SetPlayerInventoryOptions |
| entity | AddActor, AddPlayer, AddItemActor, AddPainting, RemoveActor, TakeItemActor, MoveActorAbsolute, MoveActorDelta, SetActorData, SetActorMotion, SetActorLink, ActorEvent, MobEffect, UpdateAttributes, Animate, AnimateEntity, MobEquipment, MobArmorEquipment, PlayerList, PlayerSkin, SpawnExperienceOrb, SyncActorProperty, AvailableActorIdentifiers, Emote, EmoteList, ChangeMobProperty, MotionPredictionHints, AddVolumeEntity, RemoveVolumeEntity |
| inventory | InventoryContent, InventorySlot, InventoryTransaction, ContainerOpen, ContainerClose, ContainerSetData, ContainerRegistryCleanup, PlayerHotbar, ItemStackResponse, CreativeContent, CraftingData, UnlockedRecipes, PlayerEnchantOptions, UpdateTrade, UpdateEquip, GuiDataPickItem, TrimData, HurtArmor, PlayerArmorDamage |
| chat | Text, CommandOutput, AvailableCommands, UpdateSoftEnum, SetCommandsEnabled |
| form | ModalFormRequest, ServerSettingsResponse, ClientboundCloseForm, NpcDialogue, ClientboundDataDrivenUIShowScreen, ClientboundDataDrivenUICloseScreen, ClientboundDataDrivenUICloseAllScreens, ClientboundDataDrivenUIReload, ClientboundDataStore |
| ui | SetTitle, ToastRequest, BossEvent, SetDisplayObjective, SetScore, RemoveObjective, SetScoreboardIdentity, SetHud, CameraInstruction, CameraShake, Camera, CameraPresets, CameraAimAssist, CameraAimAssistPresets, CameraAimAssistActorPriority, CameraSpline, ClientboundMapItemData, OnScreenTextureAnimation, AwardAchievement, LocatorBar, PlayerLocation, ShowProfile, ClientboundTextureShift |
| sound | PlaySound, StopSound, LevelSoundEvent, LevelEvent, LevelEventGeneric, SpawnParticleEffect, RecordStarted, ClientboundUpdateSoundData |
| core | NetworkSettings, ServerToClientHandshake, PlayStatus (login), ResourcePack*, ItemRegistry, Disconnect, Transfer |

Java → Bedrock (client packets):

| Module | Packets |
|---|---|
| world | ServerboundClientInformation (view distance → RequestChunkRadius; locale), ServerboundChunkBatchReceived, ServerboundSignUpdate |
| player | ServerboundMovePlayerPos/PosRot/Rot/StatusOnly, ServerboundAcceptTeleportation, ServerboundPlayerInput, ServerboundPlayerCommand, ServerboundPlayerAction, ServerboundUseItemOn, ServerboundUseItem, ServerboundInteract, ServerboundSwing, ServerboundPlayerAbilities, ServerboundClientCommand, ServerboundClientTickEnd, ServerboundPlayerLoaded, ServerboundPickItemFromBlock, ServerboundPickItemFromEntity, ServerboundMoveVehicle, ServerboundPaddleBoat, ServerboundTeleportToEntity, ServerboundAttack, ServerboundPunch, ServerboundChangeGameMode, ServerboundSpectatorAction |
| inventory | ServerboundContainerClick, ServerboundContainerClose, ServerboundContainerButtonClick, ServerboundContainerSlotStateChanged, ServerboundSetCreativeModeSlot, ServerboundSetCarriedItem, ServerboundRenameItem, ServerboundSelectTrade, ServerboundSetBeacon, ServerboundEditBook, ServerboundPlaceRecipe, ServerboundRecipeBookChangeSettings, ServerboundRecipeBookSeenRecipe, ServerboundSelectBundleItem |
| chat | ServerboundChat, ServerboundChatCommand, ServerboundChatCommandSigned, ServerboundChatAck, ServerboundChatSessionUpdate, ServerboundCommandSuggestion |
| form | ServerboundCustomClickAction |
| core | ServerboundCustomPayload `minecraft:register` / `minecraft:unregister` (other channels go to the translators) |
