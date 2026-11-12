<!-- Made from docs/manual/translations/ja/3ds.md by website/scripts/install-guides.mjs: edit that page, not this file. -->

# ニンテンドー3DSにインストールする

*PokeDaisyの自作アプリを3DSに入れる手順を一つずつ。ゲームとセーブデータの置き場所、音を出すための準備まで説明します。*

[English](INSTALL.md) · **日本語** · [한국어](INSTALL.ko.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [Español](INSTALL.es.md) · [Português (Brasil)](INSTALL.pt-br.md) · [Português (Portugal)](INSTALL.pt.md)

ウェブサイトでも読めます: https://pokedaisy.com/ja/docs/3ds/

PokeDaisyはニンテンドー3DSでも自作アプリ（homebrew）として動きます。上画面でゲームが動き、下画面（タッチスクリーン）にコンパニオンが出ます。ゲームのライブラリ、セーブエディター、セーブデータどうしの交換もついています。このページでは、カスタムファームウェアの入った3DSから、最初のゲームを始めるまでを説明します。

> [!NOTE]
> 3DS版はまだ早期プレビューです。**Newニンテンドー3DS、Newニンテンドー3DS LL、Newニンテンドー2DS LL** 向けに作られています。ニンテンドー3DS、3DS LL、2DSでも起動はできますが、動きが遅くなることがあります。メニューは今のところ英語だけです。

![3DSのPokeDaisyライブラリ。上画面にカーソルのゲーム、下画面にゲームの一覧](../docs/manual/shots/3ds-library.png "ライブラリ：上画面にカーソルのゲーム、下画面にあなたのゲーム")

## 必要なもの

- **カスタムファームウェア（Luma3DS）とHomebrew Launcherが入った3DS。** まだ入っていなければ、[3ds.hacks.guide](https://3ds.hacks.guide/) の手順に従ってください。どの本体、どのシステムバージョンにも対応して更新され続けているコミュニティのガイドで、このページに必要なものがすべてそろいます。終わったらここに戻ってきてください。
- **SDカード**と、それを読めるパソコン（カードを抜きたくなければ、3DSのFTPアプリでもかまいません）。
- **自分のゲーム。** PokeDaisyにゲームは入っていません。自分の持っているカートリッジから自分で吸い出したROMを使ってください：`.gba`、`.gb`、`.gbc` のファイルです。

## ステップ1：アプリを手に入れる

1. パソコンで [PokeDaisyのリリースページ](https://github.com/lidor30/pokedaisy/releases) を開きます。
2. 3DS用のファイルがある一番新しいリリースから、`pokedaisy.3dsx` をダウンロードします。

## ステップ2：SDカードにコピーする

1. 3DSの電源を切り、SDカードをパソコンに入れます。
2. カードのいちばん上にある `3ds` フォルダを開きます。カスタムファームウェアを入れたときに作られています。なければ作ってください。
3. その中に `pokedaisy.3dsx` をコピーします。これでカードに `sdmc:/3ds/pokedaisy.3dsx` ができます。

`sdmc:/` は3DSでのSDカードのいちばん上の呼び名です。パソコンから見ると、`sdmc:/3ds/` はカードの `3ds` フォルダのことです。

## ステップ3：ゲームとセーブデータを入れる

1. カードのいちばん上に `pokedaisy` フォルダを作り、その中に `roms` フォルダを作ります：`sdmc:/pokedaisy/roms/`。（先に一度PokeDaisyを起動すれば、どちらも自動で作られます。）
2. ゲームを `roms` にコピーします。ゲームごとのフォルダには分けず、フォルダの中に直接置いてください。zipは先に展開しておきます。3DS版のPokeDaisyが読めるのは、そのままの `.gba`、`.gb`、`.gbc` ファイルだけです。
3. ほかのエミュレーターのセーブデータを使うときは、ゲームと同じ名前で、最後を `.sav` にして `roms` の中のゲームの隣に置きます：`Pokemon Emerald.gba` なら `Pokemon Emerald.sav` を読みます。RetroArchのセーブデータは `.srm` で終わるので、`.sav` に名前を変えてください。念のため、パソコンにもセーブデータのコピーを残しておきましょう。

セーブデータがなければゲームは最初から始まり、ゲームの中で初めてセーブしたときに、PokeDaisyが同じフォルダに `.sav` を作ります。

## ステップ4：音を出せるようにする

3DSの自作アプリは、本体のサウンド用ファームウェアをSDカードにコピーしたあとでないと音を出せません。一度だけやれば大丈夫です：

1. SDカードを戻して3DSの電源を入れ、**L** を押したまま、十字ボタンの **下** と **SELECT** を押してRosalinaメニューを開きます。
2. **Miscellaneous options** を選び、**Dump DSP firmware** を選びます。
3. メニューが閉じるまで **B** を押します。

これで `sdmc:/3ds/dspfirm.cdc` ができます。これがないと、ゲームは音なしで動きます。ほかのアプリのために前にやったことがあれば、何もしなくてかまいません。

## ステップ5：PokeDaisyを起動する

1. Homebrew Launcherを開きます。3ds.hacks.guideで準備した本体なら、「ダウンロードプレイ」から開きます。
2. 一覧から **PokeDaisy** を選びます。

ライブラリが開きます。下画面にゲームの一覧、上画面にカーソルのゲームが出て、一度遊んだゲームならプレイヤー、プレイ時間、手持ちのポケモンも表示されます。十字ボタンで動かすか、ゲームをタッチして、**A** で遊べます。

![ゲームのない最初の起動画面。上画面に「ゲームを sdmc:/pokedaisy/roms/ に入れて」と表示](../docs/manual/shots/3ds-library-empty.png "まだゲームがないとき：ライブラリが置き場所を教えてくれます")

ライブラリに **No games yet** と出たら、ゲームが `sdmc:/pokedaisy/roms/` の中に直接あり、名前が `.gba`、`.gb`、`.gbc` で終わっているか確かめてください。

## 操作

| ボタン | すること |
| --- | --- |
| **A** | カーソルのゲームを遊ぶ |
| **L** / **R** | ライブラリの表示を切り替える：`ALL`、`RECENT`、`GBA`、`GB`、`HACKS` |
| **SELECT** | 次のページ：ゲーム、`SAVE EDITOR`、`TRADE SAVES`、`SETTINGS` |
| **START** | PokeDaisyを閉じてHomebrew Launcherに戻る |

ゲーム中は、ゲームボーイアドバンスのボタンが3DSの同じボタンになり（スライドパッドは十字ボタンとして使えます）、下画面がコンパニオンです。ほかにも：

| ボタン | すること |
| --- | --- |
| **Y** | クイックメニュー：パソコン、`FLY`、自転車、ステート、はやおくりなど |
| **X** | フィールドで、ボタンをコンパニオンに渡す（もう一度 **X** でゲームに戻す） |
| **ZR** | 押している間はやおくり（New 3DS） |
| **ZL** | コンパニオンの前のタブ（New 3DS） |

ゲームをやめるときは、コンパニオンの `SETTINGS` のいちばん下にある `CLOSE GAME` を使います。**HOME** ボタンや本体を閉じるのも使えます。

![3DSのライブラリのSETTINGSページ：VIEW、SORT、THEME、DARK MODE、SCREEN、ASPECTと音の設定](../docs/manual/shots/3ds-settings.png "ライブラリのSETTINGS：すべてのゲームに共通の見た目と画面の設定")

## 遊べるゲーム

ゲームボーイ、ゲームボーイカラー、ゲームボーイアドバンスのゲームならどれでも動きます。コンパニオンが読めるのは、Android版のPokeDaisyが知っているゲームです：FireRed、LeafGreen、Ruby、Sapphire、Emerald、Red、Blue、Yellow、Gold、Silver、Crystal、そしてたくさんのROMハック（一覧は [対応ゲーム](https://pokedaisy.com/docs/supported-games/) にあります）。3DS版ではDSのゲームは動きません。

## SDカードのどこに何があるか

| 場所 | 中身 |
| --- | --- |
| `sdmc:/3ds/pokedaisy.3dsx` | アプリ |
| `sdmc:/3ds/dspfirm.cdc` | サウンド用ファームウェア（ステップ4） |
| `sdmc:/pokedaisy/roms/` | あなたのゲームと、その隣に各ゲームの `.sav` |
| `sdmc:/pokedaisy/roms/pokedaisy-backups/` | 起動のたびにとるセーブデータのコピー。ゲームごとに新しい10個 |
| `sdmc:/pokedaisy/states/` | ステート |
| `sdmc:/pokedaisy/cheats/` | チート。ゲームごとに1ファイル |
| `sdmc:/pokedaisy/settings.ini` | 設定 |
| `sdmc:/pokedaisy/log.txt` | 前回の起動で何が起きたか。不具合を報告するときに添えてください |

## アップデート

新しい `pokedaisy.3dsx` をダウンロードし、`sdmc:/3ds/` の古いファイルに上書きします。ゲーム、セーブデータ、ステート、設定はすべて `sdmc:/pokedaisy/` にあるので、そのまま残ります。

## うまくいかないとき

- **音が出ない：** `sdmc:/3ds/dspfirm.cdc` がありません。ステップ4をやってください。
- **Homebrew LauncherにPokeDaisyがない：** ファイルが `sdmc:/3ds/` にあり、名前が `.3dsx` で終わっている必要があります。
- **ゲームが一覧に出ない：** `sdmc:/pokedaisy/roms/` の中に直接置き、中のフォルダには入れず、zipのままにしないでください。
- **セーブデータが読まれない：** セーブデータはゲームとまったく同じ名前で、最後が `.sav` である必要があります。起動のたびにセーブデータは `sdmc:/pokedaisy/roms/pokedaisy-backups/` にコピーされるので、古いコピーが必要ならそこにあります。
- **動きが遅い：** ニンテンドー3DSや2DSは、Newの本体より遅くなります。ライブラリの `SETTINGS` で `SCREEN` を `PIXEL 1x` にしてください。いちばん軽いモードです。

それでも困ったら、`sdmc:/pokedaisy/log.txt` を添えて [お知らせください](https://pokedaisy.com/ja/feedback/#bug)。
