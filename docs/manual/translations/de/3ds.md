---
title: Auf einem Nintendo 3DS installieren
summary: Bring die Homebrew-App PokeDaisy Schritt für Schritt auf einen 3DS, füge deine Spiele und Speicherdateien hinzu und schalte den Ton ein.
section: start
---

PokeDaisy läuft auch auf dem Nintendo 3DS, als Homebrew-App: Dein Spiel läuft auf dem oberen Bildschirm, und der Begleiter wohnt auf dem Touchscreen, mit eigener Spielebibliothek, einem Speicherstand-Editor und Tausch zwischen Speicherdateien. Diese Seite bringt dich von einer Konsole mit Custom Firmware bis zu deinem ersten Spiel.

> [!NOTE]
> Die 3DS-Version ist eine frühe Vorschau. Sie ist für den **New Nintendo 3DS, New Nintendo 3DS XL und New Nintendo 2DS XL** gemacht. Ein älterer 3DS, 3DS XL oder 2DS startet sie vielleicht, kann aber langsam laufen. Ihre Menüs sind vorerst auf Englisch.

![Die PokeDaisy-Bibliothek auf einem 3DS: das gewählte Spiel auf dem oberen Bildschirm, die Spieleliste auf dem Touchscreen](shots/3ds-library.png "Die Bibliothek: oben das Spiel unter dem Cursor, unten deine Spiele")

## Was du brauchst

- **Einen 3DS mit Custom Firmware (Luma3DS) und dem Homebrew Launcher.** Falls deine Konsole das noch nicht hat, folge [3ds.hacks.guide](https://3ds.hacks.guide/). Das ist die Anleitung der Community, für jede Konsole und Systemversion aktuell gehalten, und sie richtet alles ein, was diese Seite braucht. Komm danach hierher zurück.
- **Die SD-Karte** und einen Computer, der sie lesen kann (oder eine FTP-App auf dem 3DS, wenn du die Karte nicht herausnehmen willst).
- **Deine eigenen Spiele.** PokeDaisy enthält keine Spiele. Nutze ROMs, die du selbst aus Modulen ausgelesen hast, die dir gehören: Dateien mit der Endung `.gba`, `.gb` oder `.gbc`.

## Schritt 1: die App holen

1. Öffne auf einem Computer die [Release-Seite von PokeDaisy](https://github.com/lidor30/pokedaisy/releases).
2. Lade beim neuesten Release, das eine 3DS-Datei anbietet, `pokedaisy.3dsx` herunter.

## Schritt 2: auf die SD-Karte kopieren

1. Schalte den 3DS aus und stecke seine SD-Karte in deinen Computer.
2. Öffne den Ordner `3ds` ganz oben auf der Karte. Die Einrichtung der Custom Firmware hat ihn angelegt; fehlt er, lege ihn an.
3. Kopiere `pokedaisy.3dsx` hinein, sodass die Karte `sdmc:/3ds/pokedaisy.3dsx` enthält.

`sdmc:/` ist der Name, den der 3DS der obersten Ebene der SD-Karte gibt: Auf deinem Computer ist `sdmc:/3ds/` einfach der Ordner `3ds` auf der Karte.

## Schritt 3: Spiele und Speicherdateien hinzufügen

1. Lege ganz oben auf der Karte einen Ordner `pokedaisy` an und darin einen Ordner `roms`: `sdmc:/pokedaisy/roms/`. (PokeDaisy legt beide beim ersten Start selbst an, falls du es lieber erst einmal startest.)
2. Kopiere deine Spiele nach `roms`. Lege sie direkt in den Ordner, nicht in eigene Unterordner, und entpacke sie vorher: PokeDaisy liest auf dem 3DS nur lose Dateien mit der Endung `.gba`, `.gb` und `.gbc`.
3. Du bringst eine Speicherdatei aus einem anderen Emulator mit? Lege sie in `roms` neben ihr Spiel, mit demselben Namen und `.sav` am Ende: `Pokemon Emerald.gba` liest `Pokemon Emerald.sav`. Eine RetroArch-Speicherdatei endet auf `.srm`: Benenne sie in `.sav` um. Behalte zur Sicherheit auch eine Kopie auf deinem Computer.

Ohne Speicherdatei beginnt das Spiel von vorn, und PokeDaisy legt seine `.sav` im selben Ordner an, sobald du im Spiel zum ersten Mal speicherst.

## Schritt 4: den Ton einschalten

3DS-Homebrew kann erst Ton abspielen, wenn die Sound-Firmware der Konsole auf die SD-Karte kopiert wurde. Das musst du nur einmal machen:

1. Stecke die SD-Karte wieder ein, schalte den 3DS ein, halte **L** gedrückt und drücke dann **Unten** auf dem Steuerkreuz und **SELECT**, um das Rosalina-Menü zu öffnen.
2. Wähle **Miscellaneous options**, dann **Dump DSP firmware**.
3. Drücke **B**, bis sich das Menü schließt.

Damit entsteht `sdmc:/3ds/dspfirm.cdc`. Ohne diese Datei laufen die Spiele stumm. Hast du das schon einmal für eine andere App gemacht, ist nichts zu tun.

## Schritt 5: PokeDaisy starten

1. Öffne den Homebrew Launcher. Auf einer Konsole, die mit 3ds.hacks.guide eingerichtet wurde, öffnet er sich über die Download-Spiel-App (Download Play).
2. Wähle **PokeDaisy** in der Liste.

Die Bibliothek öffnet sich: Der Touchscreen listet deine Spiele, und der obere Bildschirm zeigt das Spiel unter dem Cursor, mit deinem Spieler, deiner Spielzeit und deinem Team, sobald du es gespielt hast. Bewege dich mit dem Steuerkreuz oder tippe ein Spiel an, dann drücke **A** zum Spielen.

![Der erste Start der Bibliothek ohne Spiele: Der obere Bildschirm sagt, dass deine Spiele nach sdmc:/pokedaisy/roms/ gehören](shots/3ds-library-empty.png "Noch keine Spiele: Die Bibliothek sagt, wohin sie gehören")

Zeigt die Bibliothek **No games yet**, prüfe, ob deine Spiele direkt in `sdmc:/pokedaisy/roms/` liegen und auf `.gba`, `.gb` oder `.gbc` enden.

## Zurechtfinden

| Taste | Was sie tut |
| --- | --- |
| **A** | Das Spiel unter dem Cursor spielen |
| **L** / **R** | Die Ansichten der Bibliothek wechseln: `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | Die nächste Seite: Spiele, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | PokeDaisy schließen und zum Homebrew Launcher zurückkehren |

Im Spiel sind die Tasten des GBA die gleichen Tasten des 3DS (das Schiebepad funktioniert wie das Steuerkreuz), und der Touchscreen ist der Begleiter. Dazu noch:

| Taste | Was sie tut |
| --- | --- |
| **Y** | Das Schnellmenü: PC, `FLY`, dein Fahrrad, Spielstände, Vorspulen und mehr |
| **X** | Auf dem Feld die Tasten an den Begleiter geben (**X** noch einmal gibt sie dem Spiel zurück) |
| **ZR** | Vorspulen, solange du sie hältst (New 3DS) |
| **ZL** | Der vorige Tab des Begleiters (New 3DS) |

Um ein Spiel zu verlassen, nutze `CLOSE GAME` ganz unten in den `SETTINGS` des Begleiters. Die **HOME**-Taste und das Zuklappen der Konsole funktionieren auch.

![Die SETTINGS-Seite der Bibliothek auf einem 3DS: VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT und der Ton](shots/3ds-settings.png "Die SETTINGS der Bibliothek: Aussehen und Bildschirm für alle Spiele")

## Welche Spiele laufen

Jedes Game-Boy-, Game-Boy-Color- oder Game-Boy-Advance-Spiel läuft. Der Begleiter liest die Spiele, die PokeDaisy auf Android kennt: FireRed, LeafGreen, Ruby, Sapphire und Emerald, Red, Blue, Yellow, Gold, Silver und Crystal sowie viele ROM-Hacks ([Unterstützte Spiele](supported-games.md) hat die Liste). DS-Spiele laufen in der 3DS-Version nicht.

## Was wo auf der SD-Karte liegt

| Pfad | Was dort liegt |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | Die App |
| `sdmc:/3ds/dspfirm.cdc` | Die Sound-Firmware (Schritt 4) |
| `sdmc:/pokedaisy/roms/` | Deine Spiele und die `.sav` jedes Spiels daneben |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | Eine Kopie jeder Speicherdatei vor jedem Start, die neuesten 10 pro Spiel |
| `sdmc:/pokedaisy/states/` | Spielstände |
| `sdmc:/pokedaisy/cheats/` | Cheats, eine Datei pro Spiel |
| `sdmc:/pokedaisy/settings.ini` | Deine Einstellungen |
| `sdmc:/pokedaisy/log.txt` | Was der letzte Start getan hat: Hänge die Datei an, wenn du einen Fehler meldest |

## Aktualisieren

Lade die neuere `pokedaisy.3dsx` herunter und kopiere sie über die alte in `sdmc:/3ds/`. Deine Spiele, Speicherdateien, Spielstände und Einstellungen liegen alle in `sdmc:/pokedaisy/` und bleiben, wie sie sind.

## Wenn etwas nicht klappt

- **Kein Ton:** `sdmc:/3ds/dspfirm.cdc` fehlt. Mach Schritt 4.
- **PokeDaisy fehlt im Homebrew Launcher:** Die Datei muss in `sdmc:/3ds/` liegen und auf `.3dsx` enden.
- **Ein Spiel fehlt in der Liste:** Es muss direkt in `sdmc:/pokedaisy/roms/` liegen, nicht in einem Ordner darin und nicht gepackt.
- **Meine Speicherdatei wurde nicht geladen:** Sie braucht genau den Namen des Spiels mit `.sav` am Ende. Jeder Start kopiert die Speicherdatei zuerst nach `sdmc:/pokedaisy/roms/pokedaisy-backups/`, also liegt dort eine ältere Kopie, falls du sie brauchst.
- **Es läuft langsam:** Ein älterer 3DS oder 2DS ist langsamer als die New-Modelle. Stelle in den `SETTINGS` der Bibliothek `SCREEN` auf `PIXEL 1x`, den leichtesten Modus.

Kommst du nicht weiter? [Sag uns Bescheid](https://pokedaisy.com/de/feedback/#bug) und hänge `sdmc:/pokedaisy/log.txt` an.
