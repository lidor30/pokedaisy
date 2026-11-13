<!-- Made from docs/manual/translations/it/3ds.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Installare su Nintendo 3DS

*Installa l'app homebrew PokeDaisy su un 3DS passo dopo passo, aggiungi i tuoi giochi e i salvataggi e attiva l'audio.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · [한국어](INSTALL.ko.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · **Italiano** · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

Anche sul sito: https://pokedaisy.com/it/docs/3ds/

PokeDaisy funziona anche su Nintendo 3DS, come app homebrew: il gioco gira sullo schermo superiore e il compagno vive sul touch screen, con una sua libreria di giochi, un editor dei salvataggi e gli scambi tra salvataggi. Questa pagina ti accompagna da una console con firmware personalizzato fino al tuo primo gioco.

> [!NOTE]
> La versione 3DS è un'anteprima. È fatta per **New Nintendo 3DS, New Nintendo 3DS XL e New Nintendo 2DS XL**. Un Nintendo 3DS, 3DS XL o 2DS originale può avviarla, ma potrebbe andare lento. I suoi menu per ora sono in inglese.

![La libreria di PokeDaisy su 3DS: il gioco scelto sullo schermo superiore, l'elenco dei giochi sul touch screen](../docs/manual/shots/3ds-library.png "La libreria: sopra il gioco sotto il cursore, sotto i tuoi giochi")

## Cosa ti serve

- **Un 3DS con firmware personalizzato (Luma3DS) e l'Homebrew Launcher.** Se la tua console non li ha ancora, segui [3ds.hacks.guide](https://3ds.hacks.guide/). È la guida della community, aggiornata per ogni console e versione del sistema, e prepara tutto quello che serve a questa pagina. Torna qui quando hai finito.
- **La scheda SD** e un computer che la legga (o un'app FTP sul 3DS, se preferisci non togliere la scheda).
- **I tuoi giochi.** PokeDaisy non contiene giochi. Usa ROM che hai estratto tu dalle cartucce che possiedi: file `.gba`, `.gb` o `.gbc`.

## Passo 1: scarica l'app

1. Su un computer, apri la [pagina delle versioni di PokeDaisy](https://github.com/lidor30/pokedaisy/releases).
2. Nella versione più recente che ha un file per 3DS, scarica `pokedaisy.3dsx`.

## Passo 2: copiala sulla scheda SD

1. Spegni il 3DS e inserisci la sua scheda SD nel computer.
2. Apri la cartella `3ds` nella radice della scheda. L'ha creata l'installazione del firmware personalizzato; se non c'è, creala.
3. Copia lì dentro `pokedaisy.3dsx`, così sulla scheda c'è `sdmc:/3ds/pokedaisy.3dsx`.

`sdmc:/` è il nome che il 3DS dà alla radice della scheda SD: sul tuo computer, `sdmc:/3ds/` è semplicemente la cartella `3ds` della scheda.

## Passo 3: aggiungi i giochi e i salvataggi

1. Nella radice della scheda crea una cartella `pokedaisy` e, al suo interno, una cartella `roms`: `sdmc:/pokedaisy/roms/`. (PokeDaisy le crea entrambe la prima volta che parte, se preferisci avviarlo prima una volta.)
2. Copia i tuoi giochi in `roms`. Mettili direttamente nella cartella, non in sottocartelle, ed estraili prima dagli archivi: su 3DS PokeDaisy legge solo file `.gba`, `.gb` e `.gbc` non compressi.
3. Porti un salvataggio da un altro emulatore? Mettilo in `roms` accanto al suo gioco, con lo stesso nome e `.sav` alla fine: `Pokemon Emerald.gba` legge `Pokemon Emerald.sav`. Un salvataggio di RetroArch finisce con `.srm`: rinominalo in `.sav`. Tieni anche una copia del salvataggio sul computer, non si sa mai.

Senza salvataggio il gioco parte da zero, e PokeDaisy crea il suo `.sav` nella stessa cartella la prima volta che salvi nel gioco.

## Passo 4: attiva l'audio

Le app homebrew per 3DS possono riprodurre l'audio solo dopo che il firmware audio della console è stato copiato sulla scheda SD. Va fatto una volta sola:

1. Rimetti la scheda SD, accendi il 3DS, tieni premuto **L**, poi premi **Giù** sulla pulsantiera di comando e **SELECT** per aprire il menu Rosalina.
2. Scegli **Miscellaneous options**, poi **Dump DSP firmware**.
3. Premi **B** finché il menu non si chiude.

Così viene scritto `sdmc:/3ds/dspfirm.cdc`. Senza, i giochi girano senza audio. Se l'hai già fatto per un'altra app, non devi fare niente.

## Passo 5: avvia PokeDaisy

1. Apri l'Homebrew Launcher. Su una console preparata con 3ds.hacks.guide si apre dall'app del gioco in download (Download Play).
2. Scegli **PokeDaisy** nell'elenco.

Si apre la libreria: il touch screen elenca i tuoi giochi e lo schermo superiore mostra quello sotto il cursore, con il tuo giocatore, il tempo di gioco e la squadra dopo che ci hai giocato. Muoviti con la pulsantiera di comando o tocca un gioco, poi premi **A** per giocare.

![Il primo avvio della libreria senza giochi: lo schermo superiore dice di mettere i giochi in sdmc:/pokedaisy/roms/](../docs/manual/shots/3ds-library-empty.png "Ancora nessun gioco: la libreria ti dice dove metterli")

Se la libreria dice **No games yet**, controlla che i giochi siano direttamente in `sdmc:/pokedaisy/roms/` e finiscano con `.gba`, `.gb` o `.gbc`.

## Orientarsi

| Pulsante | Cosa fa |
| --- | --- |
| **A** | Gioca al gioco sotto il cursore |
| **L** / **R** | Cambia la vista della libreria: `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | La pagina successiva: giochi, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | Chiude PokeDaisy e torna all'Homebrew Launcher |

In gioco, i pulsanti del GBA sono gli stessi del 3DS (il pad scorrevole funziona come la pulsantiera di comando) e il touch screen è il compagno. Qualche altro:

| Pulsante | Cosa fa |
| --- | --- |
| **Y** | Il menu rapido: PC, `FLY`, la bici, gli stati salvati, l'avanti veloce e altro |
| **X** | Sul campo, passa i pulsanti al compagno (**X** di nuovo li ridà al gioco) |
| **ZR** | Avanti veloce finché lo tieni premuto (New 3DS) |
| **ZL** | La scheda precedente del compagno (New 3DS) |

Per uscire da un gioco usa `CLOSE GAME` in fondo alle `SETTINGS` del compagno. Funzionano anche il pulsante **HOME** e la chiusura della console.

![La pagina SETTINGS della libreria su 3DS: VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT e l'audio](../docs/manual/shots/3ds-settings.png "Le SETTINGS della libreria: aspetto e schermo per tutti i giochi")

## Quali giochi funzionano

Funziona qualunque gioco Game Boy, Game Boy Color o Game Boy Advance. Il compagno legge i giochi che PokeDaisy conosce su Android: FireRed, LeafGreen, Ruby, Sapphire ed Emerald, Red, Blue, Yellow, Gold, Silver e Crystal, e tante ROM hack ([Giochi supportati](https://pokedaisy.com/docs/supported-games/) ha l'elenco). I giochi DS non girano sulla versione 3DS.

## Dove sono le cose sulla scheda SD

| Percorso | Cosa contiene |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | L'app |
| `sdmc:/3ds/dspfirm.cdc` | Il firmware audio (passo 4) |
| `sdmc:/pokedaisy/roms/` | I tuoi giochi, e il `.sav` di ogni gioco accanto |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | Una copia di ogni salvataggio prima di ogni avvio, le 10 più recenti per gioco |
| `sdmc:/pokedaisy/states/` | Gli stati salvati |
| `sdmc:/pokedaisy/cheats/` | I trucchi, un file per gioco |
| `sdmc:/pokedaisy/settings.ini` | Le tue impostazioni |
| `sdmc:/pokedaisy/log.txt` | Cosa ha fatto l'ultimo avvio: allegalo quando segnali un bug |

## Aggiornare

Scarica il nuovo `pokedaisy.3dsx` e copialo sopra quello vecchio in `sdmc:/3ds/`. Giochi, salvataggi, stati e impostazioni stanno tutti in `sdmc:/pokedaisy/` e restano come sono.

## Se qualcosa non va

- **Niente audio:** manca `sdmc:/3ds/dspfirm.cdc`. Fai il passo 4.
- **PokeDaisy non è nell'Homebrew Launcher:** il file deve essere in `sdmc:/3ds/` e finire con `.3dsx`.
- **Un gioco non è nell'elenco:** deve stare direttamente in `sdmc:/pokedaisy/roms/`, non in una cartella al suo interno e non compresso.
- **Il mio salvataggio non è stato caricato:** deve avere esattamente il nome del gioco con `.sav` alla fine. A ogni avvio il salvataggio viene prima copiato in `sdmc:/pokedaisy/roms/pokedaisy-backups/`, quindi lì trovi una copia più vecchia se ti serve.
- **Va lento:** un 3DS o 2DS originale è più lento dei modelli New. Nelle `SETTINGS` della libreria imposta `SCREEN` su `PIXEL 1x`, la modalità più leggera.

Ancora bloccato? [Faccelo sapere](https://pokedaisy.com/it/feedback/#bug) e allega `sdmc:/pokedaisy/log.txt`.
