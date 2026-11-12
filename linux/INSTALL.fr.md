<!-- Made from docs/manual/translations/fr/linux-install.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Installer sur Linux, Steam Deck et consoles portables

*Choisissez le bon téléchargement, installez PokeDaisy sur un Steam Deck, un PC Linux ou une console portable sous Linux (ou ses cœurs dans RetroArch), ajoutez vos jeux et reprenez vos sauvegardes.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · [한국어](INSTALL.ko.md) · **Français** · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

Aussi sur le site: https://pokedaisy.com/fr/docs/linux-install/

PokeDaisy fonctionne aussi sur Linux : sur un Steam Deck, un PC Linux et des consoles portables sous Linux comme celles de TrimUI, soit comme programme à part entière, soit comme cœurs libretro que vous chargez dans RetroArch ou dans le frontend de votre console. Dans les deux cas, vous avez le même compagnon, le même HUD, le même menu rapide et `EXPAND`. Cette page vous guide du téléchargement jusqu'à votre premier jeu ; [Linux, Steam Deck et consoles portables](https://pokedaisy.com/docs/linux/) explique les commandes, le menu et tout le reste une fois que vous jouez.

> [!WARNING]
> La version Linux est une bêta. Ses menus sont en anglais pour l'instant, et certains appareils de cette page n'ont pas encore été essayés. En cas de problème, [dites-nous](https://pokedaisy.com/fr/feedback/#bug) quel fichier et quel appareil vous avez utilisés.

## Ce qu'il vous faut

- **Un Steam Deck, un PC Linux (x86_64) ou une console portable Linux ARM 64 bits.** Sur un PC : un Linux avec glibc 2.35 ou plus récent et SDL2 (SteamOS, Ubuntu 22.04 et plus récent et la plupart des bureaux actuels ont les deux). Sur une console portable : glibc 2.33 ou plus récent, ce qui inclut la TrimUI Brick.
- **Un moyen d'y copier des fichiers :** l'appareil lui-même, ou sa carte SD dans un ordinateur.
- **Vos propres jeux.** PokeDaisy ne contient aucun jeu. Utilisez des ROM que vous avez copiées vous-même depuis des cartouches que vous possédez : des fichiers `.gba`, `.gb`, `.gbc` ou `.nds`, décompressés.

## Quel fichier télécharger

Ouvrez la [page des versions de PokeDaisy](https://github.com/lidor30/pokedaisy/releases) et, dans la version la plus récente qui propose des fichiers Linux, téléchargez celui qui correspond à votre appareil :

| Votre appareil | Téléchargement |
| --- | --- |
| Steam Deck, ou un PC Linux | `pokedaisy-linux-x86_64.zip` |
| Une console portable TrimUI (CrossMix, NextUI), comme application | `pokedaisy-linux-aarch64.zip` |
| Une console portable avec PortMaster (ROCKNIX, Knulli, muOS et d'autres) | `pokedaisy-portmaster-aarch64.zip` |
| Une TrimUI Brick sous NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch ou un autre frontend, sur un PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch ou un autre frontend, sur une console portable ARM | `pokedaisy-libretro-aarch64.zip` |

Les trois premiers contiennent le programme PokeDaisy avec ses trois cœurs à côté : un pour la Game Boy Advance, un pour la Game Boy et la Game Boy Color, un pour la DS. Les deux derniers ne contiennent que les cœurs. Suivez ensuite la partie de cette page qui correspond à votre appareil.

## Steam Deck et PC Linux

1. Extrayez `pokedaisy-linux-x86_64.zip` quelque part dans votre dossier personnel, par exemple dans un dossier `PokeDaisy`. Sur un Steam Deck, faites-le en mode Bureau. Vous obtenez un dossier `pokedaisy-linux-x86_64` avec le programme, `pokedaisy-linux`, et les trois cœurs.
2. Dans ce dossier, créez un dossier nommé `roms` et copiez-y vos jeux. (PokeDaisy le crée la première fois qu'il démarre, si vous préférez le lancer une fois d'abord.)
3. Ouvrez un terminal dans le dossier `pokedaisy-linux-x86_64` et lancez-le avec `./pokedaisy-linux --data .`

La liste des jeux s'ouvre : choisissez un jeu avec la croix directionnelle (ou les flèches) et appuyez sur A (Z sur un clavier). `--data .` indique à PokeDaisy de garder `roms`, `saves`, `states` et ses réglages dans ce dossier ; un raccourci que vous créez vous-même doit passer `--data` avec le chemin complet du dossier.

### Jouer en mode Jeu

1. En mode Bureau, ouvrez Steam et choisissez **Jeux** > **Ajouter un jeu non-Steam à ma bibliothèque**.
2. Choisissez **Parcourir**, allez dans le dossier `pokedaisy-linux-x86_64`, sélectionnez `pokedaisy-linux` et ajoutez-le.
3. Revenez en mode Jeu et lancez PokeDaisy depuis votre bibliothèque.

Steam le lance dans son propre dossier, donc aucune option de lancement n'est nécessaire. En mode Jeu, il occupe tout l'écran, et les commandes, l'écran tactile et le son du Deck fonctionnent comme d'habitude.

## Consoles portables TrimUI

`pokedaisy-linux-aarch64.zip` est PokeDaisy sous forme d'application TrimUI, faite pour CrossMix et NextUI.

1. Extrayez le zip sur votre ordinateur. Vous obtenez un dossier `pokedaisy-linux-aarch64`.
2. Mettez la carte SD dans votre ordinateur et copiez ce dossier dans le dossier `Apps` de la carte (sur CrossMix).
3. Copiez vos jeux dans le dossier `roms` qu'il contient (créez-le s'il n'y est pas).
4. Remettez la carte et lancez PokeDaisy depuis le menu Apps.

L'application garde tout dans son propre dossier et tourne en plein écran. Sur CrossMix, elle relève la vitesse minimale du processeur pendant que vous jouez et la remet comme avant quand vous quittez.

> [!NOTE]
> Le programme n'a pas encore tourné comme application autonome sur une TrimUI (ses cœurs, oui). Attendez-vous à quelques imperfections, et dites-nous comment ça se passe.

## Consoles portables avec PortMaster

1. Extrayez `pokedaisy-portmaster-aarch64.zip` sur votre ordinateur. Il contient `PokeDaisy.sh` et un dossier `pokedaisy`.
2. Copiez les deux dans le dossier `ports` de votre firmware sur la carte SD.
3. Vos jeux peuvent rester où ils sont : la liste des jeux affiche `ports/pokedaisy/roms` et les dossiers `gba`, `gb`, `gbc` et `nds` du firmware (ainsi que ceux nommés `Game Boy Advance`, `Game Boy Color`, `Game Boy` et `Nintendo DS`).
4. Lancez PokeDaisy depuis la liste Ports.

PortMaster indique à PokeDaisy les boutons de votre appareil, donc ils fonctionnent sans configuration. Le bouton Menu ou Guide ouvre le menu de PokeDaisy, et SELECT et START ensemble permettent de quitter, comme dans n'importe quel port. Il a été testé en jeu sur une GKD 350H sous ROCKNIX.

## TrimUI Brick avec NXRedux

1. Extrayez `PokeDaisy-NXRedux.zip` à la racine de votre carte SD. Il ajoute `Emus/tg5040/PDAI.pak` et un dossier `Roms/PokeDaisy (PDAI)`.
2. Mettez vos jeux (`.gba`, `.gb`, `.gbc` et `.nds`, décompressés) dans `Roms/PokeDaisy (PDAI)`.
3. Lancez-les depuis la collection PokeDaisy dans NXRedux.

NXRedux gère vos sauvegardes, vos sauvegardes d'état, l'avance rapide, les shaders et la reprise depuis son propre menu. Donnez à chaque jeu un nom de fichier différent, car NXRedux nomme les sauvegardes uniquement d'après le nom du fichier.

## RetroArch et autres frontends

1. Extrayez `pokedaisy-libretro-x86_64.zip` (PC) ou `pokedaisy-libretro-aarch64.zip` (console portable ARM). Son dossier contient les trois cœurs, leurs licences et `SOURCE.txt` :
   - `pokedaisy_gba_libretro.so` lance les jeux Game Boy Advance.
   - `pokedaisy_gbc_libretro.so` lance les jeux Game Boy et Game Boy Color (et aussi Game Boy Advance).
   - `pokedaisy_ds_libretro.so` lance les jeux DS.
2. Copiez les cœurs dans le dossier des cœurs de votre frontend. RetroArch indique où il se trouve dans **Settings** > **Directory** > **Cores**.
3. Chargez votre jeu avec le cœur PokeDaisy (le cœur DS s'affiche sous le nom PokeDaisy DS).

Votre frontend gère la sauvegarde, les états et l'avance rapide comme pour n'importe quel cœur. Les réglages du compagnon vont dans le dossier de sauvegardes du frontend : `pokedaisy/settings.ini` pour les cœurs Game Boy et Game Boy Advance, `pokedaisy-ds/settings.ini` pour le cœur DS. Gardez les licences avec les cœurs si vous les partagez.

### Déjà installés : plorpOS et spruceOS

Sur [plorpOS](https://github.com/thwonp/plorpos) et les versions nightly de [spruceOS](https://github.com/spruceUI/spruceOS), les cœurs Game Boy, Game Boy Color et Game Boy Advance sont déjà installés, donc il n'y a rien à télécharger : choisissez PokeDaisy comme cœur pour votre jeu (sur plorpOS, dans la ligne Core d'une étagère Game Boy).

## Reprendre vos sauvegardes

Dans le programme PokeDaisy (les trois premiers téléchargements), la sauvegarde d'un jeu se trouve dans le dossier `saves` à côté de `roms`, nommée d'après le nom complet du fichier de la ROM suivi de `.sav` : pour `FireRed.gba`, c'est `saves/FireRed.gba.sav`. Une sauvegarde d'un autre émulateur fonctionne si vous la copiez là sous ce nom, y compris un `.srm` de RetroArch. Gardez d'abord une copie de votre ancienne sauvegarde ailleurs.

Avant qu'un jeu s'ouvre, PokeDaisy copie sa sauvegarde dans `saves/pokedaisy-backups` et garde les 10 copies les plus récentes : une plus ancienne vous y attend si besoin. Les sauvegardes d'état d'un autre émulateur ne se chargent pas.

Dans RetroArch, NXRedux et les autres frontends, la sauvegarde est le fichier du frontend lui-même, là où il la garde toujours.

## Mettre à jour

Téléchargez le nouveau zip et extrayez-le par-dessus l'ancien dossier (sur NXRedux, de nouveau à la racine de la carte SD). Vos jeux, sauvegardes, états et réglages ne sont pas dans le zip, donc ils restent tels quels. Dans un frontend, copiez les nouveaux cœurs par-dessus les anciens.

## En cas de problème

- **Permission refusée (permission denied) au lancement :** l'outil d'extraction a perdu le droit d'exécution du programme. Dans le dossier, lancez `chmod +x pokedaisy-linux`.
- **Une erreur qui mentionne `GLIBC_2.35` ou `libSDL2` :** votre Linux est plus ancien que ce dont PokeDaisy a besoin, ou SDL2 n'est pas installé. Installez SDL2 depuis votre distribution (sur Ubuntu, `sudo apt install libsdl2-2.0-0`), ou essayez un système plus récent.
- **Un jeu n'est pas dans la liste :** il doit être directement dans `roms` (pas dans un dossier à l'intérieur), décompressé, et se terminer par `.gba`, `.gb`, `.gbc` ou `.nds`.
- **A et B sont inversés :** ouvrez le menu (START et SELECT ensemble, ou le bouton Guide ou Menu) et mettez `A/B BUTTONS` sur `SWAPPED`.
- **Ma sauvegarde n'a pas été chargée :** la sauvegarde doit porter le nom complet du fichier de la ROM avec `.sav` ajouté, dans `saves`. Cherchez une copie plus ancienne dans `saves/pokedaisy-backups`.
- **La manette ne fait rien :** SDL ne la connaît pas encore. Mettez un `gamecontrollerdb.txt` avec sa configuration dans le dossier de données (à côté de `roms`).

Toujours bloqué ? [Dites-nous](https://pokedaisy.com/fr/feedback/#bug) quel appareil et quel fichier vous avez utilisés. Lancé depuis un terminal, PokeDaisy y affiche ce qui s'est passé ; l'application TrimUI l'écrit dans `launch.log` et le port PortMaster dans `log.txt`, tous deux dans leur dossier.
