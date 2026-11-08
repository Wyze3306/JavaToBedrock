# JavaToBedrock

Play on a Minecraft **Bedrock** server (PowerNukkitX) with Minecraft **Java Edition**.

JavaToBedrock is a standalone proxy, the reverse of Geyser: Java 26.3 players join it like any Java
server, and it plays for them on a Bedrock 1.26.60 server, translating every packet both ways.

```
Java 26.3 client ──TCP──▶ JavaToBedrock ──NetherNet──▶ PowerNukkitX 1.26.60
```

## Features

- World, blocks, biomes, lighting, block entities, item frames
- Player movement, abilities, health, food, experience, effects, death and respawn
- Breaking, placing, using items, combat
- Entities, equipment, mounts, player list and skins
- Inventories, containers, crafting, creative mode, special menus
- Chat and commands with tab completion
- Button forms as chest menus with icons; other forms, NPC dialogues and data-driven screens as
  native Java dialogs
- Scoreboards, boss bars, titles, toasts, camera
- Sounds and particles
- Server resource packs (encrypted ones too) converted to a Java pack: textures, custom items, blocks,
  entities, armour, fonts, sounds and translations
- Several Bedrock servers behind one proxy, transfers, and a waiting room while the server restarts

## Quick start

1. Install **Java 21**.
2. Download `JavaToBedrock.jar` from the [releases](../../releases), or build it with `./gradlew build`.
3. Run `java -jar JavaToBedrock.jar` once: it writes `config.yml`.
4. Set the Bedrock server in `config.yml` (`bedrock.address`, `bedrock.port`) and restart.
5. In Minecraft Java 26.3, add the proxy as a server (port `25565` by default).

The Bedrock server's port must be reachable over **TCP** (NetherNet signalling). If players join from
the internet, also open the resource pack HTTP port (`8765` by default) or set
`resource-packs.public-url`.

## Authentication

| `bedrock.auth` | Use |
|---|---|
| `offline` | Private servers with `xboxAuth: false` in `pnx.yml` |
| `microsoft` | Any server: each player signs in once with a Microsoft account (a code is shown in game) |
| `trusted` | The proxy vouches for Mojang-verified Java players (needs PowerNukkitX with *java-bridge*) |

## Docker

```bash
docker build -t javatobedrock .
docker run -d -p 25565:25565 -p 8765:8765 -v ./jtb:/data \
  -e JTB_BEDROCK_ADDRESS=my.bedrock.server -e JTB_BEDROCK_AUTH=offline javatobedrock
```

Any `config.yml` option can be set with an environment variable: `JTB_` + its path in upper case
(`bedrock.address` → `JTB_BEDROCK_ADDRESS`). `docker/docker-compose.yml` runs PowerNukkitX with the
proxy in front of it.

## Limitations

- Bedrock JSON UI (custom HUDs and screens from resource packs) has no Java equivalent.
- Java has about 1,500 block states a pack can redraw. Custom blocks beyond that are drawn by display
  entities, without light emission.
- Custom entities are drawn with their converted model, without animations.

## Development

```bash
./gradlew build                                # build and unit tests
tools/e2e/run.sh path/to/powernukkitx.jar world 40   # end-to-end test against a real server
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/DEVELOPING.md`](docs/DEVELOPING.md).
Running a Bedrock server? [`docs/SERVERS.md`](docs/SERVERS.md) explains how to make it work well for
Java players (HUD, menus, custom blocks).

## License

GPL-3.0. Built on [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib),
[PowerNukkitX Protocol](https://github.com/PowerNukkitX/Protocol),
[CloudburstMC Network](https://github.com/CloudburstMC/Network),
[ViaBedrock](https://github.com/RaphiMC/ViaBedrock), [Geyser](https://github.com/GeyserMC/Geyser) and
[MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth). Not affiliated with Mojang or Microsoft.
