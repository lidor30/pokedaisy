<!-- Made from docs/manual/translations/ko/linux-install.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Linux, Steam Deck, 휴대용 게임기에 설치하기

*내 기기에 맞는 파일을 고르고, Steam Deck, Linux PC, Linux 휴대용 게임기에 PokeDaisy를 설치하고(또는 RetroArch에 코어를 넣고), 게임과 세이브 데이터를 가져오는 방법이에요.*

[English](INSTALL.md) · [日本語](INSTALL.ja.md) · **한국어** · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

웹사이트에서도 볼 수 있어요: https://pokedaisy.com/ko/docs/linux-install/

PokeDaisy는 Linux에서도 실행돼요. Steam Deck, Linux PC, TrimUI 같은 Linux 휴대용 게임기에서 따로 된 프로그램으로 쓰거나, RetroArch나 게임기의 프론트엔드에서 불러오는 libretro 코어로 쓸 수 있어요. 어느 쪽이든 같은 컴패니언, HUD, 퀵 메뉴, `EXPAND`를 쓸 수 있어요. 이 페이지는 내려받기부터 첫 게임까지 안내해요. 플레이를 시작한 뒤의 조작, 메뉴와 그 밖의 모든 것은 [Linux, Steam Deck, 휴대용 게임기](https://pokedaisy.com/docs/linux/)에 있어요.

> [!WARNING]
> Linux 버전은 베타예요. 메뉴는 지금은 영어로만 나오고, 이 페이지의 기기 중에는 아직 시험해 보지 않은 것도 있어요. 문제가 생기면 어떤 파일을 어떤 기기에서 썼는지 [알려 주세요](https://pokedaisy.com/ko/feedback/#bug).

## 필요한 것

- **Steam Deck, Linux PC(x86_64), 또는 64비트 ARM Linux 휴대용 게임기.** PC라면 glibc 2.35 이상과 SDL2가 있는 Linux가 필요해요(SteamOS, Ubuntu 22.04 이상, 요즘 데스크톱 대부분에는 둘 다 있어요). 휴대용 게임기라면 glibc 2.33 이상이 필요하고, TrimUI Brick도 여기에 들어가요.
- **파일을 복사할 방법:** 기기 자체에서 하거나, SD 카드를 컴퓨터에 넣어서 해요.
- **내 게임.** PokeDaisy에는 게임이 들어 있지 않아요. 내가 가진 카트리지에서 직접 추출한 ROM을 사용하세요: 압축을 푼 `.gba`, `.gb`, `.gbc`, `.nds` 파일이에요.

## 어떤 파일을 내려받나요

[PokeDaisy 릴리스 페이지](https://github.com/lidor30/pokedaisy/releases)를 열고, Linux 파일이 있는 가장 새로운 릴리스에서 내 기기에 맞는 파일을 내려받아요:

| 내 기기 | 내려받을 파일 |
| --- | --- |
| Steam Deck 또는 Linux PC | `pokedaisy-linux-x86_64.zip` |
| TrimUI 휴대용 게임기(CrossMix, NextUI)에서 앱으로 | `pokedaisy-linux-aarch64.zip` |
| PortMaster가 있는 휴대용 게임기(ROCKNIX, Knulli, muOS 등) | `pokedaisy-portmaster-aarch64.zip` |
| NXRedux를 쓰는 TrimUI Brick | `PokeDaisy-NXRedux.zip` |
| PC의 RetroArch나 다른 프론트엔드 | `pokedaisy-libretro-x86_64.zip` |
| ARM 휴대용 게임기의 RetroArch나 다른 프론트엔드 | `pokedaisy-libretro-aarch64.zip` |

처음 세 개에는 PokeDaisy 프로그램과 그 옆에 코어 세 개가 들어 있어요: 게임보이 어드밴스용 하나, 게임보이와 게임보이 컬러용 하나, DS용 하나예요. 마지막 두 개에는 코어만 들어 있어요. 그다음 이 페이지에서 내 기기에 맞는 부분을 따라 하세요.

## Steam Deck과 Linux PC

1. `pokedaisy-linux-x86_64.zip`을 홈 폴더 어딘가에, 예를 들어 `PokeDaisy` 폴더에 압축을 풀어요. Steam Deck이라면 데스크톱 모드에서 하세요. 그러면 프로그램 `pokedaisy-linux`와 코어 세 개가 든 `pokedaisy-linux-x86_64` 폴더가 생겨요.
2. 그 폴더 안에 `roms` 폴더를 만들고 게임을 복사해요. (먼저 한 번 실행해 보고 싶다면, 처음 실행할 때 PokeDaisy가 만들어 줘요.)
3. `pokedaisy-linux-x86_64` 폴더에서 터미널을 열고 `./pokedaisy-linux --data .`로 실행해요.

게임 목록이 열려요. 십자 버튼(또는 방향키)으로 게임을 고르고 A(키보드에서는 Z)를 누르세요. `--data .`는 `roms`, `saves`, `states`와 설정을 그 폴더에 두라는 뜻이에요. 바로가기를 직접 만든다면 `--data` 뒤에 그 폴더의 전체 경로를 넣어 주세요.

### 게임 모드에서 플레이하기

1. 데스크톱 모드에서 Steam을 열고 **Games** > **Add a Non-Steam Game to My Library**를 골라요.
2. **Browse**를 누르고 `pokedaisy-linux-x86_64` 폴더로 가서 `pokedaisy-linux`를 골라 추가해요.
3. 게임 모드로 돌아가서 라이브러리에서 PokeDaisy를 실행해요.

Steam이 PokeDaisy를 그 폴더에서 실행하니까 실행 옵션은 필요 없어요. 게임 모드에서는 화면을 꽉 채우고, Deck의 버튼, 터치스크린, 소리가 평소처럼 작동해요.

## TrimUI 휴대용 게임기

`pokedaisy-linux-aarch64.zip`은 CrossMix와 NextUI용으로 만든 TrimUI 앱 버전의 PokeDaisy예요.

1. 컴퓨터에서 zip 파일의 압축을 풀어요. `pokedaisy-linux-aarch64` 폴더가 생겨요.
2. SD 카드를 컴퓨터에 넣고, 그 폴더를 카드의 `Apps` 폴더(CrossMix의 경우)에 복사해요.
3. 그 안의 `roms` 폴더에 게임을 복사해요(없으면 만들어 주세요).
4. 카드를 다시 넣고 Apps 메뉴에서 PokeDaisy를 실행해요.

앱은 모든 것을 자기 폴더에 두고 전체 화면으로 실행돼요. CrossMix에서는 플레이하는 동안 CPU의 최저 속도를 올리고, 끝내면 원래대로 돌려놔요.

> [!NOTE]
> 이 프로그램은 TrimUI에서 따로 된 앱으로는 아직 실행해 본 적이 없어요(코어는 실행해 봤어요). 다듬어지지 않은 부분이 있을 수 있으니, 어땠는지 알려 주세요.

## PortMaster가 있는 휴대용 게임기

1. 컴퓨터에서 `pokedaisy-portmaster-aarch64.zip`의 압축을 풀어요. 안에 `PokeDaisy.sh`와 `pokedaisy` 폴더가 있어요.
2. 둘 다 SD 카드에 있는 펌웨어의 `ports` 폴더에 복사해요.
3. 게임은 지금 있는 곳에 그대로 두어도 돼요. 게임 목록에 `ports/pokedaisy/roms`와 펌웨어의 `gba`, `gb`, `gbc`, `nds` 폴더(그리고 `Game Boy Advance`, `Game Boy Color`, `Game Boy`, `Nintendo DS`라는 이름의 폴더)가 나와요.
4. Ports 목록에서 PokeDaisy를 실행해요.

PortMaster가 PokeDaisy에 기기의 버튼을 알려 주니까 따로 설정하지 않아도 작동해요. Menu나 Guide 버튼은 PokeDaisy의 메뉴를 열고, 다른 포트처럼 SELECT와 START를 함께 누르면 끝나요. ROCKNIX를 쓰는 GKD 350H에서 플레이해 봤어요.

## NXRedux를 쓰는 TrimUI Brick

1. `PokeDaisy-NXRedux.zip`을 SD 카드 맨 위에 압축을 풀어요. `Emus/tg5040/PDAI.pak`과 `Roms/PokeDaisy (PDAI)` 폴더가 생겨요.
2. 게임(압축을 푼 `.gba`, `.gb`, `.gbc`, `.nds`)을 `Roms/PokeDaisy (PDAI)`에 넣어요.
3. NXRedux의 PokeDaisy 모음에서 실행해요.

세이브 데이터, 세이브 스테이트, 빨리 감기, 셰이더, 이어 하기는 NXRedux가 자기 메뉴에서 관리해요. NXRedux는 세이브 데이터 이름을 파일 이름만으로 정하니까, 게임마다 파일 이름을 다르게 해 주세요.

## RetroArch와 다른 프론트엔드

1. `pokedaisy-libretro-x86_64.zip`(PC) 또는 `pokedaisy-libretro-aarch64.zip`(ARM 휴대용 게임기)의 압축을 풀어요. 폴더에는 코어 세 개, 그 라이선스, `SOURCE.txt`가 있어요:
   - `pokedaisy_gba_libretro.so`는 게임보이 어드밴스 게임을 실행해요.
   - `pokedaisy_gbc_libretro.so`는 게임보이와 게임보이 컬러 게임을 실행해요(게임보이 어드밴스 게임도 돼요).
   - `pokedaisy_ds_libretro.so`는 DS 게임을 실행해요.
2. 코어를 프론트엔드의 코어 폴더에 복사해요. RetroArch에서는 **Settings** > **Directory** > **Cores**에서 그 위치를 볼 수 있어요.
3. PokeDaisy 코어로 게임을 불러와요(DS 코어는 PokeDaisy DS로 나와요).

세이브 데이터, 세이브 스테이트, 빨리 감기는 다른 코어와 똑같이 프론트엔드가 관리해요. 컴패니언 설정은 프론트엔드의 세이브 폴더에 저장돼요: 게임보이와 게임보이 어드밴스 코어는 `pokedaisy/settings.ini`, DS 코어는 `pokedaisy-ds/settings.ini`예요. 코어를 다른 사람에게 줄 때는 라이선스도 함께 주세요.

### 이미 설치되어 있어요: plorpOS와 spruceOS

[plorpOS](https://github.com/thwonp/plorpos)와 [spruceOS](https://github.com/spruceUI/spruceOS)의 나이틀리 빌드에는 게임보이, 게임보이 컬러, 게임보이 어드밴스 코어가 이미 설치되어 있어서 내려받을 게 없어요. 게임의 코어로 PokeDaisy를 고르기만 하면 돼요(plorpOS에서는 게임보이 선반의 Core 줄에서 골라요).

## 세이브 데이터 가져오기

PokeDaisy 프로그램(처음 세 개의 파일)에서는 게임의 세이브 데이터가 `roms` 옆의 `saves` 폴더에 있고, 이름은 ROM 파일 이름 전체에 `.sav`를 붙인 거예요: `FireRed.gba`라면 `saves/FireRed.gba.sav`예요. 다른 에뮬레이터의 세이브 데이터도 그 이름으로 거기 복사하면 돼요. RetroArch의 `.srm`도 마찬가지예요. 먼저 예전 세이브 데이터의 사본을 다른 곳에 남겨 두세요.

게임이 열리기 전에 PokeDaisy는 세이브 데이터를 `saves/pokedaisy-backups`에 복사하고 최신 사본 10개를 남겨 두니까, 필요하면 예전 사본이 거기 있어요. 다른 에뮬레이터의 세이브 스테이트는 불러올 수 없어요.

RetroArch, NXRedux, 다른 프론트엔드에서는 세이브 데이터가 프론트엔드의 파일이고, 늘 두던 곳에 있어요.

## 업데이트하기

새 zip 파일을 내려받아 예전 폴더 위에 압축을 풀어요(NXRedux라면 이번에도 SD 카드 맨 위에요). 게임, 세이브 데이터, 세이브 스테이트, 설정은 zip 파일에 들어 있지 않으니까 그대로 남아요. 프론트엔드에서는 새 코어를 예전 코어 위에 덮어써요.

## 문제가 생기면

- **실행할 때 Permission denied가 나와요:** 압축을 풀면서 프로그램의 실행 권한이 사라졌어요. 그 폴더에서 `chmod +x pokedaisy-linux`를 실행하세요.
- **`GLIBC_2.35`나 `libSDL2`가 들어간 오류가 나와요:** PokeDaisy에 필요한 것보다 Linux가 오래됐거나 SDL2가 설치되지 않았어요. 배포판에서 SDL2를 설치하거나(Ubuntu라면 `sudo apt install libsdl2-2.0-0`), 더 새로운 시스템에서 해 보세요.
- **게임이 목록에 없어요:** `roms` 안에 바로 있어야 하고(그 안의 폴더에 넣으면 안 돼요), 압축을 푼 상태로, 이름이 `.gba`, `.gb`, `.gbc`, `.nds`로 끝나야 해요.
- **A와 B가 반대예요:** 메뉴를 열고(START와 SELECT를 함께, 또는 Guide나 Menu 버튼) `A/B BUTTONS`를 `SWAPPED`로 바꾸세요.
- **세이브 데이터를 안 읽어요:** 세이브 데이터는 `saves` 안에, ROM 파일 이름 전체에 `.sav`를 붙인 이름이어야 해요. 예전 사본은 `saves/pokedaisy-backups`에서 찾아보세요.
- **컨트롤러가 반응하지 않아요:** SDL이 아직 그 컨트롤러를 몰라요. 그 매핑이 들어 있는 `gamecontrollerdb.txt`를 데이터 폴더(`roms` 옆)에 넣어 주세요.

그래도 안 되면 어떤 기기와 파일을 썼는지 [알려 주세요](https://pokedaisy.com/ko/feedback/#bug). 터미널에서 실행했다면 PokeDaisy가 무슨 일이 있었는지 그곳에 출력해요. TrimUI 앱은 `launch.log`에, PortMaster 포트는 `log.txt`에 기록하고, 둘 다 자기 폴더에 있어요.
