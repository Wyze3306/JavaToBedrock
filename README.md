# JavaToBedrock

**Jouez sur un serveur Minecraft Bedrock (PowerNukkitX, BDS…) avec Minecraft Java Edition.**

JavaToBedrock est un proxy autonome : les joueurs **Java Edition 26.3** se connectent au proxy comme
à n'importe quel serveur Java, et le proxy les fait jouer sur un serveur **Bedrock 1.26.50** en
traduisant chaque paquet dans les deux sens — monde, blocs, entités, inventaires, formulaires,
commandes, scoreboard, sons, packs de ressources… C'est le chemin inverse de Geyser.

```
Client Java 26.3 ──TCP──▶ JavaToBedrock ──RakNet──▶ Serveur Bedrock (PowerNukkitX 1.26.50)
```

> Statut : en développement actif. Voir [État des fonctionnalités](#état-des-fonctionnalités).

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
4. Dans `config.yml`, indiquer l'adresse du serveur Bedrock (`bedrock.address` / `bedrock.port`)
   et choisir le mode d'authentification (`bedrock.auth`, voir ci-dessous). Redémarrer.
5. Dans Minecraft Java 26.3 : *Multijoueur → Ajouter un serveur →* l'adresse du proxy
   (port `25565` par défaut).

Commandes de la console du proxy : `list`, `stop`.

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
   ```
   (PowerNukkitX écrit cette section avec ses valeurs par défaut au premier démarrage ; les clés
   sont en camelCase comme le reste de `pnx.yml`.) Le chiffrement réseau de PNX doit rester activé
   (`network-settings.networkEncryption: true`, valeur par défaut).
3. Les plugins peuvent reconnaître ces joueurs : `player.isJavaEditionPlayer()` et
   `player.getJavaIdentity()` (UUID et pseudo du compte Java). Leur UUID PNX est leur UUID Java.

## Packs de ressources

Les packs Bedrock du serveur sont téléchargés (en morceaux ou via CDN, packs chiffrés compris),
convertis en un pack Java 26.3 et servis par un petit serveur HTTP intégré (`resource-packs.*` dans
`config.yml`). Le pack est mis en cache : la conversion n'a lieu qu'une fois.
Si les joueurs se connectent depuis Internet, ouvrez le port HTTP (`8765` par défaut) ou réglez
`resource-packs.public-url`.

## État des fonctionnalités

Ce tableau est tenu à jour à chaque version (✅ fait · 🟡 partiel · ❌ pas encore).

| Domaine | État |
|---|---|
| Connexion, liste des serveurs (MOTD, joueurs), modes offline / microsoft / trusted | ✅ |
| Monde : chunks, blocs, biomes, éclairage, mises à jour de blocs, block entities | en cours |
| Joueur : mouvements serveur-autoritaires, capacités, santé/faim/XP, effets, mort | en cours |
| Interactions : casser, poser, utiliser, attaquer | en cours |
| Entités : apparition, métadonnées, mouvements, équipement, montures, liste des joueurs | en cours |
| Inventaires et conteneurs (requêtes serveur-autoritaires), artisanat, créatif, échanges | en cours |
| Chat, commandes avec auto-complétion | en cours |
| Formulaires → dialogues Java natifs | en cours |
| Scoreboard, boss bars, titres, toasts | en cours |
| Sons, particules | en cours |
| Conversion des packs (textures, objets/blocs/entités custom, polices, sons, langues) | en cours |

## Développement

- Architecture : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Règles, contrats entre sous-systèmes et propriété des paquets : [`docs/DEVELOPING.md`](docs/DEVELOPING.md)
- Test de bout en bout avec un vrai serveur : `./gradlew runHeadlessClient -Phost=… -Pport=…`

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
