---
title: Instalar em um Nintendo 3DS
summary: Coloque o app homebrew PokeDaisy em um 3DS passo a passo, adicione seus jogos e saves e ligue o som.
section: start
---

O PokeDaisy também roda no Nintendo 3DS, como app homebrew: seu jogo roda na tela de cima e o companheiro fica na tela de toque, com sua própria biblioteca de jogos, um editor de saves e trocas entre saves. Esta página leva você de um console com firmware personalizado até o seu primeiro jogo.

> [!NOTE]
> A versão para 3DS é uma prévia inicial. Ela foi feita para o **New Nintendo 3DS, New Nintendo 3DS XL e New Nintendo 2DS XL**. Um Nintendo 3DS, 3DS XL ou 2DS original pode abrir, mas pode ficar lento. Os menus estão em inglês por enquanto.

![A biblioteca do PokeDaisy em um 3DS: o jogo escolhido na tela de cima, a lista de jogos na tela de toque](shots/3ds-library.png "A biblioteca: em cima o jogo sob o cursor, embaixo os seus jogos")

## O que você precisa

- **Um 3DS com firmware personalizado (Luma3DS) e o Homebrew Launcher.** Se o seu console ainda não tem, siga o [3ds.hacks.guide](https://3ds.hacks.guide/). É o guia da comunidade, atualizado para cada console e versão do sistema, e ele prepara tudo o que esta página precisa. Volte aqui quando terminar.
- **O cartão SD** e um computador que consiga ler (ou um app de FTP no 3DS, se preferir não tirar o cartão).
- **Seus próprios jogos.** O PokeDaisy não vem com nenhum jogo. Use ROMs que você mesmo extraiu de cartuchos que você tem: arquivos `.gba`, `.gb` ou `.gbc`.

## Passo 1: baixe o app

1. No computador, abra a [página de versões do PokeDaisy](https://github.com/lidor30/pokedaisy/releases).
2. Na versão mais nova que tiver um arquivo para 3DS, baixe `pokedaisy.3dsx`.

## Passo 2: copie para o cartão SD

1. Desligue o 3DS e coloque o cartão SD dele no computador.
2. Abra a pasta `3ds` na raiz do cartão. A instalação do firmware personalizado criou essa pasta; se ela não estiver lá, crie.
3. Copie `pokedaisy.3dsx` para dentro dela, para que o cartão tenha `sdmc:/3ds/pokedaisy.3dsx`.

`sdmc:/` é o nome que o 3DS dá à raiz do cartão SD: no seu computador, `sdmc:/3ds/` é só a pasta `3ds` do cartão.

## Passo 3: adicione seus jogos e saves

1. Na raiz do cartão, crie uma pasta `pokedaisy` e, dentro dela, uma pasta `roms`: `sdmc:/pokedaisy/roms/`. (O PokeDaisy cria as duas na primeira vez que abre, se preferir abrir uma vez antes.)
2. Copie seus jogos para `roms`. Coloque direto na pasta, não em subpastas, e descompacte antes: no 3DS, o PokeDaisy só lê arquivos `.gba`, `.gb` e `.gbc` soltos.
3. Vai trazer um save de outro emulador? Coloque em `roms` ao lado do jogo, com o mesmo nome e `.sav` no final: `Pokemon Emerald.gba` lê `Pokemon Emerald.sav`. Um save do RetroArch termina em `.srm`: renomeie para `.sav`. Guarde também uma cópia do save no computador, por garantia.

Sem save, o jogo começa do zero e o PokeDaisy cria o `.sav` na mesma pasta na primeira vez que você salvar no jogo.

## Passo 4: ligue o som

O homebrew de 3DS só consegue tocar som depois que o firmware de áudio do console é copiado para o cartão SD. Isso é feito uma vez só:

1. Coloque o cartão SD de volta, ligue o 3DS, segure **L** e então aperte **para baixo** no direcional e **SELECT** para abrir o menu Rosalina.
2. Escolha **Miscellaneous options** e depois **Dump DSP firmware**.
3. Aperte **B** até o menu fechar.

Isso grava `sdmc:/3ds/dspfirm.cdc`. Sem ele, os jogos rodam sem som. Se você já fez isso antes para outro app, não precisa fazer nada.

## Passo 5: abra o PokeDaisy

1. Abra o Homebrew Launcher. Em um console preparado com o 3ds.hacks.guide, ele abre pelo app de jogo por download (Download Play).
2. Escolha **PokeDaisy** na lista.

A biblioteca abre: a tela de toque lista seus jogos e a tela de cima mostra o que está sob o cursor, com seu jogador, tempo de jogo e equipe depois que você jogar. Mova com o direcional ou toque em um jogo, e aperte **A** para jogar.

![O primeiro início da biblioteca, sem jogos: a tela de cima diz para colocar seus jogos em sdmc:/pokedaisy/roms/](shots/3ds-library-empty.png "Ainda sem jogos: a biblioteca diz onde eles ficam")

Se a biblioteca disser **No games yet**, confira se os jogos estão direto em `sdmc:/pokedaisy/roms/` e terminam em `.gba`, `.gb` ou `.gbc`.

## Como se orientar

| Botão | O que faz |
| --- | --- |
| **A** | Joga o jogo sob o cursor |
| **L** / **R** | Troca as visões da biblioteca: `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | A próxima página: jogos, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | Fecha o PokeDaisy e volta ao Homebrew Launcher |

No jogo, os botões do GBA são os mesmos do 3DS (o botão deslizante funciona como o direcional) e a tela de toque é o companheiro. Mais alguns:

| Botão | O que faz |
| --- | --- |
| **Y** | O menu rápido: PC, `FLY`, sua bicicleta, estados salvos, avanço rápido e mais |
| **X** | No mapa, passa os botões para o companheiro (**X** de novo devolve ao jogo) |
| **ZR** | Avanço rápido enquanto segura (New 3DS) |
| **ZL** | A aba anterior do companheiro (New 3DS) |

Para sair de um jogo, use `CLOSE GAME` no fim das `SETTINGS` do companheiro. O botão **HOME** e fechar o console também funcionam.

![A página SETTINGS da biblioteca em um 3DS: VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT e o som](shots/3ds-settings.png "As SETTINGS da biblioteca: a aparência e a tela para todos os jogos")

## Quais jogos funcionam

Qualquer jogo de Game Boy, Game Boy Color ou Game Boy Advance roda. O companheiro lê os jogos que o PokeDaisy conhece no Android: FireRed, LeafGreen, Ruby, Sapphire e Emerald, Red, Blue, Yellow, Gold, Silver e Crystal, e muitos hacks de ROM ([Jogos compatíveis](supported-games.md) tem a lista). Jogos de DS não rodam na versão para 3DS.

## Onde fica cada coisa no cartão SD

| Caminho | O que tem lá |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | O app |
| `sdmc:/3ds/dspfirm.cdc` | O firmware de áudio (passo 4) |
| `sdmc:/pokedaisy/roms/` | Seus jogos, e o `.sav` de cada um ao lado |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | Uma cópia de cada save antes de cada início, os 10 mais novos por jogo |
| `sdmc:/pokedaisy/states/` | Os estados salvos |
| `sdmc:/pokedaisy/cheats/` | Os cheats, um arquivo por jogo |
| `sdmc:/pokedaisy/settings.ini` | Suas configurações |
| `sdmc:/pokedaisy/log.txt` | O que o último início fez: anexe quando for relatar um bug |

## Atualizar

Baixe o `pokedaisy.3dsx` novo e copie por cima do antigo em `sdmc:/3ds/`. Seus jogos, saves, estados e configurações ficam todos em `sdmc:/pokedaisy/` e continuam como estão.

## Se algo der errado

- **Sem som:** está faltando `sdmc:/3ds/dspfirm.cdc`. Faça o passo 4.
- **O PokeDaisy não aparece no Homebrew Launcher:** o arquivo precisa estar em `sdmc:/3ds/` e terminar em `.3dsx`.
- **Um jogo não aparece na lista:** ele precisa estar direto em `sdmc:/pokedaisy/roms/`, não em uma pasta dentro dela e não compactado.
- **Meu save não carregou:** ele precisa ter exatamente o nome do jogo com `.sav` no final. Todo início copia o save primeiro para `sdmc:/pokedaisy/roms/pokedaisy-backups/`, então uma cópia mais antiga está lá se você precisar.
- **Está lento:** um 3DS ou 2DS original é mais lento que os modelos New. Nas `SETTINGS` da biblioteca, mude `SCREEN` para `PIXEL 1x`, o modo mais leve.

Ainda com problema? [Avise a gente](https://pokedaisy.com/pt-br/feedback/#bug) e anexe `sdmc:/pokedaisy/log.txt`.
