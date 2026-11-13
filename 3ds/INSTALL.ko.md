<!-- Made from docs/manual/translations/ko/3ds.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# 닌텐도 3DS에 설치하기

*PokeDaisy 홈브루 앱을 3DS에 한 단계씩 설치하고, 게임과 세이브 데이터를 넣고, 소리를 켜는 방법이에요.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · **한국어** · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

웹사이트에서도 볼 수 있어요: https://pokedaisy.com/ko/docs/3ds/

PokeDaisy는 닌텐도 3DS에서도 홈브루 앱으로 실행돼요. 위 화면에서 게임이 돌아가고, 아래 화면(터치스크린)에 컴패니언이 나와요. 게임 라이브러리, 세이브 에디터, 세이브 데이터끼리 교환하는 기능도 있어요. 이 페이지는 커스텀 펌웨어가 설치된 3DS에서 첫 게임을 시작할 때까지 안내해요.

> [!NOTE]
> 3DS 버전은 아직 초기 프리뷰예요. **New 닌텐도 3DS, New 닌텐도 3DS XL, New 닌텐도 2DS XL**에 맞춰 만들었어요. 닌텐도 3DS, 3DS XL, 2DS에서도 실행은 되지만 느릴 수 있어요. 메뉴는 지금은 영어로만 나와요.

![3DS의 PokeDaisy 라이브러리: 위 화면에 커서가 있는 게임, 터치스크린에 게임 목록](../docs/manual/shots/3ds-library.png "라이브러리: 위에는 커서가 있는 게임, 아래에는 내 게임들")

## 필요한 것

- **커스텀 펌웨어(Luma3DS)와 Homebrew Launcher가 설치된 3DS.** 아직 없다면 [3ds.hacks.guide](https://3ds.hacks.guide/)를 따라 하세요. 모든 기기와 시스템 버전에 맞춰 계속 업데이트되는 커뮤니티 가이드이고, 이 페이지에 필요한 것을 모두 준비해 줘요. 다 끝나면 여기로 돌아오세요.
- **SD 카드**와 그걸 읽을 수 있는 컴퓨터(카드를 빼고 싶지 않다면 3DS의 FTP 앱도 괜찮아요).
- **내 게임.** PokeDaisy에는 게임이 들어 있지 않아요. 내가 가진 카트리지에서 직접 추출한 ROM을 사용하세요: `.gba`, `.gb`, `.gbc` 파일이에요.

## 1단계: 앱 받기

1. 컴퓨터에서 [PokeDaisy 릴리스 페이지](https://github.com/lidor30/pokedaisy/releases)를 열어요.
2. 3DS 파일이 있는 가장 새로운 릴리스에서 `pokedaisy.3dsx`를 내려받아요.

## 2단계: SD 카드에 복사하기

1. 3DS 전원을 끄고 SD 카드를 컴퓨터에 넣어요.
2. 카드 맨 위에 있는 `3ds` 폴더를 열어요. 커스텀 펌웨어를 설치할 때 만들어졌어요. 없으면 만들어 주세요.
3. 그 안에 `pokedaisy.3dsx`를 복사해요. 그러면 카드에 `sdmc:/3ds/pokedaisy.3dsx`가 생겨요.

`sdmc:/`는 3DS가 SD 카드의 맨 위를 부르는 이름이에요. 컴퓨터에서 보면 `sdmc:/3ds/`는 그냥 카드의 `3ds` 폴더예요.

## 3단계: 게임과 세이브 데이터 넣기

1. 카드 맨 위에 `pokedaisy` 폴더를 만들고, 그 안에 `roms` 폴더를 만들어요: `sdmc:/pokedaisy/roms/`. (PokeDaisy를 먼저 한 번 실행하면 둘 다 자동으로 만들어져요.)
2. 게임을 `roms`에 복사해요. 게임마다 폴더를 따로 만들지 말고 바로 그 폴더 안에 넣고, 압축 파일은 먼저 풀어 주세요. 3DS의 PokeDaisy는 압축하지 않은 `.gba`, `.gb`, `.gbc` 파일만 읽어요.
3. 다른 에뮬레이터의 세이브 데이터를 가져온다면, 게임과 같은 이름에 끝을 `.sav`로 해서 `roms` 안의 게임 옆에 두세요: `Pokemon Emerald.gba`는 `Pokemon Emerald.sav`를 읽어요. RetroArch 세이브는 `.srm`으로 끝나니까 `.sav`로 이름을 바꿔 주세요. 혹시 모르니 컴퓨터에도 세이브 데이터 사본을 남겨 두세요.

세이브 데이터가 없으면 게임은 처음부터 시작하고, 게임에서 처음 저장할 때 PokeDaisy가 같은 폴더에 `.sav`를 만들어요.

## 4단계: 소리 켜기

3DS 홈브루는 본체의 사운드 펌웨어를 SD 카드에 복사한 뒤에야 소리를 낼 수 있어요. 한 번만 하면 돼요:

1. SD 카드를 다시 넣고 3DS를 켠 다음, **L**을 누른 채로 십자 버튼 **아래**와 **SELECT**를 눌러 Rosalina 메뉴를 열어요.
2. **Miscellaneous options**를 고르고, **Dump DSP firmware**를 골라요.
3. 메뉴가 닫힐 때까지 **B**를 눌러요.

그러면 `sdmc:/3ds/dspfirm.cdc`가 생겨요. 이게 없으면 게임이 소리 없이 실행돼요. 다른 앱 때문에 전에 해 본 적이 있다면 할 일은 없어요.

## 5단계: PokeDaisy 실행하기

1. Homebrew Launcher를 열어요. 3ds.hacks.guide로 준비한 본체라면 다운로드 플레이에서 열려요.
2. 목록에서 **PokeDaisy**를 골라요.

라이브러리가 열려요. 터치스크린에 게임 목록이, 위 화면에 커서가 있는 게임이 나오고, 한 번 플레이한 게임이면 플레이어, 플레이 시간, 지닌 포켓몬도 보여요. 십자 버튼으로 움직이거나 게임을 터치한 다음 **A**를 누르면 시작해요.

![게임이 없는 첫 실행 화면: 위 화면에 게임을 sdmc:/pokedaisy/roms/에 넣으라고 나와요](../docs/manual/shots/3ds-library-empty.png "아직 게임이 없을 때: 라이브러리가 넣을 곳을 알려 줘요")

라이브러리에 **No games yet**이 나오면, 게임이 `sdmc:/pokedaisy/roms/` 안에 바로 있는지, 이름이 `.gba`, `.gb`, `.gbc`로 끝나는지 확인하세요.

## 조작 방법

| 버튼 | 하는 일 |
| --- | --- |
| **A** | 커서가 있는 게임 플레이 |
| **L** / **R** | 라이브러리 보기 바꾸기: `ALL`, `RECENT`, `GBA`, `GB`, `HACKS` |
| **SELECT** | 다음 페이지: 게임, `SAVE EDITOR`, `TRADE SAVES`, `SETTINGS` |
| **START** | PokeDaisy를 닫고 Homebrew Launcher로 돌아가기 |

게임 중에는 게임보이 어드밴스의 버튼이 3DS의 같은 버튼이 되고(슬라이드 패드는 십자 버튼처럼 쓸 수 있어요), 터치스크린이 컴패니언이에요. 그 밖에:

| 버튼 | 하는 일 |
| --- | --- |
| **Y** | 퀵 메뉴: PC, `FLY`, 자전거, 세이브 스테이트, 빨리 감기 등 |
| **X** | 필드에서 버튼을 컴패니언에 넘기기(**X**를 한 번 더 누르면 게임으로 돌아가요) |
| **ZR** | 누르는 동안 빨리 감기(New 3DS) |
| **ZL** | 컴패니언의 이전 탭(New 3DS) |

게임을 끝낼 때는 컴패니언의 `SETTINGS` 맨 아래에 있는 `CLOSE GAME`을 써요. **HOME** 버튼이나 본체를 닫아도 돼요.

![3DS 라이브러리의 SETTINGS 페이지: VIEW, SORT, THEME, DARK MODE, SCREEN, ASPECT와 소리 설정](../docs/manual/shots/3ds-settings.png "라이브러리의 SETTINGS: 모든 게임에 쓰이는 모양과 화면 설정")

## 플레이할 수 있는 게임

게임보이, 게임보이 컬러, 게임보이 어드밴스 게임이라면 다 실행돼요. 컴패니언은 Android의 PokeDaisy가 아는 게임을 읽어요: FireRed, LeafGreen, Ruby, Sapphire, Emerald, Red, Blue, Yellow, Gold, Silver, Crystal, 그리고 많은 ROM 핵([지원 게임](https://pokedaisy.com/docs/supported-games/)에 목록이 있어요). 3DS 버전에서는 DS 게임이 실행되지 않아요.

## SD 카드의 어디에 무엇이 있나요

| 위치 | 들어 있는 것 |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | 앱 |
| `sdmc:/3ds/dspfirm.cdc` | 사운드 펌웨어(4단계) |
| `sdmc:/pokedaisy/roms/` | 내 게임과, 각 게임 옆의 `.sav` |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | 실행할 때마다 만드는 세이브 사본, 게임마다 최신 10개 |
| `sdmc:/pokedaisy/states/` | 세이브 스테이트 |
| `sdmc:/pokedaisy/cheats/` | 치트, 게임마다 파일 하나 |
| `sdmc:/pokedaisy/settings.ini` | 설정 |
| `sdmc:/pokedaisy/log.txt` | 지난 실행에서 일어난 일: 버그를 알릴 때 첨부해 주세요 |

## 업데이트하기

새 `pokedaisy.3dsx`를 내려받아 `sdmc:/3ds/`의 예전 파일 위에 덮어써요. 게임, 세이브 데이터, 세이브 스테이트, 설정은 모두 `sdmc:/pokedaisy/`에 있어서 그대로 남아요.

## 문제가 생기면

- **소리가 안 나요:** `sdmc:/3ds/dspfirm.cdc`가 없어요. 4단계를 해 주세요.
- **Homebrew Launcher에 PokeDaisy가 없어요:** 파일이 `sdmc:/3ds/`에 있고 이름이 `.3dsx`로 끝나야 해요.
- **게임이 목록에 없어요:** `sdmc:/pokedaisy/roms/` 안에 바로 있어야 하고, 그 안의 폴더에 넣거나 압축한 채로 두면 안 돼요.
- **세이브 데이터를 안 읽어요:** 세이브는 게임과 정확히 같은 이름에 끝이 `.sav`여야 해요. 실행할 때마다 세이브가 먼저 `sdmc:/pokedaisy/roms/pokedaisy-backups/`에 복사되니까, 예전 사본이 필요하면 거기 있어요.
- **느려요:** 닌텐도 3DS와 2DS는 New 모델보다 느려요. 라이브러리의 `SETTINGS`에서 `SCREEN`을 가장 가벼운 모드인 `PIXEL 1x`로 바꿔 보세요.

그래도 안 되면 `sdmc:/pokedaisy/log.txt`를 첨부해서 [알려 주세요](https://pokedaisy.com/ko/feedback/#bug).
