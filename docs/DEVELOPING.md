# Developing JavaToBedrock

Read `docs/ARCHITECTURE.md` first. This file holds the rules every subsystem follows and who owns
which packet.

## Build & run

```bash
./gradlew build                # compile, tests, shadow jar -> build/libs/JavaToBedrock.jar
java -jar build/libs/JavaToBedrock.jar        # writes config.yml on first start
```

Java 21. Dependencies: MCProtocolLib 26.3 (Java side), PowerNukkitX Protocol
`3.0.0.Beta7-Debug-SNAPSHOT` + CloudburstMC RakNet `2.0.0.CR4` (Bedrock side) — the exact versions the
PowerNukkitX server uses.

## End-to-end test

`src/test/java/.../e2e/HeadlessJavaClient` is a display-less Java 26.3 client: it logs in, answers the
configuration phase like the real client (known packs, downloads and SHA-1 checks the resource pack),
joins, acknowledges teleports and runs a scenario (`e2e/scenario/*Scenario`, chosen with
`-Dscenario=name`). Scenarios record `context.check(name, ok)`; one failed check fails the run.

```bash
./gradlew runHeadlessClient -Phost=127.0.0.1 -Pport=25565 -Pname=Tester -Pseconds=30
```

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
| `block.BlockMapper` | block | world, player, entity, inventory |
| `world.WorldState` | world | player, sound |
| `player.PlayerState` | player | entity (local player hooks), world, inventory |
| `entity.EntityTracker` | entity | player, ui, sound |
| `inventory.InventoryState` | inventory | player |
| `item.ItemTranslator`, `item.ItemRegistry` | inventory | entity (equipment, item entities), ui |
| `text.TextTranslator`, `text.BedrockLang` | chat | all |
| `network.java.JavaConfiguration` | core | world (registries, ready), resourcepack |
| `network.java.JavaChannels` (client mod channels) | core | entity, skin |
| `skin.SignedSkins` (Mojang-signed textures of Bedrock skins: `cached` then `resolve`) | core | entity (player list) |
| `skin.SkinMods` (full skins for ViaBedrockUtility / BedrockSkinUtility) | core | entity (player list, PlayerSkin) |
| `sound.SoundState`, `sound.BlockCracking`, `sound.CustomSounds`, `sound.SoundTranslators#playLevelSound` | sound | player, world, entity, resourcepack |
| `resourcepack.ResourcePackState`, `resourcepack.ConversionArtifacts`, `resourcepack.JavaAssetNames` | resourcepack | inventory (item models, equipment), sound (custom events), entity (custom models), block (carrier states), chat/ui (glyphs, lang) |

The join translator (world) calls, in order: `BlockMapper.onStartGame`, `EntityTracker.onStartGame`,
`PlayerState.onStartGame`, then builds the Java login packet.

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
