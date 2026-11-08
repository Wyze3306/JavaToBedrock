# Making a Bedrock server work well for Java players

JavaToBedrock translates what a Bedrock server sends, so most servers work as they are. A few things
only a Bedrock client can do; this page says what a server (or plugin) can do so Java players get
the same experience.

## Telling Java players apart

Every Java player joins with this client data in the login chain:

| Field | Value |
|---|---|
| `DeviceModel` | `JavaToBedrock` |
| `DeviceOS` | `8` (Windows) |
| `ThirdPartyName` | the Java account name |

On PowerNukkitX: `"JavaToBedrock".equals(player.getClientChainData().getDeviceModel())`. With the
`trusted` mode and a PowerNukkitX that has *java-bridge*, `player.isJavaEditionPlayer()` and
`player.getJavaIdentity()` also tell it, with the Mojang-verified identity.

## HUD and scoreboards

Java cannot run resource pack JSON UI. A sidebar, boss bar or title whose text is data for a pack's UI
(markers, fixed-width fields, glyph codes) shows as raw text on Java.

- Send Java players **plain, readable lines**: one piece of information per sidebar line, `§` colours
  and pack glyphs are fine (glyph pages are converted).
- The sidebar scores (red numbers on Java) can be hidden for everyone with
  `features.scoreboard-numbers: false` in the proxy's `config.yml`.
- Keep the scoreboard to 15 lines; Java shows the objective's display name as its title.

## Menus

- **Forms are the way to go.** Simple and modal forms open as **chest menus** on Java (one item per
  button, the button image as the item's icon when it is a texture of the server's packs, the content
  in a book), custom forms (inputs, toggles, sliders, dropdowns), NPC dialogues and data-driven screens
  as native Java dialogs. `features.chest-menus: false` shows every form as a dialog.
- Button images: use `path` images of your packs (`textures/ui/...`, `textures/items/...`,
  `textures/custom/...`, `textures/blocks/...`, square, 256 px at most). `url` images show as paper.
- Screens drawn only by JSON UI (custom chest layouts routed by the container title, HUD screens)
  have no Java equivalent: open a form, or a plain container, for Java players.

## Blocks and entities

- Java has about 1,500 block states a pack can redraw. Custom blocks beyond that are shown with display
  entities: they look right but do not emit light on Java. Fewer distinct looks (states that only differ
  by data the model ignores share one) keep more blocks on real block states.
- Custom entities are drawn with their converted model, without animations or animation-driven parts.

## Anti-cheats

Java and Bedrock clients do not move or swing the same way. The proxy sends what a Bedrock client
would (movement every tick, arm swings at most every 4 ticks), but checks tuned to the exact timing of
Bedrock touch or controller input may need to skip Java players.
