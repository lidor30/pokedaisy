<!-- Made from docs/manual/translations/es/3ds.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Instalar en una Nintendo 3DS

*Instala la aplicación homebrew PokeDaisy en una 3DS paso a paso, añade tus juegos y tus partidas guardadas, y activa el sonido.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · [한국어](INSTALL.ko.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · **Español** · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

También en la web: https://pokedaisy.com/es/docs/3ds/

PokeDaisy también funciona en Nintendo 3DS, como aplicación homebrew: tu juego se ve en la pantalla superior y el compañero vive en la pantalla táctil, con su propia biblioteca de juegos, un editor de partidas guardadas e intercambios entre partidas. Esta página te lleva desde una consola con firmware personalizado hasta tu primer juego.

> [!NOTE]
> La versión para 3DS es un avance temprano. Está hecha para **New Nintendo 3DS, New Nintendo 3DS XL y New Nintendo 2DS XL**. Una Nintendo 3DS, 3DS XL o 2DS original puede abrirla, pero puede ir lenta. Sus menús están en inglés por ahora.

![La biblioteca de PokeDaisy en una 3DS: el juego elegido en la pantalla superior, la lista de juegos en la pantalla táctil](../docs/manual/shots/3ds-library.png "La biblioteca: arriba el juego bajo el cursor, abajo tus juegos")

## Lo que necesitas

- **Una 3DS con firmware personalizado (Luma3DS) y el Homebrew Launcher.** Si tu consola aún no los tiene, sigue [3ds.hacks.guide](https://3ds.hacks.guide/). Es la guía de la comunidad, al día para cada consola y versión del sistema, y prepara todo lo que esta página necesita. Vuelve aquí cuando termines.
- **La tarjeta SD** y un ordenador que pueda leerla (o una aplicación FTP en la 3DS, si prefieres no sacar la tarjeta).
- **Tus propios juegos.** PokeDaisy no incluye ningún juego. Usa ROM que hayas extraído tú de cartuchos que tengas: archivos `.gba`, `.gb` o `.gbc`.

## Paso 1: consigue la aplicación

1. En un ordenador, abre la [página de versiones de PokeDaisy](https://github.com/lidor30/pokedaisy/releases).
2. En la versión más reciente que tenga un archivo para 3DS, descarga `pokedaisy.3dsx`.

## Paso 2: cópiala a la tarjeta SD

1. Apaga la 3DS y pon su tarjeta SD en tu ordenador.
2. Abre la carpeta `3ds` en la raíz de la tarjeta. La creó la instalación del firmware personalizado; si no está, créala.
3. Copia dentro `pokedaisy.3dsx`, para que la tarjeta tenga `sdmc:/3ds/pokedaisy.3dsx`.

`sdmc:/` es como la 3DS llama a la raíz de la tarjeta SD: en tu ordenador, `sdmc:/3ds/` es simplemente la carpeta `3ds` de la tarjeta.

## Paso 3: añade tus juegos y tus partidas

1. En la raíz de la tarjeta, crea una carpeta `pokedaisy` y, dentro, una carpeta `roms`: `sdmc:/pokedaisy/roms/`. (PokeDaisy crea las dos la primera vez que se abre, si prefieres abrirlo una vez antes.)
2. Copia tus juegos en `roms`. Ponlos directamente en la carpeta, no en subcarpetas, y descomprímelos antes: en la 3DS, PokeDaisy solo lee archivos `.gba`, `.gb` y `.gbc` sin comprimir.
3. ¿Traes una partida de otro emulador? Ponla en `roms` junto a su juego, con el mismo nombre y `.sav` al final: `Pokemon Emerald.gba` lee `Pokemon Emerald.sav`. Una partida de RetroArch termina en `.srm`: cámbiale el nombre a `.sav`. Guarda también una copia de la partida en tu ordenador, por si acaso.

Sin partida guardada, el juego empieza de cero y PokeDaisy crea su `.sav` en la misma carpeta la primera vez que guardas en el juego.

## Paso 4: activa el sonido

El homebrew de 3DS solo puede reproducir sonido cuando el firmware de audio de la consola está copiado en la tarjeta SD. Solo hay que hacerlo una vez:

1. Vuelve a poner la tarjeta SD, enciende la 3DS, mantén pulsado **L** y luego pulsa **Abajo** en el +Panel de Control y **SELECT** para abrir el menú Rosalina.
2. Elige **Miscellaneous options** y después **Dump DSP firmware**.
3. Pulsa **B** hasta que se cierre el menú.

Así se escribe `sdmc:/3ds/dspfirm.cdc`. Sin él, los juegos van sin sonido. Si ya lo hiciste antes para otra aplicación, no tienes que hacer nada.

## Paso 5: abre PokeDaisy

1. Abre el Homebrew Launcher. En una consola preparada con 3ds.hacks.guide, se abre desde la aplicación de juego en descarga (Download Play).
2. Elige **PokeDaisy** en la lista.

Se abre la biblioteca: la pantalla táctil muestra tus juegos y la pantalla superior el que está bajo el cursor, con tu jugador, tu tiempo de juego y tu equipo una vez que lo hayas jugado. Muévete con el +Panel de Control o toca un juego, y pulsa **A** para jugar.

![El primer inicio de la biblioteca sin juegos: la pantalla superior dice que pongas tus juegos en sdmc:/pokedaisy/roms/](../docs/manual/shots/3ds-library-empty.png "Aún no hay juegos: la biblioteca te dice dónde van")

Si la biblioteca dice **No games yet**, comprueba que tus juegos estén directamente en `sdmc:/pokedaisy/roms/` y terminen en `.gba`, `.gb` o `.gbc`.

## Cómo moverte

| Botón | Qué hace |
| --- | --- |
| **A** | Jugar al juego bajo el cursor |
| **L** / **R** | Cambiar las vistas de la biblioteca: `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | La página siguiente: juegos, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | Cerrar PokeDaisy y volver al Homebrew Launcher |

Mientras juegas, los botones de la GBA son los de la 3DS (el botón deslizante sirve como +Panel de Control) y la pantalla táctil es el compañero. Algunos más:

| Botón | Qué hace |
| --- | --- |
| **Y** | El menú rápido: PC, `FLY`, tu bici, estados guardados, avance rápido y más |
| **X** | En el campo, pasar los botones al compañero (**X** otra vez se los devuelve al juego) |
| **ZR** | Avance rápido mientras lo mantienes (New 3DS) |
| **ZL** | La pestaña anterior del compañero (New 3DS) |

Para salir de un juego, usa `CLOSE GAME` al final de los `SETTINGS` del compañero. El botón **HOME** y cerrar la consola también funcionan.

![La página SETTINGS de la biblioteca en una 3DS: VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT y el sonido](../docs/manual/shots/3ds-settings.png "Los SETTINGS de la biblioteca: el aspecto y la pantalla para todos los juegos")

## Qué juegos funcionan

Funciona cualquier juego de Game Boy, Game Boy Color o Game Boy Advance. El compañero lee los juegos que PokeDaisy conoce en Android: FireRed, LeafGreen, Ruby, Sapphire y Emerald, Red, Blue, Yellow, Gold, Silver y Crystal, y muchos ROM hacks ([Juegos compatibles](https://pokedaisy.com/docs/supported-games/) tiene la lista). Los juegos de DS no funcionan en la versión para 3DS.

## Dónde está cada cosa en la tarjeta SD

| Ruta | Qué hay |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | La aplicación |
| `sdmc:/3ds/dspfirm.cdc` | El firmware de audio (paso 4) |
| `sdmc:/pokedaisy/roms/` | Tus juegos, y el `.sav` de cada uno a su lado |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | Una copia de cada partida antes de cada inicio, las 10 más recientes por juego |
| `sdmc:/pokedaisy/states/` | Los estados guardados |
| `sdmc:/pokedaisy/cheats/` | Los trucos, un archivo por juego |
| `sdmc:/pokedaisy/settings.ini` | Tus ajustes |
| `sdmc:/pokedaisy/log.txt` | Lo que hizo el último inicio: adjúntalo cuando informes de un fallo |

## Actualizar

Descarga el `pokedaisy.3dsx` nuevo y cópialo encima del antiguo en `sdmc:/3ds/`. Tus juegos, partidas, estados y ajustes están todos en `sdmc:/pokedaisy/` y se quedan como están.

## Si algo falla

- **No hay sonido:** falta `sdmc:/3ds/dspfirm.cdc`. Haz el paso 4.
- **PokeDaisy no aparece en el Homebrew Launcher:** el archivo tiene que estar en `sdmc:/3ds/` y terminar en `.3dsx`.
- **Un juego no aparece en la lista:** tiene que estar directamente en `sdmc:/pokedaisy/roms/`, no en una carpeta dentro y no comprimido.
- **Mi partida no se cargó:** necesita exactamente el nombre del juego con `.sav` al final. Cada inicio copia antes la partida a `sdmc:/pokedaisy/roms/pokedaisy-backups/`, así que ahí tienes una copia anterior si la necesitas.
- **Va lento:** una 3DS o 2DS original es más lenta que los modelos New. En los `SETTINGS` de la biblioteca, pon `SCREEN` en `PIXEL 1x`, el modo más ligero.

¿Sigues atascado? [Avísanos](https://pokedaisy.com/es/feedback/#bug) y adjunta `sdmc:/pokedaisy/log.txt`.
