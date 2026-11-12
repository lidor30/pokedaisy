<!-- Made from docs/manual/translations/ja/linux-install.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# Linux、Steam Deck、携帯機にインストールする

*自分の機器に合うファイルを選び、PokeDaisyをSteam Deck、LinuxのパソコンやLinuxの携帯機に入れる（またはそのコアをRetroArchで使う）手順です。ゲームの入れ方とセーブデータの持ってき方も説明します。*

[English](INSTALL.md) · **日本語** · [한국어](INSTALL.ko.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

ウェブサイトでも読めます: https://pokedaisy.com/ja/docs/linux-install/

PokeDaisyはLinuxでも動きます。Steam Deck、Linuxのパソコン、TrimUIのようなLinuxの携帯機で、単独のプログラムとして、またはRetroArchや携帯機のフロントエンドで読み込むlibretroコアとして使えます。どちらでも、同じコンパニオン、HUD、クイックメニュー、`EXPAND` がついてきます。このページでは、ダウンロードから最初のゲームを始めるまでを説明します。遊び始めてからの操作、メニュー、そのほかのことは [Linux、Steam Deck、携帯機](https://pokedaisy.com/docs/linux/) にあります。

> [!WARNING]
> Linux版はベータ版です。メニューは今のところ英語だけで、このページの機器にはまだ試していないものもあります。うまくいかないときは、どのファイルとどの機器を使ったかを [お知らせください](https://pokedaisy.com/ja/feedback/#bug)。

## 必要なもの

- **Steam Deck、Linuxのパソコン（x86_64）、または64ビットARMのLinux携帯機。** パソコンなら、glibc 2.35以降とSDL2の入ったLinuxが必要です（SteamOS、Ubuntu 22.04以降、いま使われているデスクトップのほとんどには両方入っています）。携帯機ならglibc 2.33以降で、TrimUI Brickもこれに入ります。
- **ファイルをコピーする方法：** 機器そのもの、またはパソコンに入れたそのSDカード。
- **自分のゲーム。** PokeDaisyにゲームは入っていません。自分の持っているカートリッジから自分で吸い出したROMを使ってください：展開済みの `.gba`、`.gb`、`.gbc`、`.nds` ファイルです。

## どのファイルをダウンロードするか

[PokeDaisyのリリースページ](https://github.com/lidor30/pokedaisy/releases) を開き、Linux用のファイルがある一番新しいリリースから、自分の機器に合うものをダウンロードします：

| あなたの機器 | ダウンロード |
| --- | --- |
| Steam Deck、またはLinuxのパソコン | `pokedaisy-linux-x86_64.zip` |
| TrimUIの携帯機（CrossMix、NextUI）でアプリとして | `pokedaisy-linux-aarch64.zip` |
| PortMasterの入った携帯機（ROCKNIX、Knulli、muOSなど） | `pokedaisy-portmaster-aarch64.zip` |
| NXReduxの入ったTrimUI Brick | `PokeDaisy-NXRedux.zip` |
| パソコンのRetroArchやほかのフロントエンド | `pokedaisy-libretro-x86_64.zip` |
| ARM携帯機のRetroArchやほかのフロントエンド | `pokedaisy-libretro-aarch64.zip` |

最初の3つには、PokeDaisyのプログラムと、その隣に3つのコアが入っています：ゲームボーイアドバンス用、ゲームボーイとゲームボーイカラー用、DS用です。最後の2つはコアだけです。あとは、このページの自分の機器のところに進んでください。

## Steam DeckとLinuxのパソコン

1. `pokedaisy-linux-x86_64.zip` をホームフォルダのどこか、たとえば `PokeDaisy` フォルダに展開します。Steam Deckではデスクトップモードでやってください。プログラムの `pokedaisy-linux` と3つのコアが入った `pokedaisy-linux-x86_64` フォルダができます。
2. そのフォルダの中に `roms` というフォルダを作り、ゲームをコピーします。（先に一度起動したいなら、最初の起動でPokeDaisyが作ります。）
3. `pokedaisy-linux-x86_64` フォルダでターミナルを開き、`./pokedaisy-linux --data .` で起動します。

ゲームの一覧が開きます。十字ボタン（または矢印キー）でゲームを選び、A（キーボードではZ）を押します。`--data .` は、`roms`、`saves`、`states` と設定をそのフォルダに置くようPokeDaisyに伝えます。自分でショートカットを作るときは、`--data` にフォルダのフルパスを渡してください。

### ゲームモードで遊ぶ

1. デスクトップモードでSteamを開き、**ゲーム** > **Steam以外のゲームをマイライブラリに追加** を選びます。
2. **参照** を選んで `pokedaisy-linux-x86_64` フォルダに行き、`pokedaisy-linux` を選んで追加します。
3. ゲームモードに戻り、ライブラリからPokeDaisyを起動します。

Steamはプログラムをそのフォルダで起動するので、起動オプションはいりません。ゲームモードでは画面いっぱいに表示され、Steam Deckのボタン、タッチスクリーン、音はいつもどおり使えます。

## TrimUIの携帯機

`pokedaisy-linux-aarch64.zip` は、CrossMixとNextUI向けに作ったTrimUIアプリ版のPokeDaisyです。

1. パソコンでzipを展開します。`pokedaisy-linux-aarch64` フォルダができます。
2. SDカードをパソコンに入れ、そのフォルダをカードの `Apps` フォルダ（CrossMixの場合）にコピーします。
3. その中の `roms` フォルダにゲームをコピーします（なければ作ってください）。
4. カードを戻し、AppsメニューからPokeDaisyを起動します。

アプリはすべてを自分のフォルダに置き、全画面で動きます。CrossMixでは、遊んでいる間CPUの最低速度を上げ、終わると元に戻します。

> [!NOTE]
> このプログラムは、TrimUIで単独のアプリとしてはまだ動かしたことがありません（コアは動いています）。荒いところがあるかもしれません。どうだったか教えてください。

## PortMasterの入った携帯機

1. パソコンで `pokedaisy-portmaster-aarch64.zip` を展開します。中には `PokeDaisy.sh` と `pokedaisy` フォルダがあります。
2. 両方をSDカードの、ファームウェアの `ports` フォルダにコピーします。
3. ゲームは今の場所のままでかまいません。ゲームの一覧には `ports/pokedaisy/roms` と、ファームウェアの `gba`、`gb`、`gbc`、`nds` フォルダ（と `Game Boy Advance`、`Game Boy Color`、`Game Boy`、`Nintendo DS` という名前のフォルダ）が出ます。
4. Portsの一覧からPokeDaisyを起動します。

PortMasterが機器のボタンをPokeDaisyに伝えるので、設定しなくても使えます。MenuボタンかGuideボタンでPokeDaisyのメニューが開き、ほかのportと同じくSELECTとSTARTを一緒に押すと終了します。ROCKNIXの入ったGKD 350Hで遊ばれています。

## NXReduxの入ったTrimUI Brick

1. `PokeDaisy-NXRedux.zip` をSDカードのいちばん上に展開します。`Emus/tg5040/PDAI.pak` と `Roms/PokeDaisy (PDAI)` フォルダが追加されます。
2. ゲーム（展開済みの `.gba`、`.gb`、`.gbc`、`.nds`）を `Roms/PokeDaisy (PDAI)` に置きます。
3. NXReduxのPokeDaisyコレクションから起動します。

セーブデータ、ステート、はやおくり、シェーダー、続きから遊ぶ機能は、NXReduxが自分のメニューで管理します。NXReduxはファイル名だけでセーブデータに名前をつけるので、ゲームごとに違うファイル名にしてください。

## RetroArchとほかのフロントエンド

1. `pokedaisy-libretro-x86_64.zip`（パソコン）または `pokedaisy-libretro-aarch64.zip`（ARM携帯機）を展開します。フォルダには3つのコア、そのライセンス、`SOURCE.txt` が入っています：
   - `pokedaisy_gba_libretro.so` はゲームボーイアドバンスのゲームを動かします。
   - `pokedaisy_gbc_libretro.so` はゲームボーイとゲームボーイカラーのゲーム（とゲームボーイアドバンスも）を動かします。
   - `pokedaisy_ds_libretro.so` はDSのゲームを動かします。
2. コアをフロントエンドのコアフォルダにコピーします。RetroArchでは、その場所が **設定** > **ディレクトリ** > **コア** に出ています。
3. PokeDaisyのコアでゲームを読み込みます（DSのコアはPokeDaisy DSと表示されます）。

セーブデータ、ステート、はやおくりは、ほかのコアと同じくフロントエンドが管理します。コンパニオンの設定はフロントエンドのセーブフォルダに入ります：ゲームボーイとゲームボーイアドバンスのコアは `pokedaisy/settings.ini`、DSのコアは `pokedaisy-ds/settings.ini` です。コアを人に渡すときは、ライセンスも一緒に渡してください。

### はじめから入っているもの：plorpOSとspruceOS

[plorpOS](https://github.com/thwonp/plorpos) と [spruceOS](https://github.com/spruceUI/spruceOS) のナイトリービルドには、ゲームボーイ、ゲームボーイカラー、ゲームボーイアドバンスのコアがはじめから入っているので、ダウンロードするものはありません。ゲームのコアにPokeDaisyを選んでください（plorpOSでは、ゲームボーイの棚のCoreの行で選びます）。

## セーブデータを持ってくる

PokeDaisyのプログラム（最初の3つのダウンロード）では、ゲームのセーブデータは `roms` の隣の `saves` フォルダにあり、ROMのファイル名全体に `.sav` をつけた名前です：`FireRed.gba` なら `saves/FireRed.gba.sav` です。ほかのエミュレーターのセーブデータも、その名前でそこにコピーすれば使えます。RetroArchの `.srm` も同じです。念のため、先に古いセーブデータのコピーを別の場所に残しておきましょう。

ゲームを開く前に、PokeDaisyはセーブデータを `saves/pokedaisy-backups` にコピーし、新しい10個を残します。古いものが必要になったらそこにあります。ほかのエミュレーターのステートは読み込めません。

RetroArch、NXRedux、ほかのフロントエンドでは、セーブデータはフロントエンド自身のファイルで、いつもの場所にあります。

## アップデート

新しいzipをダウンロードし、古いフォルダに上書きして展開します（NXReduxでは、もう一度SDカードのいちばん上に）。ゲーム、セーブデータ、ステート、設定はzipに入っていないので、そのまま残ります。フロントエンドでは、新しいコアを古いコアに上書きコピーします。

## うまくいかないとき

- **起動すると Permission denied と出る：** 展開したときに、プログラムの実行の許可が消えています。フォルダの中で `chmod +x pokedaisy-linux` を実行してください。
- **`GLIBC_2.35` や `libSDL2` という名前の出るエラー：** LinuxがPokeDaisyに必要なものより古いか、SDL2が入っていません。ディストリビューションからSDL2を入れるか（Ubuntuなら `sudo apt install libsdl2-2.0-0`）、新しいシステムで試してください。
- **ゲームが一覧に出ない：** `roms` の中に直接（中のフォルダではなく）、展開して置き、名前が `.gba`、`.gb`、`.gbc`、`.nds` で終わっている必要があります。
- **AとBが逆になっている：** メニューを開き（STARTとSELECTを一緒に押すか、GuideボタンかMenuボタン）、`A/B BUTTONS` を `SWAPPED` にしてください。
- **セーブデータが読まれない：** セーブデータは `saves` の中に、ROMのファイル名全体に `.sav` をつけた名前で置く必要があります。古いコピーは `saves/pokedaisy-backups` を見てください。
- **コントローラーが反応しない：** SDLがまだそのコントローラーを知りません。そのマッピングの入った `gamecontrollerdb.txt` をデータフォルダ（`roms` の隣）に置いてください。

それでも困ったら、どの機器とどのファイルを使ったかを [お知らせください](https://pokedaisy.com/ja/feedback/#bug)。ターミナルから起動したときは、何が起きたかをPokeDaisyがそこに表示します。TrimUIアプリは `launch.log` に、PortMasterのportは `log.txt` に書き、どちらも自分のフォルダに置きます。
