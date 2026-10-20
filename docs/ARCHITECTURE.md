# JavaToBedrock — Architecture

JavaToBedrock est un proxy qui permet à un client **Minecraft Java Edition 26.3** (protocole 777)
de rejoindre un serveur **Minecraft Bedrock Edition** — en priorité **PowerNukkitX** (Bedrock 1.26.50,
protocole 2193), mais aussi tout serveur Bedrock parlant le même protocole (BDS, autres logiciels).

```
 Client Java 26.3  ──TCP (protocole Java)──▶  JavaToBedrock  ──RakNet (protocole Bedrock)──▶  Serveur Bedrock (PNX)
                   ◀────────────────────────                  ◀─────────────────────────────
                         MCProtocolLib (serveur)                PowerNukkitX Protocol (client)
```

Le proxy se comporte, vis‑à‑vis du serveur Bedrock, comme un **client Bedrock vanilla parfait**, et,
vis‑à‑vis du client Java, comme un **serveur Java vanilla**. Chaque joueur Java possède une
`ProxySession` qui relie sa connexion Java à sa propre connexion Bedrock et qui traduit chaque
paquet dans les deux sens.

## 1. Principes

1. **Un seul fil par session.** Tout l'état d'une session (monde, entités, inventaire…) n'est lu
   et modifié que sur l'`EventLoop` de la session (`ProxySession#execute`). Les paquets Java et
   Bedrock arrivent sur leurs threads Netty respectifs et sont immédiatement re‑postés sur cette
   boucle. Aucun verrou n'est nécessaire dans les traducteurs. Le travail lourd (conversion de
   chunks, calcul de lumière, conversion de packs) part sur des pools dédiés puis revient sur la
   boucle de la session pour l'envoi.
2. **Traducteurs par paquet.** Chaque paquet entrant est traité par un traducteur unique :
   - `BedrockTranslator<P extends BedrockPacket>` : serveur Bedrock → client Java ;
   - `JavaTranslator<P extends Packet>` : client Java → serveur Bedrock.
   Les traducteurs sont regroupés en **modules** (`TranslatorModule`) par sous‑système et
   enregistrés dans `Translators`.
3. **Données statiques partagées, données dynamiques par serveur.** Les tables de correspondance
   vanilla (blocs, objets, entités, sons, particules, biomes…) sont chargées une seule fois au
   démarrage (`MappingData`). Les définitions envoyées par le serveur (objets et blocs custom,
   recettes, contenu créatif) sont conservées par session (et mises en cache par empreinte pour
   être réutilisées entre sessions vers le même serveur).
4. **Aller‑retour sans perte.** Un objet Bedrock converti en `ItemStack` Java garde son identité
   Bedrock exacte dans le composant `custom_data` (`{jtb:{...}}`) afin de pouvoir être reconverti
   exactement (inventaire créatif, requêtes d'inventaire, etc.).
5. **Le serveur reste l'autorité.** Le proxy ne « devine » jamais l'état du jeu : il traduit les
   intentions du joueur Java (clics, mouvements, actions) en intentions Bedrock et affiche au
   joueur Java ce que le serveur Bedrock lui renvoie (corrections incluses).

## 2. Paquets (packages) et responsabilités

Racine : `io.github.wyze3306.jtb`

| Package | Rôle |
|---|---|
| `(racine)` | `JavaToBedrock` : démarrage, cycle de vie, console |
| `config` | `ProxyConfig` (fichier `config.yml`) |
| `network.java` | Serveur Java (MCProtocolLib) : statut, connexion, phase de configuration |
| `network.bedrock` | Client Bedrock : RakNet, ping/MOTD, paramètres réseau, login, chiffrement, packs |
| `session` | `ProxySession`, `SessionManager`, cycle de vie d'une connexion |
| `translator` | API des traducteurs + registre + liste des modules |
| `mapping` | Tables statiques Bedrock ↔ Java chargées depuis `src/main/resources/mappings` |
| `auth` | Chaînes de login Bedrock : hors‑ligne auto‑signée, Microsoft/Xbox, proxy de confiance |
| `skin` | Skin Java → skin Bedrock (et l'inverse quand c'est possible) |
| `text` | Texte Bedrock (codes §, traductions, rawtext JSON) ↔ `Component` Adventure, langues |
| `world` | Chunks, sous‑chunks, palettes, biomes, lumière, blocs, block entities, dimensions, temps, météo |
| `block` | Traduction des états de blocs (vanilla + custom), allocation d'états Java pour les blocs custom |
| `item` | Traduction `ItemData` ↔ `ItemStack` (composants), registre d'objets du serveur, objets custom |
| `entity` | Suivi des entités, types, métadonnées, mouvements, équipement, montures, entités custom |
| `player` | Joueur local : mouvement (`PlayerAuthInput`), capacités, attributs, effets, interactions (casser/poser/utiliser/attaquer), mort/réapparition |
| `inventory` | Inventaires et conteneurs, clics Java → `ItemStackRequest`, artisanat, créatif, échanges |
| `chat` | Chat, commandes (arbre Brigadier depuis `AvailableCommands`), suggestions |
| `form` | Formulaires Bedrock (Simple/Modal/Custom, NPC, paramètres serveur) → Dialogues Java 26.3 |
| `ui` | Scoreboard, boss bars, titres, action bar, toasts, liste des joueurs, caméra, HUD |
| `sound` | Sons, particules, événements de niveau |
| `resourcepack` | Téléchargement / déchiffrement des packs Bedrock, conversion en pack Java, serveur HTTP, cache |
| `util` | Utilitaires communs |

## 3. Cycle de vie d'une session

La configuration (`config.yml`) peut être surchargée par variables d'environnement `JTB_<CHEMIN>`
(`bedrock.address` → `JTB_BEDROCK_ADDRESS`), pratique en conteneur (`Dockerfile`, `docker/`).


```
Java  : Handshake ─▶ Login (Hello, chiffrement, auth Mojang en mode online) ─▶ LoginFinished
Proxy : ouvre la connexion Bedrock dès LoginAcknowledged (le client Java reste en CONFIGURATION)
Bedrock: RequestNetworkSettings ─▶ NetworkSettings (compression) ─▶ Login ─▶ Handshake chiffré
       ─▶ ResourcePacksInfo ─▶ (téléchargement des packs) ─▶ ResourcePackStack ─▶ COMPLETED
Proxy : conversion des packs Bedrock → pack Java, envoi `ResourcePackPush` (phase CONFIGURATION)
Bedrock: StartGame (+ définitions objets/blocs, créatif, recettes, biomes…)
Java  : SelectKnownPacks / RegistryData / UpdateTags / FinishConfiguration ─▶ Login (play)
Bedrock: PlayStatus PLAYER_SPAWN ─▶ SetLocalPlayerAsInitialized ; chunks ─▶ Java
Jeu   : traduction continue + tic de 50 ms qui émet `PlayerAuthInputPacket`
```

## 4. API cœur (contrat entre sous‑systèmes)

```java
// Traducteurs
interface BedrockTranslator<P extends BedrockPacket> { void translate(ProxySession session, P packet); }
interface JavaTranslator<P extends Packet>           { void translate(ProxySession session, P packet); }
interface TranslatorModule { void register(TranslatorRegistry registry); }
// registry.bedrock(StartGamePacket.class, new StartGameTranslator());
// registry.java(ServerboundChatPacket.class, (session, packet) -> ...);

// Session
ProxySession#sendJava(Packet)                // vers le client Java (thread‑safe)
ProxySession#sendBedrock(BedrockPacket)      // vers le serveur Bedrock (thread‑safe)
ProxySession#execute(Runnable) / schedule(...)// boucle de la session
ProxySession#disconnect(Component|String)
ProxySession#getXxx()                        // états des sous‑systèmes (WorldState, EntityTracker…)
```

Chaque sous‑système possède **son** état (`WorldState`, `EntityTracker`, `InventoryState`,
`PlayerState`, `ScoreboardState`…) dans son propre package ; `ProxySession` se contente de les
instancier et de les exposer. Les sous‑systèmes communiquent par appels directs sur ces états
(toujours sur la boucle de la session).

## 5. Authentification Bedrock

| Mode | Usage | Exigence serveur |
|---|---|---|
| `offline` | Chaîne auto‑signée au nom du joueur Java | `xbox-auth=false` |
| `microsoft` | Le joueur Java se connecte une fois à son compte Microsoft (code d'appareil affiché en jeu) ; jetons mis en cache | aucun : identité Xbox réelle |
| `trusted` | Le proxy signe l'identité Java (vérifiée par Mojang) avec sa clé privée ; PowerNukkitX vérifie avec la clé publique du proxy (`java-bridge` dans `pnx.yml`) | PNX avec support JavaToBedrock |

## 6. Formulaires → Dialogues Java

Les formulaires Bedrock (`ModalFormRequestPacket`) sont rendus nativement avec le système de
**dialogues** de Java (26.3) :

| Bedrock | Java |
|---|---|
| `form` (SimpleForm : boutons) | dialogue `multi_action`, un bouton par bouton Bedrock |
| `modal` (deux boutons) | dialogue `multi_action` à deux boutons (+ bouton de sortie, pour distinguer Échap de « non ») |
| `custom_form` (label, input, toggle, slider, step_slider, dropdown, header, divider) | dialogue `notice`/`multi_action` avec entrées `text`, `boolean`, `number_range`, `single_option` et un bouton « Envoyer » qui renvoie toutes les valeurs |
| Fermeture (Échap) | réponse Bedrock annulée (`cancelReason = USER_CLOSED`) |

Les réponses passent par `ServerboundCustomClickActionPacket` (`jtb:form/<n>`, un numéro par affichage).
Les formulaires reçus pendant qu'un dialogue est affiché attendent leur tour, comme sur Bedrock ; les
paramètres du serveur (`ServerSettingsRequest`) sont accessibles depuis le menu pause ; les dialogues de
PNJ et les écrans pilotés par les données (`ddui`) sont aussi rendus en dialogues.

## 7. Packs de ressources

Les packs du serveur Bedrock sont téléchargés (par morceaux ou via CDN), déchiffrés si besoin,
puis convertis en **un pack Java 26.3** : textures vanilla remplacées, objets custom (définitions
`items/`, modèles), blocs custom (états Java inutilisés re‑texturés), polices de glyphes
(`glyph_XX.png` → fournisseurs bitmap), sons (`sound_definitions.json` → `sounds.json`), langues
(`.lang` → `.json`), modèles d'entités custom (os → `item_display`). Le pack est servi par un
serveur HTTP intégré, mis en cache par empreinte SHA‑1 et envoyé pendant la phase de configuration.

## 8. Changement de serveur et salle d'attente

`ProxySession#detachBedrock` quitte le serveur Bedrock courant sans perdre le joueur Java : la
connexion Bedrock est fermée, chaque état qui décrit le monde (`SessionState#survivesServerSwitch`
faux) est libéré, et le client Java repasse en phase CONFIGURATION (`JavaConfiguration#startServerSwitch`).
Le `StartGame` du serveur suivant produit un nouveau login Java qui reconstruit le monde.

- **Transfert** (`TransferPacket`, `bedrock.follow-transfers`) : `switchServer(cible)`.
- **Salle d'attente** (`BedrockReconnect`, `bedrock.reconnect`) : un arrêt propre (raison `SHUTDOWN`
  ou message d'arrêt connu), une coupure RakNet ou un serveur injoignable laissent le joueur dans
  l'écran de configuration avec un dialogue ; nouvelle tentative toutes les 5 s jusqu'au délai
  maximal, puis `onBedrockReady` efface le dialogue. Une expulsion ordinaire déconnecte.

## 9. Skins

| Sens | Mécanisme |
|---|---|
| Java → Bedrock | `JavaSkinProvider` : skin et cape Mojang (même UV 64×64), repli sur un skin généré |
| Bedrock → Java vanilla | `SignedSkins` : Java exige une signature Mojang. Index des pixels envoyés pour les joueurs Java (signature d'origine), MineSkin (clé API), API GeyserMC par XUID ; cache disque `cache/skins` |
| Bedrock → Java avec mod | `SkinMods` : ViaBedrockUtility / BedrockSkinUtility (canaux détectés par `JavaChannels`) reçoivent l'image, la géométrie custom et la cape |

## 10. Concurrence et performance

- Boucles d'événements Netty partagées ; une boucle par session (pas un thread par joueur).
- Conversion des chunks et calcul de lumière sur un pool de travail, palettes primitives
  (`fastutil`), aucune allocation inutile dans le tic de mouvement.
- Paquets Java regroupés par lots (`ChunkBatchStart/Finished`) avec contrôle de débit.
- Caches : packs convertis (disque), définitions serveur (mémoire), skins.
