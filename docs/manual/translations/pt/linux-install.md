---
title: Instalar no Linux, Steam Deck e portáteis
summary: Escolha a transferência certa, instale o PokeDaisy num Steam Deck, num PC com Linux ou numa consola portátil com Linux (ou os núcleos dele no RetroArch), junte os seus jogos e traga os seus jogos guardados.
section: start
---

O PokeDaisy também corre no Linux: num Steam Deck, num PC com Linux e em consolas portáteis com Linux como as da TrimUI, seja como programa próprio, seja como núcleos libretro que carrega no RetroArch ou no frontend da sua consola portátil. Seja como for, tem o mesmo companheiro, o mesmo HUD, o mesmo menu rápido e o `EXPAND`. Esta página leva-o da transferência até ao seu primeiro jogo; [Linux, Steam Deck e portáteis](linux.md) explica os controlos, o menu e tudo o resto depois de começar a jogar.

> [!WARNING]
> A versão para Linux é uma beta. Os menus estão em inglês, para já, e alguns dispositivos desta página ainda não foram testados. Se algo correr mal, [diga-nos](https://pokedaisy.com/pt/feedback/#bug) que ficheiro e que dispositivo usou.

## O que precisa

- **Um Steam Deck, um PC com Linux (x86_64) ou uma consola portátil com Linux ARM de 64 bits.** Num PC: um Linux com glibc 2.35 ou mais recente e SDL2 (o SteamOS, o Ubuntu 22.04 e mais recentes e a maioria dos ambientes de trabalho atuais têm os dois). Numa consola portátil: glibc 2.33 ou mais recente, o que inclui o TrimUI Brick.
- **Uma forma de copiar ficheiros para lá:** o próprio dispositivo, ou o cartão SD dele num computador.
- **Os seus próprios jogos.** O PokeDaisy não traz nenhum jogo. Use ROMs que extraiu dos cartuchos que tem: ficheiros `.gba`, `.gb`, `.gbc` ou `.nds`, descomprimidos.

## Que ficheiro transferir

Abra a [página de versões do PokeDaisy](https://github.com/lidor30/pokedaisy/releases) e, na versão mais recente que tenha ficheiros para Linux, transfira o do seu dispositivo:

| O seu dispositivo | Transferência |
| --- | --- |
| Steam Deck ou um PC com Linux | `pokedaisy-linux-x86_64.zip` |
| Uma consola portátil da TrimUI (CrossMix, NextUI), como aplicação | `pokedaisy-linux-aarch64.zip` |
| Uma consola portátil com PortMaster (ROCKNIX, Knulli, muOS e outros) | `pokedaisy-portmaster-aarch64.zip` |
| Um TrimUI Brick com NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch ou outro frontend, num PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch ou outro frontend, numa consola portátil ARM | `pokedaisy-libretro-aarch64.zip` |

Os três primeiros trazem o programa PokeDaisy com os seus três núcleos ao lado: um para Game Boy Advance, um para Game Boy e Game Boy Color, um para DS. Os dois últimos são apenas os núcleos. Depois, siga a parte desta página do seu dispositivo.

## Steam Deck e PCs com Linux

1. Extraia `pokedaisy-linux-x86_64.zip` algures na sua pasta pessoal, por exemplo numa pasta `PokeDaisy`. No Steam Deck, faça-o no Desktop Mode. Fica com uma pasta `pokedaisy-linux-x86_64` com o programa, `pokedaisy-linux`, e os três núcleos.
2. Nessa pasta, crie uma pasta chamada `roms` e copie os seus jogos para dentro dela. (O PokeDaisy cria-a na primeira vez que abre, se preferir abri-lo uma vez antes.)
3. Abra um terminal na pasta `pokedaisy-linux-x86_64` e inicie-o com `./pokedaisy-linux --data .`

A lista de jogos abre: escolha um jogo com a cruz direcional (ou as setas) e prima A (Z no teclado). `--data .` diz ao PokeDaisy para guardar `roms`, `saves`, `states` e as suas definições nessa pasta; um atalho que crie deve passar `--data` com o caminho completo da pasta.

### Jogar no Game Mode

1. No Desktop Mode, abra a Steam e escolha **Games** > **Add a Non-Steam Game to My Library**.
2. Escolha **Browse**, vá até à pasta `pokedaisy-linux-x86_64`, escolha `pokedaisy-linux` e adicione-o.
3. Volte ao Game Mode e inicie o PokeDaisy a partir da sua biblioteca.

A Steam inicia-o na sua própria pasta, por isso não são precisas opções de arranque. No Game Mode ocupa o ecrã inteiro, e os controlos, o ecrã tátil e o som do Deck funcionam como de costume.

## Consolas portáteis da TrimUI

`pokedaisy-linux-aarch64.zip` é o PokeDaisy como aplicação da TrimUI, feita para CrossMix e NextUI.

1. Extraia o zip no computador. Fica com uma pasta `pokedaisy-linux-aarch64`.
2. Coloque o cartão SD no computador e copie essa pasta para a pasta `Apps` do cartão (no CrossMix).
3. Copie os seus jogos para a pasta `roms` lá dentro (crie-a se não estiver lá).
4. Volte a colocar o cartão e inicie o PokeDaisy a partir do menu Apps.

A aplicação guarda tudo na sua própria pasta e corre em ecrã inteiro. No CrossMix, aumenta a velocidade mínima do CPU enquanto joga e repõe-na quando sai.

> [!NOTE]
> O programa ainda não correu como aplicação própria num TrimUI (os seus núcleos, sim). Conte com algumas arestas por limar, e diga-nos como correu.

## Consolas portáteis com PortMaster

1. Extraia `pokedaisy-portmaster-aarch64.zip` no computador. Lá dentro estão `PokeDaisy.sh` e uma pasta `pokedaisy`.
2. Copie os dois para a pasta `ports` do seu firmware no cartão SD.
3. Os seus jogos podem ficar onde estão: a lista de jogos mostra `ports/pokedaisy/roms` e as pastas `gba`, `gb`, `gbc` e `nds` do próprio firmware (e as que se chamam `Game Boy Advance`, `Game Boy Color`, `Game Boy` e `Nintendo DS`).
4. Inicie o PokeDaisy a partir da lista Ports.

O PortMaster indica ao PokeDaisy os botões do seu dispositivo, por isso funcionam sem configurar nada. O botão Menu ou Guide abre o menu do PokeDaisy, e SELECT e START em conjunto saem, como em qualquer port. Já foi jogado num GKD 350H com ROCKNIX.

## TrimUI Brick com NXRedux

1. Extraia `PokeDaisy-NXRedux.zip` na raiz do cartão SD. Acrescenta `Emus/tg5040/PDAI.pak` e uma pasta `Roms/PokeDaisy (PDAI)`.
2. Coloque os seus jogos (`.gba`, `.gb`, `.gbc` e `.nds`, descomprimidos) em `Roms/PokeDaisy (PDAI)`.
3. Inicie-os a partir da coleção PokeDaisy no NXRedux.

O NXRedux trata dos seus jogos guardados, estados guardados, avanço rápido, shaders e da retoma a partir do seu próprio menu. Dê a cada jogo um nome de ficheiro diferente, porque o NXRedux dá nome aos jogos guardados apenas pelo nome do ficheiro.

## RetroArch e outros frontends

1. Extraia `pokedaisy-libretro-x86_64.zip` (PC) ou `pokedaisy-libretro-aarch64.zip` (consola portátil ARM). A pasta tem os três núcleos, as suas licenças e `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` corre jogos de Game Boy Advance.
   - `pokedaisy_gbc_libretro.so` corre jogos de Game Boy e Game Boy Color (e também de Game Boy Advance).
   - `pokedaisy_ds_libretro.so` corre jogos de DS.
2. Copie os núcleos para a pasta de núcleos do seu frontend. O RetroArch mostra onde fica em **Settings** > **Directory** > **Cores**.
3. Carregue o seu jogo com o núcleo PokeDaisy (o núcleo de DS aparece como PokeDaisy DS).

O seu frontend trata do jogo guardado, dos estados e do avanço rápido como faz com qualquer núcleo. As definições do companheiro ficam na pasta de jogos guardados do frontend: `pokedaisy/settings.ini` para os núcleos de Game Boy e Game Boy Advance, `pokedaisy-ds/settings.ini` para o núcleo de DS. Mantenha as licenças junto dos núcleos se os passar a alguém.

### Já instalado: plorpOS e spruceOS

No [plorpOS](https://github.com/thwonp/plorpos) e nas versões nightly do [spruceOS](https://github.com/spruceUI/spruceOS), os núcleos de Game Boy, Game Boy Color e Game Boy Advance já vêm instalados, por isso não há nada para transferir: escolha PokeDaisy como núcleo do seu jogo (no plorpOS, na linha Core de uma prateleira de Game Boy).

## Traga os seus jogos guardados

No programa PokeDaisy (as três primeiras transferências), o jogo guardado de um jogo fica na pasta `saves` ao lado de `roms`, com o nome completo do ficheiro da ROM mais `.sav`: para `FireRed.gba`, é `saves/FireRed.gba.sav`. Um jogo guardado de outro emulador funciona se o copiar para lá com esse nome, incluindo um `.srm` do RetroArch. Antes, guarde uma cópia do seu jogo guardado antigo noutro sítio.

Antes de um jogo abrir, o PokeDaisy copia o seu jogo guardado para `saves/pokedaisy-backups` e mantém as 10 cópias mais recentes, por isso tem lá uma mais antiga se precisar. Os estados guardados de outro emulador não carregam.

No RetroArch, no NXRedux e nos outros frontends, o jogo guardado é o ficheiro do próprio frontend, onde ele sempre o guarda.

## Atualizar

Transfira o zip mais recente e extraia-o por cima da pasta antiga (no NXRedux, outra vez na raiz do cartão SD). Os seus jogos, jogos guardados, estados e definições não estão no zip, por isso ficam como estão. Num frontend, copie os núcleos novos por cima dos antigos.

## Se algo correr mal

- **Permission denied ao iniciar:** o extrator perdeu a permissão de execução do programa. Na pasta, execute `chmod +x pokedaisy-linux`.
- **Um erro que menciona `GLIBC_2.35` ou `libSDL2`:** o seu Linux é mais antigo do que o PokeDaisy precisa, ou o SDL2 não está instalado. Instale o SDL2 a partir da sua distribuição (no Ubuntu, `sudo apt install libsdl2-2.0-0`) ou experimente um sistema mais recente.
- **Um jogo não aparece na lista:** tem de estar diretamente em `roms` (não numa pasta lá dentro), descomprimido, e terminar em `.gba`, `.gb`, `.gbc` ou `.nds`.
- **A e B estão trocados:** abra o menu (START e SELECT em conjunto, ou o botão Guide ou Menu) e mude `A/B BUTTONS` para `SWAPPED`.
- **O meu jogo guardado não carregou:** precisa de ter o nome completo do ficheiro da ROM com `.sav` no fim, em `saves`. Procure uma cópia mais antiga em `saves/pokedaisy-backups`.
- **O comando não faz nada:** o SDL ainda não o conhece. Ponha um `gamecontrollerdb.txt` com o mapeamento dele na pasta de dados (ao lado de `roms`).

Ainda com problemas? [Diga-nos](https://pokedaisy.com/pt/feedback/#bug) que dispositivo e que ficheiro usou. Iniciado a partir de um terminal, o PokeDaisy mostra lá o que aconteceu; a aplicação da TrimUI escreve-o em `launch.log` e o port do PortMaster em `log.txt`, ambos na sua própria pasta.
