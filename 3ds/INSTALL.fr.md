<!-- Made from docs/manual/translations/fr/3ds.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Installer sur une Nintendo 3DS

*Installez l'application homebrew PokeDaisy sur une 3DS étape par étape, ajoutez vos jeux et vos sauvegardes, et activez le son.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · [한국어](INSTALL.ko.md) · **Français** · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

Aussi sur le site: https://pokedaisy.com/fr/docs/3ds/

PokeDaisy fonctionne aussi sur Nintendo 3DS, comme application homebrew : votre jeu tourne sur l'écran supérieur et le compagnon vit sur l'écran tactile, avec sa propre bibliothèque de jeux, un éditeur de sauvegardes et des échanges entre sauvegardes. Cette page vous guide d'une console avec un firmware personnalisé jusqu'à votre premier jeu.

> [!NOTE]
> La version 3DS est un aperçu précoce. Elle est faite pour la **New Nintendo 3DS, la New Nintendo 3DS XL et la New Nintendo 2DS XL**. Une Nintendo 3DS, 3DS XL ou 2DS d'origine peut la lancer, mais elle risque d'être lente. Ses menus sont en anglais pour l'instant.

![La bibliothèque PokeDaisy sur 3DS : le jeu sélectionné sur l'écran supérieur, la liste des jeux sur l'écran tactile](../docs/manual/shots/3ds-library.png "La bibliothèque : le jeu sous le curseur en haut, vos jeux en bas")

## Ce qu'il vous faut

- **Une 3DS avec un firmware personnalisé (Luma3DS) et le Homebrew Launcher.** Si votre console ne les a pas encore, suivez [3ds.hacks.guide](https://3ds.hacks.guide/). C'est le guide de la communauté, tenu à jour pour chaque console et chaque version du système, et il installe tout ce dont cette page a besoin. Revenez ici une fois terminé.
- **La carte SD**, et un ordinateur qui peut la lire (ou une application FTP sur la 3DS, si vous préférez ne pas sortir la carte).
- **Vos propres jeux.** PokeDaisy ne contient aucun jeu. Utilisez des ROM que vous avez copiées vous-même depuis des cartouches que vous possédez : des fichiers `.gba`, `.gb` ou `.gbc`.

## Étape 1 : récupérer l'application

1. Sur un ordinateur, ouvrez la [page des versions de PokeDaisy](https://github.com/lidor30/pokedaisy/releases).
2. Dans la version la plus récente qui propose un fichier 3DS, téléchargez `pokedaisy.3dsx`.

## Étape 2 : le copier sur la carte SD

1. Éteignez la 3DS et mettez sa carte SD dans votre ordinateur.
2. Ouvrez le dossier `3ds` à la racine de la carte. L'installation du firmware personnalisé l'a créé ; s'il n'y est pas, créez-le.
3. Copiez-y `pokedaisy.3dsx`, pour que la carte contienne `sdmc:/3ds/pokedaisy.3dsx`.

`sdmc:/` est le nom que la 3DS donne à la racine de la carte SD : sur votre ordinateur, `sdmc:/3ds/` est simplement le dossier `3ds` de la carte.

## Étape 3 : ajouter vos jeux et vos sauvegardes

1. À la racine de la carte, créez un dossier `pokedaisy`, et dedans un dossier `roms` : `sdmc:/pokedaisy/roms/`. (PokeDaisy crée les deux la première fois qu'il démarre, si vous préférez le lancer une fois d'abord.)
2. Copiez vos jeux dans `roms`. Mettez-les directement dans le dossier, pas dans des sous-dossiers, et décompressez-les d'abord : sur 3DS, PokeDaisy ne lit que des fichiers `.gba`, `.gb` et `.gbc` non compressés.
3. Vous reprenez une sauvegarde d'un autre émulateur ? Placez-la dans `roms` à côté de son jeu, avec le même nom et `.sav` à la fin : `Pokemon Emerald.gba` lit `Pokemon Emerald.sav`. Une sauvegarde RetroArch se termine par `.srm` : renommez-la en `.sav`. Gardez aussi une copie de la sauvegarde sur votre ordinateur, au cas où.

Sans sauvegarde, le jeu commence une nouvelle partie et PokeDaisy crée son `.sav` dans le même dossier la première fois que vous sauvegardez dans le jeu.

## Étape 4 : activer le son

Les homebrews 3DS ne peuvent jouer du son qu'une fois le firmware audio de la console copié sur la carte SD. C'est à faire une seule fois :

1. Remettez la carte SD, allumez la 3DS, maintenez **L**, puis appuyez sur **Bas** sur la croix directionnelle et sur **SELECT** pour ouvrir le menu Rosalina.
2. Choisissez **Miscellaneous options**, puis **Dump DSP firmware**.
3. Appuyez sur **B** jusqu'à ce que le menu se ferme.

Cela écrit `sdmc:/3ds/dspfirm.cdc`. Sans ce fichier, les jeux tournent sans son. Si vous l'avez déjà fait pour une autre application, il n'y a rien à faire.

## Étape 5 : lancer PokeDaisy

1. Ouvrez le Homebrew Launcher. Sur une console préparée avec 3ds.hacks.guide, il s'ouvre depuis l'application de téléchargement DS (Download Play).
2. Choisissez **PokeDaisy** dans la liste.

La bibliothèque s'ouvre : l'écran tactile liste vos jeux et l'écran supérieur montre celui sous le curseur, avec votre joueur, votre temps de jeu et votre équipe une fois que vous y avez joué. Déplacez-vous avec la croix directionnelle ou touchez un jeu, puis appuyez sur **A** pour jouer.

![Le premier lancement de la bibliothèque, sans jeux : l'écran supérieur dit de mettre vos jeux dans sdmc:/pokedaisy/roms/](../docs/manual/shots/3ds-library-empty.png "Pas encore de jeux : la bibliothèque indique où les mettre")

Si la bibliothèque affiche **No games yet**, vérifiez que vos jeux sont directement dans `sdmc:/pokedaisy/roms/` et que leur nom se termine par `.gba`, `.gb` ou `.gbc`.

## Se repérer

| Bouton | Ce qu'il fait |
| --- | --- |
| **A** | Jouer au jeu sous le curseur |
| **L** / **R** | Changer la vue de la bibliothèque : `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | La page suivante : jeux, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | Fermer PokeDaisy et revenir au Homebrew Launcher |

Pendant une partie, les boutons de la GBA sont ceux de la 3DS (le pad circulaire sert de croix directionnelle) et l'écran tactile est le compagnon. Quelques autres :

| Bouton | Ce qu'il fait |
| --- | --- |
| **Y** | Le menu rapide : PC, `FLY`, votre vélo, états sauvegardés, avance rapide et plus |
| **X** | Sur le terrain, confier les boutons au compagnon (**X** à nouveau les rend au jeu) |
| **ZR** | Avance rapide tant que vous le maintenez (New 3DS) |
| **ZL** | L'onglet précédent du compagnon (New 3DS) |

Pour quitter un jeu, utilisez `CLOSE GAME` tout en bas des `SETTINGS` du compagnon. Le bouton **HOME** et la fermeture de la console marchent aussi.

![La page SETTINGS de la bibliothèque sur 3DS : VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT et le son](../docs/manual/shots/3ds-settings.png "Les SETTINGS de la bibliothèque : l'apparence et l'écran pour tous les jeux")

## Les jeux qui marchent

Tout jeu Game Boy, Game Boy Color ou Game Boy Advance se lance. Le compagnon lit les jeux que PokeDaisy connaît sur Android : FireRed, LeafGreen, Ruby, Sapphire et Emerald, Red, Blue, Yellow, Gold, Silver et Crystal, et beaucoup de hacks ROM ([Jeux compatibles](https://pokedaisy.com/docs/supported-games/) en donne la liste). Les jeux DS ne tournent pas sur la version 3DS.

## Où sont les choses sur la carte SD

| Chemin | Ce qu'il contient |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | L'application |
| `sdmc:/3ds/dspfirm.cdc` | Le firmware audio (étape 4) |
| `sdmc:/pokedaisy/roms/` | Vos jeux, et le `.sav` de chaque jeu à côté de lui |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | Une copie de chaque sauvegarde avant chaque lancement, les 10 plus récentes par jeu |
| `sdmc:/pokedaisy/states/` | Les états sauvegardés |
| `sdmc:/pokedaisy/cheats/` | Les codes de triche, un fichier par jeu |
| `sdmc:/pokedaisy/settings.ini` | Vos réglages |
| `sdmc:/pokedaisy/log.txt` | Ce qu'a fait le dernier lancement : joignez-le quand vous signalez un bug |

## Mettre à jour

Téléchargez le nouveau `pokedaisy.3dsx` et copiez-le par-dessus l'ancien dans `sdmc:/3ds/`. Vos jeux, sauvegardes, états et réglages sont tous dans `sdmc:/pokedaisy/` et restent tels quels.

## En cas de problème

- **Pas de son :** `sdmc:/3ds/dspfirm.cdc` manque. Faites l'étape 4.
- **PokeDaisy n'apparaît pas dans le Homebrew Launcher :** le fichier doit être dans `sdmc:/3ds/` et se terminer par `.3dsx`.
- **Un jeu n'est pas dans la liste :** il doit être directement dans `sdmc:/pokedaisy/roms/`, pas dans un dossier à l'intérieur, et pas compressé.
- **Ma sauvegarde n'a pas été chargée :** elle doit porter exactement le nom du jeu avec `.sav` à la fin. À chaque lancement, la sauvegarde est d'abord copiée dans `sdmc:/pokedaisy/roms/pokedaisy-backups/` : une copie plus ancienne vous y attend si besoin.
- **C'est lent :** une 3DS ou une 2DS d'origine est plus lente que les modèles New. Dans les `SETTINGS` de la bibliothèque, mettez `SCREEN` sur `PIXEL 1x`, le mode le plus léger.

Toujours bloqué ? [Dites-le-nous](https://pokedaisy.com/fr/feedback/#bug) en joignant `sdmc:/pokedaisy/log.txt`.
