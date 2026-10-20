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
| `item.CustomItems.modelKey` (Java `item_model` of custom items) | inventory | resourcepack |
| `text.TextTranslator`, `text.BedrockLang` | chat | all |
| `network.java.JavaConfiguration` | core | world (registries, ready), resourcepack |

The join translator (world) calls, in order: `BlockMapper.onStartGame`, `EntityTracker.onStartGame`,
`PlayerState.onStartGame`, then builds the Java login packet.

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
  for throwables) with `item_name`, `max_stack_size`, `max_damage`, `food` + `consumable`, `equippable`,
  `tool`, `use_cooldown`, glint, and **`item_model` = `jtb:<namespace>/<path>`**: the Bedrock identifier
  lower-cased, every character outside `[a-z0-9_.-]` replaced by `_` (`MyMod:Ruby Sword` ->
  `jtb:mymod/ruby_sword`; no namespace -> `minecraft`). Always build it with
  `item.CustomItems.modelKey(identifier)`. The converted resource pack provides the item model definition
  `assets/jtb/items/<namespace>/<path>.json`.
- **Map items.** `ItemRegistry.javaMapId(bedrockMapUuid)` allocates the Java map id of a Bedrock map uuid;
  the ui module uses the same method for `ClientboundMapItemData`.
- **Hooks for other modules.** After sending `ClientboundRespawn` (death, dimension change) the Java client
  has an empty inventory: call `InventoryState.resendAll()`. `WorldState.blockEntityAt(x, y, z)` (world) gives
  container titles (custom names, ender chest, barrel, shulker box), double chests and lectern books.
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

## Packet ownership

Bedrock → Java (server packets):

| Module | Packets |
|---|---|
| world | StartGame, BiomeDefinitionList, DimensionData, LevelChunk, SubChunk, ChunkRadiusUpdated, NetworkChunkPublisherUpdate, UpdateBlock, UpdateBlockSynced, UpdateSubChunkBlocks, BlockActorData, BlockEvent, OpenSign, SetTime, SyncWorldClocks, GameRulesChanged, ChangeDimension, SetSpawnPosition, SetDifficulty, UpdateBlockProperties, PlayerFog, VoxelShapes, JigsawStructureData, TickingAreasLoadStatus |
| player | PlayStatus (PLAYER_SPAWN), MovePlayer, CorrectPlayerMovePrediction, ServerPlayerPostMovePosition, MovementEffect, UpdateAbilities, UpdateAdventureSettings, AdventureSettings, SetPlayerGameType, UpdatePlayerGameType, SetDefaultGameType, Respawn, SetHealth, DeathInfo, SetLastHurtBy, UpdateClientInputLocks, PlayerStartItemCooldown, CompletedUsingItem, NetworkStackLatency, ShowCredits, ClientboundControlSchemeSet, SetPlayerInventoryOptions |
| entity | AddActor, AddPlayer, AddItemActor, AddPainting, RemoveActor, TakeItemActor, MoveActorAbsolute, MoveActorDelta, SetActorData, SetActorMotion, SetActorLink, ActorEvent, MobEffect, UpdateAttributes, Animate, AnimateEntity, MobEquipment, MobArmorEquipment, PlayerList, PlayerSkin, SpawnExperienceOrb, SyncActorProperty, AvailableActorIdentifiers, Emote, EmoteList, ChangeMobProperty, MotionPredictionHints, AddVolumeEntity, RemoveVolumeEntity |
| inventory | InventoryContent, InventorySlot, InventoryTransaction, ContainerOpen, ContainerClose, ContainerSetData, ContainerRegistryCleanup, PlayerHotbar, ItemStackResponse, CreativeContent, CraftingData, UnlockedRecipes, PlayerEnchantOptions, UpdateTrade, UpdateEquip, GuiDataPickItem, TrimData, HurtArmor, PlayerArmorDamage |
| chat | Text, CommandOutput, AvailableCommands, UpdateSoftEnum, SetCommandsEnabled |
| form | ModalFormRequest, ServerSettingsResponse, ClientboundCloseForm, NpcDialogue, ClientboundDataDrivenUIShowScreen, ClientboundDataDrivenUICloseScreen, ClientboundDataDrivenUIReload |
| ui | SetTitle, ToastRequest, BossEvent, SetDisplayObjective, SetScore, RemoveObjective, SetScoreboardIdentity, SetHud, CameraInstruction, CameraShake, Camera, CameraPresets, CameraAimAssist, CameraAimAssistPresets, CameraSpline, ClientboundMapItemData, OnScreenTextureAnimation, AwardAchievement, LocatorBar, PlayerLocation, ShowProfile, ClientboundTextureShift |
| sound | PlaySound, StopSound, LevelSoundEvent, LevelEvent, LevelEventGeneric, SpawnParticleEffect, RecordStarted, ClientboundUpdateSoundData |
| core | NetworkSettings, ServerToClientHandshake, PlayStatus (login), ResourcePack*, ItemRegistry, Disconnect, Transfer |

Java → Bedrock (client packets):

| Module | Packets |
|---|---|
| world | ServerboundClientInformation (view distance → RequestChunkRadius; locale), ServerboundChunkBatchReceived, ServerboundSignUpdate |
| player | ServerboundMovePlayerPos/PosRot/Rot/StatusOnly, ServerboundAcceptTeleportation, ServerboundPlayerInput, ServerboundPlayerCommand, ServerboundPlayerAction, ServerboundUseItemOn, ServerboundUseItem, ServerboundInteract, ServerboundSwing, ServerboundPlayerAbilities, ServerboundClientCommand, ServerboundClientTickEnd, ServerboundPlayerLoaded, ServerboundPickItemFromBlock, ServerboundPickItemFromEntity, ServerboundMoveVehicle, ServerboundPaddleBoat, ServerboundTeleportToEntity |
| inventory | ServerboundContainerClick, ServerboundContainerClose, ServerboundContainerButtonClick, ServerboundContainerSlotStateChanged, ServerboundSetCreativeModeSlot, ServerboundSetCarriedItem, ServerboundRenameItem, ServerboundSelectTrade, ServerboundSetBeacon, ServerboundEditBook, ServerboundPlaceRecipe, ServerboundRecipeBookChangeSettings, ServerboundRecipeBookSeenRecipe, ServerboundSelectBundleItem |
| chat | ServerboundChat, ServerboundChatCommand, ServerboundChatCommandSigned, ServerboundChatAck, ServerboundChatSessionUpdate, ServerboundCommandSuggestion |
| form | ServerboundCustomClickAction |
