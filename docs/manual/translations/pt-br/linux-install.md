---
title: Instalar no Linux, Steam Deck e portáteis
summary: Escolha o download certo, instale o PokeDaisy em um Steam Deck, um PC com Linux ou um portátil com Linux (ou os núcleos dele no RetroArch), adicione seus jogos e traga seus saves.
section: start
---

O PokeDaisy também roda no Linux: em um Steam Deck, em um PC com Linux e em portáteis com Linux como os da TrimUI, seja como programa próprio, seja como núcleos libretro que você carrega no RetroArch ou no frontend do seu portátil. De qualquer jeito, você tem o mesmo companheiro, o mesmo HUD, o mesmo menu rápido e o `EXPAND`. Esta página leva você do download até o seu primeiro jogo; [Linux, Steam Deck e portáteis](linux.md) explica os controles, o menu e todo o resto depois que você começar a jogar.

> [!WARNING]
> A versão para Linux é um beta. Os menus estão em inglês por enquanto, e alguns aparelhos desta página ainda não foram testados. Se algo der errado, [avise a gente](https://pokedaisy.com/pt-br/feedback/#bug) qual arquivo e qual aparelho você usou.

## O que você precisa

- **Um Steam Deck, um PC com Linux (x86_64) ou um portátil com Linux ARM de 64 bits.** Em um PC: um Linux com glibc 2.35 ou mais novo e SDL2 (o SteamOS, o Ubuntu 22.04 e mais novos e a maioria dos desktops atuais têm os dois). Em um portátil: glibc 2.33 ou mais novo, o que inclui o TrimUI Brick.
- **Um jeito de copiar arquivos para ele:** o próprio aparelho, ou o cartão SD dele em um computador.
- **Seus próprios jogos.** O PokeDaisy não vem com nenhum jogo. Use ROMs que você mesmo extraiu de cartuchos que você tem: arquivos `.gba`, `.gb`, `.gbc` ou `.nds`, descompactados.

## Qual arquivo baixar

Abra a [página de versões do PokeDaisy](https://github.com/lidor30/pokedaisy/releases) e, na versão mais nova que tiver arquivos para Linux, baixe o do seu aparelho:

| Seu aparelho | Download |
| --- | --- |
| Steam Deck ou um PC com Linux | `pokedaisy-linux-x86_64.zip` |
| Um portátil da TrimUI (CrossMix, NextUI), como app | `pokedaisy-linux-aarch64.zip` |
| Um portátil com PortMaster (ROCKNIX, Knulli, muOS e outros) | `pokedaisy-portmaster-aarch64.zip` |
| Um TrimUI Brick com NXRedux | `PokeDaisy-NXRedux.zip` |
| RetroArch ou outro frontend, em um PC | `pokedaisy-libretro-x86_64.zip` |
| RetroArch ou outro frontend, em um portátil ARM | `pokedaisy-libretro-aarch64.zip` |

Os três primeiros trazem o programa PokeDaisy com os três núcleos ao lado: um para Game Boy Advance, um para Game Boy e Game Boy Color, um para DS. Os dois últimos são só os núcleos. Depois, siga a parte desta página do seu aparelho.

## Steam Deck e PCs com Linux

1. Extraia `pokedaisy-linux-x86_64.zip` em algum lugar da sua pasta pessoal, por exemplo em uma pasta `PokeDaisy`. No Steam Deck, faça isso no Desktop Mode. Você fica com uma pasta `pokedaisy-linux-x86_64` com o programa, `pokedaisy-linux`, e os três núcleos.
2. Nessa pasta, crie uma pasta chamada `roms` e copie seus jogos para dentro dela. (O PokeDaisy cria essa pasta na primeira vez que abre, se preferir abrir uma vez antes.)
3. Abra um terminal na pasta `pokedaisy-linux-x86_64` e inicie com `./pokedaisy-linux --data .`

A lista de jogos abre: escolha um jogo com o direcional (ou as setas) e aperte A (Z no teclado). `--data .` diz ao PokeDaisy para guardar `roms`, `saves`, `states` e as configurações dele nessa pasta; um atalho que você criar deve passar `--data` com o caminho completo da pasta.

### Jogar no Game Mode

1. No Desktop Mode, abra a Steam e escolha **Games** > **Add a Non-Steam Game to My Library**.
2. Escolha **Browse**, vá até a pasta `pokedaisy-linux-x86_64`, escolha `pokedaisy-linux` e adicione.
3. Volte para o Game Mode e abra o PokeDaisy pela sua biblioteca.

A Steam abre o programa na própria pasta dele, então não precisa de opções de inicialização. No Game Mode ele ocupa a tela toda, e os controles, a tela de toque e o som do Deck funcionam normalmente.

## Portáteis da TrimUI

`pokedaisy-linux-aarch64.zip` é o PokeDaisy como app da TrimUI, feito para CrossMix e NextUI.

1. Extraia o zip no computador. Você fica com uma pasta `pokedaisy-linux-aarch64`.
2. Coloque o cartão SD no computador e copie essa pasta para a pasta `Apps` do cartão (no CrossMix).
3. Copie seus jogos para a pasta `roms` dentro dela (crie se ela não estiver lá).
4. Coloque o cartão de volta e abra o PokeDaisy pelo menu Apps.

O app guarda tudo na própria pasta e roda em tela cheia. No CrossMix, ele aumenta a velocidade mínima da CPU enquanto você joga e volta ao normal quando você sai.

> [!NOTE]
> O programa ainda não rodou como app próprio em um TrimUI (os núcleos dele, sim). Espere algumas arestas, e conte para a gente como foi.

## Portáteis com PortMaster

1. Extraia `pokedaisy-portmaster-aarch64.zip` no computador. Dentro dele estão `PokeDaisy.sh` e uma pasta `pokedaisy`.
2. Copie os dois para a pasta `ports` do seu firmware no cartão SD.
3. Seus jogos podem ficar onde estão: a lista de jogos mostra `ports/pokedaisy/roms` e as pastas `gba`, `gb`, `gbc` e `nds` do próprio firmware (e as chamadas `Game Boy Advance`, `Game Boy Color`, `Game Boy` e `Nintendo DS`).
4. Abra o PokeDaisy pela lista Ports.

O PortMaster informa ao PokeDaisy os botões do seu aparelho, então eles funcionam sem configurar nada. O botão Menu ou Guide abre o menu do PokeDaisy, e SELECT e START juntos saem, como em qualquer port. Ele já foi jogado em um GKD 350H com ROCKNIX.

## TrimUI Brick com NXRedux

1. Extraia `PokeDaisy-NXRedux.zip` na raiz do cartão SD. Ele adiciona `Emus/tg5040/PDAI.pak` e uma pasta `Roms/PokeDaisy (PDAI)`.
2. Coloque seus jogos (`.gba`, `.gb`, `.gbc` e `.nds`, descompactados) em `Roms/PokeDaisy (PDAI)`.
3. Abra os jogos pela coleção PokeDaisy no NXRedux.

O NXRedux cuida dos seus saves, estados salvos, avanço rápido, shaders e da retomada pelo próprio menu dele. Dê um nome de arquivo diferente para cada jogo, porque o NXRedux nomeia os saves só pelo nome do arquivo.

## RetroArch e outros frontends

1. Extraia `pokedaisy-libretro-x86_64.zip` (PC) ou `pokedaisy-libretro-aarch64.zip` (portátil ARM). A pasta traz os três núcleos, as licenças deles e `SOURCE.txt`:
   - `pokedaisy_gba_libretro.so` roda jogos de Game Boy Advance.
   - `pokedaisy_gbc_libretro.so` roda jogos de Game Boy e Game Boy Color (e de Game Boy Advance também).
   - `pokedaisy_ds_libretro.so` roda jogos de DS.
2. Copie os núcleos para a pasta de núcleos do seu frontend. O RetroArch mostra onde ela fica em **Settings** > **Directory** > **Cores**.
3. Carregue seu jogo com o núcleo PokeDaisy (o núcleo de DS aparece como PokeDaisy DS).

Seu frontend cuida do save, dos estados e do avanço rápido como faz com qualquer núcleo. As configurações do companheiro ficam na pasta de saves do frontend: `pokedaisy/settings.ini` para os núcleos de Game Boy e Game Boy Advance, `pokedaisy-ds/settings.ini` para o núcleo de DS. Mantenha as licenças junto dos núcleos se for repassar para alguém.

### Já instalado: plorpOS e spruceOS

No [plorpOS](https://github.com/thwonp/plorpos) e nas versões nightly do [spruceOS](https://github.com/spruceUI/spruceOS), os núcleos de Game Boy, Game Boy Color e Game Boy Advance já vêm instalados, então não há nada para baixar: escolha PokeDaisy como núcleo do seu jogo (no plorpOS, na linha Core de uma prateleira de Game Boy).

## Traga seus saves

No programa PokeDaisy (os três primeiros downloads), o save de um jogo fica na pasta `saves` ao lado de `roms`, com o nome completo do arquivo da ROM mais `.sav`: para `FireRed.gba`, é `saves/FireRed.gba.sav`. Um save de outro emulador funciona se você copiar para lá com esse nome, incluindo um `.srm` do RetroArch. Antes, guarde uma cópia do seu save antigo em outro lugar.

Antes de um jogo abrir, o PokeDaisy copia o save dele para `saves/pokedaisy-backups` e mantém as 10 cópias mais novas, então uma mais antiga está lá se você precisar. Estados salvos de outro emulador não carregam.

No RetroArch, no NXRedux e nos outros frontends, o save é o arquivo do próprio frontend, onde ele sempre guarda.

## Atualizar

Baixe o zip mais novo e extraia por cima da pasta antiga (no NXRedux, na raiz do cartão SD de novo). Seus jogos, saves, estados e configurações não estão no zip, então continuam como estão. Em um frontend, copie os núcleos novos por cima dos antigos.

## Se algo der errado

- **Permission denied ao iniciar:** o extrator perdeu a permissão de execução do programa. Na pasta, rode `chmod +x pokedaisy-linux`.
- **Um erro citando `GLIBC_2.35` ou `libSDL2`:** seu Linux é mais antigo do que o PokeDaisy precisa, ou o SDL2 não está instalado. Instale o SDL2 pela sua distribuição (no Ubuntu, `sudo apt install libsdl2-2.0-0`) ou experimente um sistema mais novo.
- **Um jogo não aparece na lista:** ele precisa estar direto em `roms` (não em uma pasta dentro dela), descompactado, e terminar em `.gba`, `.gb`, `.gbc` ou `.nds`.
- **A e B estão trocados:** abra o menu (START e SELECT juntos, ou o botão Guide ou Menu) e mude `A/B BUTTONS` para `SWAPPED`.
- **Meu save não carregou:** o save precisa ter o nome completo do arquivo da ROM com `.sav` no final, em `saves`. Procure uma cópia mais antiga em `saves/pokedaisy-backups`.
- **O controle não faz nada:** o SDL ainda não conhece ele. Coloque um `gamecontrollerdb.txt` com o mapeamento dele na pasta de dados (ao lado de `roms`).

Ainda com problema? [Avise a gente](https://pokedaisy.com/pt-br/feedback/#bug) qual aparelho e qual arquivo você usou. Aberto por um terminal, o PokeDaisy mostra o que aconteceu ali; o app da TrimUI grava isso em `launch.log` e o port do PortMaster em `log.txt`, os dois na própria pasta.
