---
title: Auf Linux, Steam Deck und Handhelds installieren
summary: Wähle den richtigen Download, installiere PokeDaisy auf einem Steam Deck, einem Linux-PC oder einem Linux-Handheld (oder seine Cores in RetroArch), füge deine Spiele hinzu und bring deine Speicherdateien mit.
section: start
---

PokeDaisy läuft auch unter Linux: auf einem Steam Deck, einem Linux-PC und Linux-Handhelds wie denen von TrimUI, entweder als eigenes Programm oder als libretro-Cores, die du in RetroArch oder im Frontend deines Handhelds lädst. So oder so bekommst du denselben Begleiter, dasselbe HUD, dasselbe Schnellmenü und `EXPAND`. Diese Seite bringt dich vom Download bis zu deinem ersten Spiel; [Linux, Steam Deck und Handhelds](linux.md) erklärt die Steuerung, das Menü und alles andere, sobald du spielst.

> [!WARNING]
> Die Linux-Version ist eine Beta. Ihre Menüs sind vorerst auf Englisch, und einige Geräte auf dieser Seite wurden noch nicht ausprobiert. Wenn etwas schiefgeht, [sag uns](https://pokedaisy.com/de/feedback/#bug), welche Datei und welches Gerät du benutzt hast.

## Was du brauchst

- **Ein Steam Deck, einen Linux-PC (x86_64) oder einen 64-Bit-ARM-Linux-Handheld.** Auf einem PC: ein Linux mit glibc 2.35 oder neuer und SDL2 (SteamOS, Ubuntu 22.04 und neuer und die meisten aktuellen Desktops haben beides). Auf einem Handheld: glibc 2.33 oder neuer, was den TrimUI Brick einschließt.
- **Eine Möglichkeit, Dateien darauf zu kopieren:** das Gerät selbst oder seine SD-Karte in einem Computer.
- **Deine eigenen Spiele.** PokeDaisy enthält keine Spiele. Nutze ROMs, die du selbst aus Modulen ausgelesen hast, die dir gehören: Dateien mit der Endung `.gba`, `.gb`, `.gbc` oder `.nds`, entpackt.

## Welche Datei du herunterlädst

Öffne die [Release-Seite von PokeDaisy](https://github.com/lidor30/pokedaisy/releases) und lade beim neuesten Release, das Linux-Dateien anbietet, die für dein Gerät herunter:

| Dein Gerät | Download |
| --- | --- |
| Steam Deck oder ein Linux-PC | `pokedaisy-linux-x86_64.zip` |
| Ein TrimUI-Handheld (CrossMix, NextUI), als App | `pokedaisy-linux-aarch64.zip` |
| Ein Handheld mit PortMaster (ROCKNIX, Knulli, muOS und andere) | `pokedaisy-portmaster-aarch64.zip` |
| Ein TrimUI Brick mit NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch oder ein anderes Frontend, auf einem PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch oder ein anderes Frontend, auf einem ARM-Handheld | `pokedaisy-libretro-aarch64.zip` |

Die ersten drei enthalten das Programm PokeDaisy mit seinen drei Cores daneben: einen für Game Boy Advance, einen für Game Boy und Game Boy Color, einen für DS. Die letzten beiden sind nur die Cores. Folge dann dem Teil dieser Seite für dein Gerät.

## Steam Deck und Linux-PCs

1. Entpacke `pokedaisy-linux-x86_64.zip` irgendwo in deinem Home-Ordner, zum Beispiel in einen Ordner `PokeDaisy`. Auf einem Steam Deck machst du das im Desktopmodus. Du bekommst einen Ordner `pokedaisy-linux-x86_64` mit dem Programm, `pokedaisy-linux`, und den drei Cores.
2. Lege in diesem Ordner einen Ordner `roms` an und kopiere deine Spiele hinein. (PokeDaisy legt ihn beim ersten Start selbst an, falls du es lieber erst einmal startest.)
3. Öffne ein Terminal im Ordner `pokedaisy-linux-x86_64` und starte es mit `./pokedaisy-linux --data .`

Die Spieleliste öffnet sich: Wähle ein Spiel mit dem Steuerkreuz (oder den Pfeiltasten) und drücke A (Z auf einer Tastatur). `--data .` sagt PokeDaisy, dass es `roms`, `saves`, `states` und seine Einstellungen in diesem Ordner aufbewahren soll; eine Verknüpfung, die du selbst anlegst, sollte `--data` mit dem vollständigen Pfad des Ordners übergeben.

### Im Spielmodus spielen

1. Öffne im Desktopmodus Steam und wähle **Spiele** > **Ein Nicht-Steam-Spiel zu meiner Bibliothek hinzufügen**.
2. Wähle **Durchsuchen**, gehe in den Ordner `pokedaisy-linux-x86_64`, wähle `pokedaisy-linux` und füge es hinzu.
3. Wechsle zurück in den Spielmodus und starte PokeDaisy aus deiner Bibliothek.

Steam startet es in seinem eigenen Ordner, du brauchst also keine Startoptionen. Im Spielmodus füllt es den Bildschirm, und die Steuerung, der Touchscreen und der Ton des Decks funktionieren wie gewohnt.

## TrimUI-Handhelds

`pokedaisy-linux-aarch64.zip` ist PokeDaisy als TrimUI-App, gebaut für CrossMix und NextUI.

1. Entpacke die Zip-Datei auf deinem Computer. Du bekommst einen Ordner `pokedaisy-linux-aarch64`.
2. Stecke die SD-Karte in deinen Computer und kopiere diesen Ordner in den Ordner `Apps` der Karte (bei CrossMix).
3. Kopiere deine Spiele in den Ordner `roms` darin (lege ihn an, falls er fehlt).
4. Stecke die Karte wieder ein und starte PokeDaisy aus dem Apps-Menü.

Die App bewahrt alles in ihrem eigenen Ordner auf und läuft im Vollbild. Unter CrossMix hebt sie die niedrigste Geschwindigkeit der CPU an, solange du spielst, und setzt sie beim Beenden zurück.

> [!NOTE]
> Das Programm ist auf einem TrimUI noch nicht als eigenständige App gelaufen (seine Cores schon). Rechne mit Ecken und Kanten, und erzähl uns, wie es läuft.

## Handhelds mit PortMaster

1. Entpacke `pokedaisy-portmaster-aarch64.zip` auf deinem Computer. Darin sind `PokeDaisy.sh` und ein Ordner `pokedaisy`.
2. Kopiere beides in den Ordner `ports` deiner Firmware auf der SD-Karte.
3. Deine Spiele können bleiben, wo sie sind: Die Spieleliste zeigt `ports/pokedaisy/roms` und die eigenen Ordner `gba`, `gb`, `gbc` und `nds` der Firmware (und die mit den Namen `Game Boy Advance`, `Game Boy Color`, `Game Boy` und `Nintendo DS`).
4. Starte PokeDaisy aus der Ports-Liste.

PortMaster teilt PokeDaisy die Tasten deines Geräts mit, sie funktionieren also ohne Einrichtung. Die Menu- oder Guide-Taste öffnet das Menü von PokeDaisy, und SELECT und START zusammen beenden es, wie bei jedem Port. Gespielt wurde es auf einem GKD 350H mit ROCKNIX.

## TrimUI Brick mit NXRedux

1. Entpacke `PokeDaisy-NXRedux.zip` ganz oben auf deiner SD-Karte. Das fügt `Emus/tg5040/PDAI.pak` und einen Ordner `Roms/PokeDaisy (PDAI)` hinzu.
2. Lege deine Spiele (`.gba`, `.gb`, `.gbc` und `.nds`, entpackt) in `Roms/PokeDaisy (PDAI)`.
3. Starte sie aus der PokeDaisy-Sammlung in NXRedux.

NXRedux kümmert sich über sein eigenes Menü um deine Speicherdateien, Spielstände, das Vorspulen, Shader und das Fortsetzen. Gib jedem Spiel einen anderen Dateinamen, denn NXRedux benennt Speicherdateien allein nach dem Dateinamen.

## RetroArch und andere Frontends

1. Entpacke `pokedaisy-libretro-x86_64.zip` (PC) oder `pokedaisy-libretro-aarch64.zip` (ARM-Handheld). Sein Ordner enthält die drei Cores, ihre Lizenzen und `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` spielt Game-Boy-Advance-Spiele.
   - `pokedaisy_gbc_libretro.so` spielt Game-Boy- und Game-Boy-Color-Spiele (und auch Game-Boy-Advance-Spiele).
   - `pokedaisy_ds_libretro.so` spielt DS-Spiele.
2. Kopiere die Cores in den Cores-Ordner deines Frontends. RetroArch zeigt, wo er ist, unter **Settings** > **Directory** > **Cores**.
3. Lade dein Spiel mit dem PokeDaisy-Core (der DS-Core heißt dort PokeDaisy DS).

Dein Frontend kümmert sich um die Speicherdatei, die Spielstände und das Vorspulen wie bei jedem Core. Die Einstellungen des Begleiters landen im Speicherordner des Frontends: `pokedaisy/settings.ini` für die Game-Boy- und Game-Boy-Advance-Cores, `pokedaisy-ds/settings.ini` für den DS-Core. Behalte die Lizenzen bei den Cores, wenn du sie weitergibst.

### Schon installiert: plorpOS und spruceOS

Unter [plorpOS](https://github.com/thwonp/plorpos) und in den Nightly-Builds von [spruceOS](https://github.com/spruceUI/spruceOS) sind die Cores für Game Boy, Game Boy Color und Game Boy Advance schon installiert, es gibt also nichts herunterzuladen: Wähle PokeDaisy als Core für dein Spiel (unter plorpOS in der Core-Zeile eines Game-Boy-Regals).

## Deine Speicherdateien mitbringen

Im Programm PokeDaisy (die ersten drei Downloads) liegt die Speicherdatei eines Spiels im Ordner `saves` neben `roms`, benannt nach dem ganzen Dateinamen der ROM plus `.sav`: Für `FireRed.gba` ist das `saves/FireRed.gba.sav`. Eine Speicherdatei aus einem anderen Emulator funktioniert, wenn du sie unter diesem Namen dorthin kopierst, auch eine `.srm` von RetroArch. Bewahre vorher eine Kopie deiner alten Speicherdatei an einem anderen Ort auf.

Bevor sich ein Spiel öffnet, kopiert PokeDaisy seine Speicherdatei nach `saves/pokedaisy-backups` und behält die neuesten 10 Kopien, also liegt dort eine ältere, falls du sie brauchst. Spielstände aus einem anderen Emulator lassen sich nicht laden.

In RetroArch, NXRedux und den anderen Frontends ist die Speicherdatei die eigene Datei des Frontends, dort, wo es sie immer aufbewahrt.

## Aktualisieren

Lade die neuere Zip-Datei herunter und entpacke sie über den alten Ordner (bei NXRedux wieder ganz oben auf der SD-Karte). Deine Spiele, Speicherdateien, Spielstände und Einstellungen sind nicht in der Zip-Datei, sie bleiben also, wie sie sind. In einem Frontend kopierst du die neuen Cores über die alten.

## Wenn etwas nicht klappt

- **Permission denied beim Starten:** Das Entpackprogramm hat dem Programm die Ausführungsrechte genommen. Führe im Ordner `chmod +x pokedaisy-linux` aus.
- **Ein Fehler, der `GLIBC_2.35` oder `libSDL2` nennt:** Dein Linux ist älter, als PokeDaisy braucht, oder SDL2 ist nicht installiert. Installiere SDL2 aus deiner Distribution (unter Ubuntu `sudo apt install libsdl2-2.0-0`) oder probiere ein neueres System.
- **Ein Spiel fehlt in der Liste:** Es muss direkt in `roms` liegen (nicht in einem Ordner darin), entpackt sein und auf `.gba`, `.gb`, `.gbc` oder `.nds` enden.
- **A und B sind vertauscht:** Öffne das Menü (START und SELECT zusammen, oder die Guide- oder Menu-Taste) und stelle `A/B BUTTONS` auf `SWAPPED`.
- **Meine Speicherdatei wurde nicht geladen:** Sie braucht den ganzen Dateinamen der ROM mit `.sav` dahinter, in `saves`. Sieh in `saves/pokedaisy-backups` nach einer älteren Kopie.
- **Der Controller tut nichts:** SDL kennt ihn noch nicht. Lege eine `gamecontrollerdb.txt` mit seiner Belegung in den Datenordner (neben `roms`).

Kommst du nicht weiter? [Sag uns](https://pokedaisy.com/de/feedback/#bug), welches Gerät und welche Datei du benutzt hast. Aus einem Terminal gestartet, schreibt PokeDaisy dort hin, was passiert ist; die TrimUI-App schreibt es in `launch.log` und der PortMaster-Port in `log.txt`, beide in ihrem Ordner.
