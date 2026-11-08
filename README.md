# JavaToBedrock

**Jouez sur un serveur Minecraft Bedrock PowerNukkitX avec Minecraft Java Edition.**

JavaToBedrock est un proxy autonome : les joueurs **Java Edition 26.3** se connectent au proxy comme
à n'importe quel serveur Java, et le proxy les fait jouer sur un serveur **Bedrock 1.26.60** en
traduisant chaque paquet dans les deux sens — monde, blocs, entités, inventaires, formulaires,
commandes, scoreboard, sons, packs de ressources… C'est le chemin inverse de Geyser.

```
Client Java 26.3 ──TCP──▶ JavaToBedrock ──NetherNet (WebRTC)──▶ Serveur Bedrock (PowerNukkitX 1.26.60)
```

Le proxy parle **uniquement NetherNet**, le transport de PowerNukkitX depuis 1.26.60 : signalisation
HTTP sur le port du serveur (TCP), puis canaux de données WebRTC (bibliothèque native libdatachannel,
incluse pour Linux glibc et musl, Windows et macOS, en x64 et arm64). Les serveurs RakNet (BDS, PowerNukkitX 1.26.50 et
avant) ne sont plus pris en charge.

> Statut : complet pour un serveur PowerNukkitX avec ses plugins et ses packs (vérifié de bout en
> bout sur Linesia, voir [État des fonctionnalités](#état-des-fonctionnalités) et les
> [limites](#limites)).

## Démarrage rapide

1. **Java 21** est requis.
2. Construire (ou télécharger) le jar :
   ```bash
   ./gradlew build          # -> build/libs/JavaToBedrock.jar
   ```
3. Lancer une première fois pour générer `config.yml` :
   ```bash
   java -jar JavaToBedrock.jar
   ```
4. Dans `config.yml`, indiquer l'adresse du serveur Bedrock (`bedrock.address` / `bedrock.port` : le
   port du serveur, joignable en TCP pour la signalisation NetherNet) et choisir le mode d'authentification (`bedrock.auth`, voir ci-dessous). Redémarrer.
5. Dans Minecraft Java 26.3 : *Multijoueur → Ajouter un serveur →* l'adresse du proxy
   (port `25565` par défaut).

Commandes de la console du proxy :

| Commande | Effet |
|---|---|
| `list` | joueurs, serveur Bedrock de chacun, ping Java et Bedrock, salle d'attente |
| `servers` | état de chaque serveur Bedrock (MOTD, version, joueurs) |
| `send <joueur\|*> <serveur>` | envoie un joueur (ou tous) sur un autre serveur Bedrock, sans déconnexion |
| `stop` | arrête le proxy |

### Avec Docker

```bash
docker build -t javatobedrock .
docker run -d -p 25565:25565 -p 8765:8765 -v ./jtb:/data \
  -e JTB_BEDROCK_ADDRESS=mon.serveur.bedrock -e JTB_BEDROCK_AUTH=offline javatobedrock
```

`docker/docker-compose.yml` lance un serveur PowerNukkitX et le proxy devant lui.
Chaque option de `config.yml` peut être remplacée par une variable d'environnement `JTB_` + son
chemin en majuscules, points et tirets remplacés par `_` : `bedrock.address` → `JTB_BEDROCK_ADDRESS`,
`resource-packs.public-url` → `JTB_RESOURCE_PACKS_PUBLIC_URL`. Une variable définie, même vide,
remplace `config.yml`. Sous Kubernetes, évitez un service nommé `jtb-…` (ses variables
`JTB_…_PORT=tcp://…` seraient lues comme des options) ou mettez `enableServiceLinks: false`.

## Authentification côté Bedrock

| Mode (`bedrock.auth`) | Pour quoi | Ce qu'il faut côté serveur |
|---|---|---|
| `offline` | Serveur privé / LAN | `xbox-auth` désactivé (`settings.xboxAuth: false` dans `pnx.yml`) |
| `microsoft` | N'importe quel serveur, y compris avec Xbox Live | Rien : à la première connexion, le joueur voit un code dans un dialogue Java et se connecte à son compte Microsoft ; c'est mémorisé ensuite |
| `trusted` | **Recommandé avec PowerNukkitX** : garder `xbox-auth` pour les joueurs Bedrock et accepter les joueurs Java vérifiés par Mojang | PowerNukkitX avec le support *java-bridge* (ci-dessous) |

### Mode `trusted` avec PowerNukkitX

Le proxy signe l'identité Java de chaque joueur (vérifiée par Mojang, `java.online-mode: true`)
avec sa propre clé ; PowerNukkitX vérifie la signature et la liaison à la clé de session
(anti-rejeu), puis accepte le joueur même avec `xbox-auth` activé.

> Le support *java-bridge* vit dans le fork PowerNukkitX de JavaToBedrock (branche
> `jtb-java-bridge`, 1.26.50) : il reste à le porter sur PowerNukkitX 1.26.60. D'ici là, utilisez
> `offline` (serveur sans xbox-auth) ou `microsoft`.

1. Mettre `bedrock.auth: trusted` dans `config.yml` du proxy et démarrer : la console affiche la
   **clé publique** du proxy (fichier `jtb-proxy-key.pem`, à garder secret).
2. Dans `pnx.yml` de PowerNukkitX :
   ```yaml
   network-settings:
     java-bridge:
       enabled: true
       proxyPublicKeys:
       - "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE..."   # la clé affichée par le proxy
       requireOnlineMode: true
       maxClockSkewSeconds: 60
       namePrefix: "."
   ```
   (PowerNukkitX écrit cette section avec ses valeurs par défaut au premier démarrage ; les clés
   sont en camelCase comme le reste de `pnx.yml`.) Le chiffrement réseau de PNX doit rester activé
   (`network-settings.networkEncryption: true`, valeur par défaut).
3. Les pseudos des joueurs Java commencent par `namePrefix` (« . » par défaut, caractère absent des
   gamertags Xbox) : sans lui, un compte Java pourrait prendre le nom, les droits et les données d'un
   joueur Bedrock. Le proxy l'ajoute de lui-même en mode `trusted` (`bedrock.username-prefix: auto`) ;
   si vous changez `namePrefix`, mettez la même valeur dans `bedrock.username-prefix`.
4. Les plugins peuvent reconnaître ces joueurs : `player.isJavaEditionPlayer()` et
   `player.getJavaIdentity()` (UUID et pseudo du compte Java). Leur UUID PNX est leur UUID Java.

## Plusieurs serveurs Bedrock

Un seul proxy peut servir tout un réseau : `bedrock.servers` déclare des serveurs nommés et
`bedrock.forced-hosts` choisit le serveur selon l'adresse tapée par le joueur Java
(`survie.example.org` → serveur `survie`) ; la liste des serveurs Java affiche alors le MOTD et le
nombre de joueurs du bon serveur. Les transferts Bedrock (`TransferPacket`, ex. WaterdogPE ou un
plugin de lobby) sont suivis sans déconnexion.

```yaml
bedrock:
  address: 127.0.0.1      # serveur par défaut
  port: 19132
  servers:
    survie: 127.0.0.1:19133
    creatif: 10.0.0.5:19132
  forced-hosts:
    survie.example.org: survie
```

Les joueurs Java changent eux-mêmes de serveur avec **`/jtb server <nom>`** (`/jtb server` liste les
serveurs). La commande `/jtb` donne aussi `info`, `version` et `ping` ; elle n'est proposée que si le
serveur Bedrock n'a pas sa propre commande `jtb`.

## Salle d'attente (redémarrages du serveur Bedrock)

Quand le serveur Bedrock s'arrête, redémarre ou plante, les joueurs Java **ne sont pas déconnectés** :
ils patientent sur l'écran de chargement avec un message (et un bouton pour partir), puis rejoignent
le monde automatiquement dès que le serveur répond à nouveau. Un arrêt propre de PowerNukkitX est
détecté immédiatement, un plantage en 15 secondes. Les expulsions et bannissements déconnectent
normalement. Réglages : `bedrock.reconnect` (`timeout: 0` pour désactiver).

## Skins

- Les joueurs Bedrock voient le skin Mojang (et la cape) des joueurs Java.
- Les joueurs Java voient le skin des joueurs Bedrock : Java n'affichant que des skins signés par
  Mojang, le proxy réutilise la signature d'origine des joueurs Java qui passent par lui, interroge
  l'API publique GeyserMC (joueurs Xbox) et, avec une clé [MineSkin](https://account.mineskin.org/keys)
  (`skins.mineskin-api-key`), fait signer n'importe quel skin. Chaque skin n'est signé qu'une fois (cache).
- Avec les mods *ViaBedrockUtility* ou *BedrockSkinUtility* côté Java, les skins Bedrock complets
  (géométrie custom, capes) sont affichés.

## Packs de ressources

Les packs Bedrock du serveur sont téléchargés (en morceaux ou via CDN, packs chiffrés compris),
convertis en un pack Java 26.3 et servis par un petit serveur HTTP intégré (`resource-packs.*` dans
`config.yml`). Le pack est mis en cache : la conversion n'a lieu qu'une fois.
Si les joueurs se connectent depuis Internet, ouvrez le port HTTP (`8765` par défaut) ou réglez
`resource-packs.public-url`.

## État des fonctionnalités

Ce tableau est tenu à jour à chaque version (✅ fait et couvert par les tests de bout en bout ·
🟡 partiel · ❌ pas encore). Chaque ligne ✅ passe contre le PowerNukkitX 1.26.60 de Linesia
(`tools/e2e/run.sh`, 14 scénarios), et un passage sur une copie complète de Linesia (core, 18 packs
chiffrés par RPEncrypter) a vérifié les packs, objets, blocs, PNJ et menus.

| Domaine | État |
|---|---|
| Connexion NetherNet, liste des serveurs (MOTD, joueurs), salle d'attente, mode offline | ✅ |
| Mode microsoft | 🟡 implémenté, pas couvert par les tests automatiques |
| Mode trusted | 🟡 demande un PowerNukkitX avec *java-bridge* (voir plus bas) |
| Monde : chunks, blocs, biomes, éclairage, mises à jour de blocs, block entities, cadres | ✅ |
| Joueur : mouvements serveur-autoritaires, capacités, santé/faim/XP, effets, mort, dimensions | ✅ |
| Interactions : casser, poser, utiliser, attaquer | ✅ |
| Entités : apparition, métadonnées, mouvements, équipement, montures, liste des joueurs, PNJ | ✅ |
| Inventaires et conteneurs (requêtes serveur-autoritaires), artisanat, créatif, menus spéciaux | ✅ |
| Chat, commandes avec auto-complétion | ✅ |
| Formulaires (simples, modaux, personnalisés, PNJ, écrans data-driven) → dialogues Java | ✅ |
| Scoreboard, boss bars, titres, toasts, caméra | ✅ |
| Sons, particules | ✅ |
| Packs : textures, objets/blocs/entités custom, armures, polices, sons, langues, packs chiffrés | ✅ |

## Limites

Ce que Java ne permet pas de reproduire, et ce que le proxy fait à la place :

- **Interfaces JSON des packs (HUD, écrans)** : Java n'a pas d'équivalent. Les formulaires deviennent
  des dialogues Java, les inventaires des écrans Java, mais un HUD dessiné par un pack d'interface
  (par exemple les données qu'un plugin encode dans le texte d'une boss bar pour son pack) s'affiche
  tel quel.
- **Blocs custom** : Java n'offre qu'environ 1 500 états « porteurs » (bloc musical, blocs de
  champignon) qu'un pack peut redessiner. Ils vont d'abord aux blocs ayant le moins d'apparences ;
  les états qui partagent une apparence partagent un porteur. Au-delà, le bloc est un `barrier`
  invisible (ou `structure_void` sans collision) et une entité `item_display` dessine son modèle :
  Linesia (2 447 blocs, 32 502 états, 23 640 apparences) s'affiche ainsi en entier. Ces blocs-là
  n'émettent pas de lumière côté Java et le proxy termine leur casse au temps annoncé par le serveur.
- **Entités custom** : dessinées par une entité `item_display` portant le modèle converti (os figés,
  sans animation).

## Développement

- Architecture : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Règles, contrats entre sous-systèmes et propriété des paquets : [`docs/DEVELOPING.md`](docs/DEVELOPING.md)
- Test de bout en bout contre un vrai PowerNukkitX, sans client Minecraft :
  `tools/e2e/run.sh <powernukkitx.jar> [scénario] [secondes]` (serveur jetable, proxy et client Java
  headless ; scénarios dans `src/test/java/…/e2e/scenario`). La CI (`.github/workflows/e2e.yml`) le lance
  contre PowerNukkitX construit depuis le fork.
- Client headless contre un serveur existant : `./gradlew runHeadlessClient -Phost=… -Pport=…`

## Licence et crédits

JavaToBedrock est distribué sous **GPL-3.0** (voir `LICENSE`).

Il s'appuie sur et remercie :
[MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) (MIT),
[PowerNukkitX Protocol](https://github.com/PowerNukkitX/Protocol) / [CloudburstMC Protocol](https://github.com/CloudburstMC/Protocol) (Apache-2.0),
[ViaBedrock](https://github.com/RaphiMC/ViaBedrock) (GPL-3.0 — données de correspondance et algorithmes),
[Geyser](https://github.com/GeyserMC/Geyser) et ses [mappings](https://github.com/GeyserMC/mappings) (MIT),
[MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) (LGPL-3.0).
Les fichiers du jeu (traductions, textures vanilla) ne sont pas redistribués : ils sont téléchargés
à l'exécution depuis le dépôt officiel `Mojang/bedrock-samples`.

Projet non affilié à Mojang ni à Microsoft.
