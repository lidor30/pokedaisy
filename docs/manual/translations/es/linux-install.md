---
title: Instalar en Linux, Steam Deck y portátiles
summary: Elige la descarga correcta, instala PokeDaisy en una Steam Deck, un PC con Linux o una portátil con Linux (o sus núcleos en RetroArch), añade tus juegos y trae tus partidas guardadas.
section: start
---

PokeDaisy también funciona en Linux: en una Steam Deck, un PC con Linux y portátiles con Linux como las de TrimUI, ya sea como su propio programa o como núcleos libretro que cargas en RetroArch o en el frontend de tu portátil. De cualquier forma tienes el mismo compañero, el mismo HUD, el mismo menú rápido y `EXPAND`. Esta página te lleva desde la descarga hasta tu primer juego; [Linux, Steam Deck y portátiles](linux.md) cubre los controles, el menú y todo lo demás una vez que estés jugando.

> [!WARNING]
> La versión para Linux es una beta. Sus menús están en inglés por ahora, y algunos dispositivos de esta página aún no se han probado. Si algo sale mal, [avísanos](https://pokedaisy.com/es/feedback/#bug) de qué archivo y qué dispositivo usaste.

## Lo que necesitas

- **Una Steam Deck, un PC con Linux (x86_64) o una portátil Linux ARM de 64 bits.** En un PC: un Linux con glibc 2.35 o más reciente y SDL2 (SteamOS, Ubuntu 22.04 y posteriores y la mayoría de los escritorios actuales tienen los dos). En una portátil: glibc 2.33 o más reciente, lo que incluye la TrimUI Brick.
- **Una forma de copiar archivos al dispositivo:** el propio dispositivo, o su tarjeta SD en un ordenador.
- **Tus propios juegos.** PokeDaisy no incluye ningún juego. Usa ROM que hayas extraído tú de cartuchos que tengas: archivos `.gba`, `.gb`, `.gbc` o `.nds`, sin comprimir.

## Qué archivo descargar

Abre la [página de versiones de PokeDaisy](https://github.com/lidor30/pokedaisy/releases) y, en la versión más reciente que tenga archivos para Linux, descarga el de tu dispositivo:

| Tu dispositivo | Descarga |
| --- | --- |
| Steam Deck, o un PC con Linux | `pokedaisy-linux-x86_64.zip` |
| Una portátil TrimUI (CrossMix, NextUI), como aplicación | `pokedaisy-linux-aarch64.zip` |
| Una portátil con PortMaster (ROCKNIX, Knulli, muOS y otras) | `pokedaisy-portmaster-aarch64.zip` |
| Una TrimUI Brick con NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch u otro frontend, en un PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch u otro frontend, en una portátil ARM | `pokedaisy-libretro-aarch64.zip` |

Los tres primeros traen el programa PokeDaisy con sus tres núcleos al lado: uno para Game Boy Advance, uno para Game Boy y Game Boy Color, y uno para DS. Los dos últimos son solo los núcleos. Después sigue la parte de esta página que corresponde a tu dispositivo.

## Steam Deck y PC con Linux

1. Extrae `pokedaisy-linux-x86_64.zip` en algún lugar de tu carpeta personal, por ejemplo en una carpeta `PokeDaisy`. En una Steam Deck, hazlo en el modo escritorio. Obtienes una carpeta `pokedaisy-linux-x86_64` con el programa, `pokedaisy-linux`, y los tres núcleos.
2. En esa carpeta, crea una carpeta llamada `roms` y copia tus juegos dentro. (PokeDaisy la crea la primera vez que se abre, si prefieres abrirlo una vez antes.)
3. Abre una terminal en la carpeta `pokedaisy-linux-x86_64` y ábrelo con `./pokedaisy-linux --data .`

Se abre la lista de juegos: elige un juego con el +Panel de Control (o las flechas) y pulsa A (Z en un teclado). `--data .` le dice a PokeDaisy que guarde `roms`, `saves`, `states` y sus ajustes en esa carpeta; un acceso directo que crees tú debe pasar `--data` con la ruta completa de la carpeta.

### Jugar en el modo de juego

1. En el modo escritorio, abre Steam y elige **Juegos** > **Añadir un juego que no es de Steam a mi biblioteca**.
2. Pulsa **Examinar**, ve a la carpeta `pokedaisy-linux-x86_64`, elige `pokedaisy-linux` y añádelo.
3. Vuelve al modo de juego y abre PokeDaisy desde tu biblioteca.

Steam lo abre en su propia carpeta, así que no hacen falta opciones de lanzamiento. En el modo de juego ocupa toda la pantalla, y los controles, la pantalla táctil y el sonido de la Deck funcionan como siempre.

## Portátiles TrimUI

`pokedaisy-linux-aarch64.zip` es PokeDaisy como aplicación de TrimUI, hecha para CrossMix y NextUI.

1. Extrae el zip en tu ordenador. Obtienes una carpeta `pokedaisy-linux-aarch64`.
2. Pon la tarjeta SD en tu ordenador y copia esa carpeta en la carpeta `Apps` de la tarjeta (en CrossMix).
3. Copia tus juegos en la carpeta `roms` que hay dentro (créala si no está).
4. Vuelve a poner la tarjeta y abre PokeDaisy desde el menú Apps.

La aplicación guarda todo en su propia carpeta y funciona a pantalla completa. En CrossMix sube la velocidad mínima de la CPU mientras juegas y la devuelve a su valor al salir.

> [!NOTE]
> El programa aún no se ha ejecutado como aplicación independiente en una TrimUI (sus núcleos sí). Espera algún fallo y cuéntanos cómo te va.

## Portátiles con PortMaster

1. Extrae `pokedaisy-portmaster-aarch64.zip` en tu ordenador. Dentro hay un `PokeDaisy.sh` y una carpeta `pokedaisy`.
2. Copia los dos en la carpeta `ports` de tu firmware en la tarjeta SD.
3. Tus juegos pueden quedarse donde están: la lista de juegos muestra `ports/pokedaisy/roms` y las carpetas `gba`, `gb`, `gbc` y `nds` del propio firmware (y las llamadas `Game Boy Advance`, `Game Boy Color`, `Game Boy` y `Nintendo DS`).
4. Abre PokeDaisy desde la lista de Ports.

PortMaster le dice a PokeDaisy cuáles son los botones de tu dispositivo, así que funcionan sin configurar nada. El botón Menu o Guide abre el menú de PokeDaisy, y SELECT y START a la vez cierran, como en cualquier port. Se ha jugado en una GKD 350H con ROCKNIX.

## TrimUI Brick con NXRedux

1. Extrae `PokeDaisy-NXRedux.zip` en la raíz de tu tarjeta SD. Añade `Emus/tg5040/PDAI.pak` y una carpeta `Roms/PokeDaisy (PDAI)`.
2. Pon tus juegos (`.gba`, `.gb`, `.gbc` y `.nds`, sin comprimir) en `Roms/PokeDaisy (PDAI)`.
3. Ábrelos desde la colección PokeDaisy de NXRedux.

NXRedux se encarga de tus partidas, los estados guardados, el avance rápido, los shaders y la reanudación desde su propio menú. Dale a cada juego un nombre de archivo distinto, porque NXRedux nombra las partidas solo por el nombre del archivo.

## RetroArch y otros frontends

1. Extrae `pokedaisy-libretro-x86_64.zip` (PC) o `pokedaisy-libretro-aarch64.zip` (portátil ARM). Su carpeta contiene los tres núcleos, sus licencias y `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` juega juegos de Game Boy Advance.
   - `pokedaisy_gbc_libretro.so` juega juegos de Game Boy y Game Boy Color (y también de Game Boy Advance).
   - `pokedaisy_ds_libretro.so` juega juegos de DS.
2. Copia los núcleos en la carpeta de núcleos de tu frontend. RetroArch te muestra dónde está en **Settings** > **Directory** > **Cores**.
3. Carga tu juego con el núcleo PokeDaisy (el núcleo de DS aparece como PokeDaisy DS).

Tu frontend se encarga de la partida, los estados y el avance rápido como con cualquier núcleo. Los ajustes del compañero van en la carpeta de partidas del frontend: `pokedaisy/settings.ini` para los núcleos de Game Boy y Game Boy Advance, `pokedaisy-ds/settings.ini` para el núcleo de DS. Si pasas los núcleos a otra persona, incluye sus licencias.

### Ya instalado: plorpOS y spruceOS

En [plorpOS](https://github.com/thwonp/plorpos) y en las versiones nightly de [spruceOS](https://github.com/spruceUI/spruceOS), los núcleos de Game Boy, Game Boy Color y Game Boy Advance ya vienen instalados, así que no hay nada que descargar: elige PokeDaisy como núcleo para tu juego (en plorpOS, en la fila Core de un estante de Game Boy).

## Trae tus partidas

En el programa PokeDaisy (las tres primeras descargas), la partida de un juego está en la carpeta `saves` junto a `roms`, con el nombre completo del archivo ROM más `.sav`: para `FireRed.gba` es `saves/FireRed.gba.sav`. Una partida de otro emulador funciona si la copias ahí con ese nombre, también un `.srm` de RetroArch. Guarda antes una copia de tu partida antigua en otro sitio.

Antes de abrir un juego, PokeDaisy copia su partida a `saves/pokedaisy-backups` y guarda las 10 copias más recientes, así que tienes una anterior ahí si la necesitas. Los estados guardados de otro emulador no se cargan.

En RetroArch, NXRedux y los demás frontends, la partida es el propio archivo del frontend, donde siempre lo guarda.

## Actualizar

Descarga el zip nuevo y extráelo encima de la carpeta antigua (en NXRedux, de nuevo en la raíz de la tarjeta SD). Tus juegos, partidas, estados y ajustes no están en el zip, así que se quedan como están. En un frontend, copia los núcleos nuevos encima de los antiguos.

## Si algo falla

- **Permission denied al abrirlo:** el programa de extracción le quitó el permiso de ejecución. En la carpeta, ejecuta `chmod +x pokedaisy-linux`.
- **Un error que nombra `GLIBC_2.35` o `libSDL2`:** tu Linux es más antiguo de lo que PokeDaisy necesita, o SDL2 no está instalado. Instala SDL2 desde tu distribución (en Ubuntu, `sudo apt install libsdl2-2.0-0`) o prueba un sistema más reciente.
- **Un juego no aparece en la lista:** tiene que estar directamente en `roms` (no en una carpeta dentro), sin comprimir, y terminar en `.gba`, `.gb`, `.gbc` o `.nds`.
- **A y B están al revés:** abre el menú (START y SELECT a la vez, o el botón Guide o Menu) y pon `A/B BUTTONS` en `SWAPPED`.
- **Mi partida no se cargó:** la partida necesita el nombre completo del archivo ROM con `.sav` añadido, en `saves`. Busca una copia anterior en `saves/pokedaisy-backups`.
- **El mando no hace nada:** SDL aún no lo conoce. Pon un `gamecontrollerdb.txt` con su configuración en la carpeta de datos (junto a `roms`).

¿Sigues atascado? [Avísanos](https://pokedaisy.com/es/feedback/#bug) de qué dispositivo y qué archivo usaste. Si lo abriste desde una terminal, PokeDaisy escribe ahí lo que pasó; la aplicación de TrimUI lo escribe en `launch.log` y el port de PortMaster en `log.txt`, los dos en su carpeta.
