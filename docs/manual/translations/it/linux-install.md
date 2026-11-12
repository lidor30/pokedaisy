---
title: Installare su Linux, Steam Deck e console portatili
summary: Scegli il file giusto, installa PokeDaisy su uno Steam Deck, un PC Linux o una console portatile Linux (oppure i suoi core in RetroArch), aggiungi i tuoi giochi e porta i tuoi salvataggi.
section: start
---

PokeDaisy funziona anche su Linux: su uno Steam Deck, su un PC Linux e su console portatili Linux come quelle di TrimUI, come programma a sé oppure come core libretro da caricare in RetroArch o nel frontend della tua console. In entrambi i casi hai lo stesso compagno, lo stesso HUD, lo stesso menu rapido e `EXPAND`. Questa pagina ti accompagna dal download al tuo primo gioco; [Linux, Steam Deck e console portatili](linux.md) spiega i comandi, il menu e tutto il resto quando stai già giocando.

> [!WARNING]
> La versione Linux è una beta. I suoi menu per ora sono in inglese, e alcuni dispositivi di questa pagina non sono ancora stati provati. Se qualcosa va storto, [faccelo sapere](https://pokedaisy.com/it/feedback/#bug) dicendo quale file e quale dispositivo hai usato.

## Cosa ti serve

- **Uno Steam Deck, un PC Linux (x86_64) o una console portatile Linux ARM a 64 bit.** Su un PC: un Linux con glibc 2.35 o più recente e SDL2 (SteamOS, Ubuntu 22.04 e successive e la maggior parte dei desktop attuali li hanno entrambi). Su una console portatile: glibc 2.33 o più recente, che comprende la TrimUI Brick.
- **Un modo per copiarci i file:** il dispositivo stesso, oppure la sua scheda SD in un computer.
- **I tuoi giochi.** PokeDaisy non contiene giochi. Usa ROM che hai estratto tu dalle cartucce che possiedi: file `.gba`, `.gb`, `.gbc` o `.nds`, non compressi.

## Quale file scaricare

Apri la [pagina delle versioni di PokeDaisy](https://github.com/lidor30/pokedaisy/releases) e, nella versione più recente che ha i file per Linux, scarica quello per il tuo dispositivo:

| Il tuo dispositivo | Da scaricare |
| --- | --- |
| Steam Deck, o un PC Linux | `pokedaisy-linux-x86_64.zip` |
| Una console portatile TrimUI (CrossMix, NextUI), come app | `pokedaisy-linux-aarch64.zip` |
| Una console portatile con PortMaster (ROCKNIX, Knulli, muOS e altri) | `pokedaisy-portmaster-aarch64.zip` |
| Una TrimUI Brick con NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch o un altro frontend, su un PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch o un altro frontend, su una console portatile ARM | `pokedaisy-libretro-aarch64.zip` |

I primi tre contengono il programma PokeDaisy con accanto i suoi tre core: uno per Game Boy Advance, uno per Game Boy e Game Boy Color, uno per DS. Gli ultimi due contengono solo i core. Poi segui la parte di questa pagina per il tuo dispositivo.

## Steam Deck e PC Linux

1. Estrai `pokedaisy-linux-x86_64.zip` da qualche parte nella tua cartella home, per esempio in una cartella `PokeDaisy`. Su uno Steam Deck, fallo in modalità Desktop. Ottieni una cartella `pokedaisy-linux-x86_64` con il programma, `pokedaisy-linux`, e i tre core.
2. In quella cartella crea una cartella chiamata `roms` e copiaci i tuoi giochi. (PokeDaisy la crea la prima volta che parte, se preferisci avviarlo prima una volta.)
3. Apri un terminale nella cartella `pokedaisy-linux-x86_64` e avvialo con `./pokedaisy-linux --data .`

Si apre l'elenco dei giochi: scegli un gioco con la pulsantiera di comando (o con i tasti freccia) e premi A (Z sulla tastiera). `--data .` dice a PokeDaisy di tenere `roms`, `saves`, `states` e le sue impostazioni in quella cartella; un collegamento che crei tu deve passare `--data` con il percorso completo della cartella.

### Giocare in modalità Gioco

1. In modalità Desktop apri Steam e scegli **Games** > **Add a Non-Steam Game to My Library**.
2. Scegli **Browse**, vai nella cartella `pokedaisy-linux-x86_64`, scegli `pokedaisy-linux` e aggiungilo.
3. Torna in modalità Gioco e avvia PokeDaisy dalla tua libreria.

Steam lo avvia nella sua cartella, quindi non servono opzioni di avvio. In modalità Gioco occupa tutto lo schermo, e i comandi, il touch screen e l'audio del Deck funzionano come sempre.

## Console portatili TrimUI

`pokedaisy-linux-aarch64.zip` è PokeDaisy come app TrimUI, fatta per CrossMix e NextUI.

1. Estrai lo zip sul computer. Ottieni una cartella `pokedaisy-linux-aarch64`.
2. Inserisci la scheda SD nel computer e copia quella cartella nella cartella `Apps` della scheda (su CrossMix).
3. Copia i tuoi giochi nella cartella `roms` al suo interno (creala se non c'è).
4. Rimetti la scheda e avvia PokeDaisy dal menu Apps.

L'app tiene tutto nella sua cartella e gira a schermo intero. Su CrossMix alza la velocità minima della CPU mentre giochi e la rimette com'era quando esci.

> [!NOTE]
> Il programma non è ancora stato provato come app a sé su una TrimUI (i suoi core sì). Aspettati qualche imperfezione, e facci sapere come va.

## Console portatili con PortMaster

1. Estrai `pokedaisy-portmaster-aarch64.zip` sul computer. Dentro ci sono `PokeDaisy.sh` e una cartella `pokedaisy`.
2. Copia entrambi nella cartella `ports` del tuo firmware sulla scheda SD.
3. I tuoi giochi possono restare dove sono: l'elenco dei giochi mostra `ports/pokedaisy/roms` e le cartelle `gba`, `gb`, `gbc` e `nds` del firmware (e quelle chiamate `Game Boy Advance`, `Game Boy Color`, `Game Boy` e `Nintendo DS`).
4. Avvia PokeDaisy dall'elenco Ports.

PortMaster comunica a PokeDaisy i pulsanti del tuo dispositivo, quindi funzionano senza configurarli. Il pulsante Menu o Guide apre il menu di PokeDaisy, e SELECT e START insieme chiudono, come in ogni port. È stato provato giocando su una GKD 350H con ROCKNIX.

## TrimUI Brick con NXRedux

1. Estrai `PokeDaisy-NXRedux.zip` nella radice della scheda SD. Aggiunge `Emus/tg5040/PDAI.pak` e una cartella `Roms/PokeDaisy (PDAI)`.
2. Metti i tuoi giochi (`.gba`, `.gb`, `.gbc` e `.nds`, non compressi) in `Roms/PokeDaisy (PDAI)`.
3. Avviali dalla raccolta PokeDaisy in NXRedux.

NXRedux gestisce dal suo menu i salvataggi, gli stati salvati, l'avanti veloce, gli shader e la ripresa del gioco. Dai a ogni gioco un nome di file diverso, perché NXRedux dà il nome ai salvataggi solo in base al nome del file.

## RetroArch e altri frontend

1. Estrai `pokedaisy-libretro-x86_64.zip` (PC) o `pokedaisy-libretro-aarch64.zip` (console portatile ARM). La sua cartella contiene i tre core, le loro licenze e `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` fa girare i giochi Game Boy Advance.
   - `pokedaisy_gbc_libretro.so` fa girare i giochi Game Boy e Game Boy Color (e anche Game Boy Advance).
   - `pokedaisy_ds_libretro.so` fa girare i giochi DS.
2. Copia i core nella cartella dei core del tuo frontend. RetroArch mostra dov'è in **Settings** > **Directory** > **Cores**.
3. Carica il tuo gioco con il core PokeDaisy (il core DS compare come PokeDaisy DS).

Il tuo frontend gestisce il salvataggio, gli stati e l'avanti veloce come per qualsiasi core. Le impostazioni del compagno vanno nella cartella dei salvataggi del frontend: `pokedaisy/settings.ini` per i core Game Boy e Game Boy Advance, `pokedaisy-ds/settings.ini` per il core DS. Se passi i core a qualcuno, tieni le licenze insieme a loro.

### Già installato: plorpOS e spruceOS

Su [plorpOS](https://github.com/thwonp/plorpos) e sulle build nightly di [spruceOS](https://github.com/spruceUI/spruceOS), i core Game Boy, Game Boy Color e Game Boy Advance sono già installati, quindi non c'è niente da scaricare: scegli PokeDaisy come core per il tuo gioco (su plorpOS, nella riga Core di uno scaffale Game Boy).

## Porta i tuoi salvataggi

Nel programma PokeDaisy (i primi tre download), il salvataggio di un gioco si trova nella cartella `saves` accanto a `roms`, con il nome completo del file della ROM più `.sav`: per `FireRed.gba` è `saves/FireRed.gba.sav`. Un salvataggio di un altro emulatore funziona se lo copi lì con quel nome, compreso un `.srm` di RetroArch. Prima tieni una copia del tuo vecchio salvataggio da un'altra parte.

Prima che un gioco si apra, PokeDaisy copia il suo salvataggio in `saves/pokedaisy-backups` e tiene le 10 copie più recenti, così se ti serve ne trovi una più vecchia. Gli stati salvati di un altro emulatore non si caricano.

In RetroArch, NXRedux e negli altri frontend, il salvataggio è il file del frontend stesso, dove lo tiene sempre.

## Aggiornare

Scarica lo zip più recente ed estrailo sopra la vecchia cartella (su NXRedux, di nuovo nella radice della scheda SD). Giochi, salvataggi, stati e impostazioni non sono nello zip, quindi restano come sono. In un frontend, copia i nuovi core sopra quelli vecchi.

## Se qualcosa non va

- **Permission denied quando lo avvii:** il programma di estrazione ha perso il permesso di esecuzione del programma. Nella cartella, esegui `chmod +x pokedaisy-linux`.
- **Un errore che nomina `GLIBC_2.35` o `libSDL2`:** il tuo Linux è più vecchio di quanto serve a PokeDaisy, oppure SDL2 non è installato. Installa SDL2 dalla tua distribuzione (su Ubuntu, `sudo apt install libsdl2-2.0-0`), oppure prova un sistema più recente.
- **Un gioco non è nell'elenco:** deve stare direttamente in `roms` (non in una cartella al suo interno), non compresso, e finire con `.gba`, `.gb`, `.gbc` o `.nds`.
- **A e B sono invertiti:** apri il menu (START e SELECT insieme, oppure il pulsante Guide o Menu) e imposta `A/B BUTTONS` su `SWAPPED`.
- **Il mio salvataggio non è stato caricato:** il salvataggio deve avere il nome completo del file della ROM con `.sav` aggiunto, in `saves`. Cerca una copia più vecchia in `saves/pokedaisy-backups`.
- **Il controller non fa niente:** SDL non lo conosce ancora. Metti un `gamecontrollerdb.txt` con la sua mappatura nella cartella dei dati (accanto a `roms`).

Ancora bloccato? [Faccelo sapere](https://pokedaisy.com/it/feedback/#bug) dicendo quale dispositivo e quale file hai usato. Avviato da un terminale, PokeDaisy scrive lì cosa è successo; l'app TrimUI lo scrive in `launch.log` e il port PortMaster in `log.txt`, entrambi nella sua cartella.
