---
title: HUD
summary: Your party, a minimap, your Poké Balls and quick buttons for the bag and this area's wild Pokémon, right on the game screen while you walk around.
section: player
order: 4
keywords: [field hud, heads up display, overlay, minimap, mini map, party, hp, status, balls, bag, wild, encounters, overworld, legends, single screen]
---

Modern Pokémon games keep the important things on screen while you explore: your team, where you are, what you're carrying. The HUD does the same for the classic games. It's most useful on a device with one screen, where the companion is tucked away until you open it, but it works with two screens too.

It's on by default. Turn it off, or change how it looks, in `HUD`, in the `SCREEN` group of Settings on either screen.

On the Game Boy it shows in Yellow (the English cart), Yellow Legacy, Crystal, Crystal Legacy, Crystal Clear and Polished Crystal (3.2.3, the normal build) too, with or without `EXPAND`. Those games have no pictures for their items, so your balls show as a plain drawn ball with the count, and the bag lists your items by name, the way their own bag does.

## What it shows

| Part | What you see | Tap it to |
| --- | --- | --- |
| Party | Each Pokémon's icon and HP bar. A status like `PSN` or `SLP` sits in a row over the icons, and a fainted Pokémon is faded with `FNT`. The party's window only grows that row while someone has a status. | Open that Pokémon's summary, the same one the `PARTY` tab shows. Up and down step through your team. |
| Minimap | The region map around you, with your head on the spot you're in and the place's name under it. | Open the whole map, with `PLACES` and `FLY`, always in the map's own shape. |
| Poké Balls | The balls in your bag and how many of each (Elite Redux keeps no counts, so just the balls there). | Open the bag on its `POKé BALLS` pocket. |
| Steps left | While an item that keeps wild Pokémon away is working, a window by your balls shows its icon and the steps it has left, counting down as you walk, over a bar that drains from green to yellow to red. The count turns gold for the last 20 steps, and the window goes when the effect wears off. It shows for the games whose [bag](bag.md) can use those items. | Open the bag. |
| `PC` | Your game's own PC, opened right where you stand, so you can reach your Pokémon and stored items without walking to a Pokémon Center. The small arrow on its corner means it opens the game's own screen, not one of the HUD's. It shows for the games whose [bag](bag.md) has the `PC` button. | |
| `BAG` | A compact bag: the pockets as tabs and your items as a grid with counts. Pick an item to read about it, and `USE` it where the companion can. The button and the bag carry the game's own name for it, like the [bag tab](bag.md) (`PACK`, `CUBE`, `ITEM`...). | |
| `WILD` | This area's wild Pokémon: by grass, water, fishing rod or rock, with their levels and how often they show up. Ones you haven't seen stay `???`, and a ball marks the ones you've caught. `KEEP OPEN` leaves the list up without dimming the game, so it follows you from map to map until you close it with its X. The red number on the button counts the ones you haven't caught yet. | |

The game keeps running while the map, bag, wild list or a summary is open. Tap anywhere around it, or press `BACK`, to close it. With the `GLASS` and `WHITE GLASS` looks these open in see-through panels too.

`WILD` needs the game's own encounter tables, so it shows only for games whose `GUIDE` tab has a `HERE` page. Items the companion can't use (and ones used on a single move) have no `USE` here: use them from the `BAG` tab or in the game.

## When it shows

Only while you're walking around. The first time you play a game, it shows up once you've taken a few steps (PokeDaisy learns what that game's field looks like, and remembers it). It fades out for a battle, a menu screen like the party or the bag, the game's own town map, and a moment later it comes back. With `EXPAND` on it also steps aside for a conversation or a sign. On a phone held upright it's off for now: the companion sits right under the game there.

Without `EXPAND`, the HUD stays on the game's picture, not in the bars beside it (except `RAILS`, which is made for those bars). If the touch controls are showing, the HUD moves its corners in so it never covers the rows of buttons along the top and bottom of the game. Held sideways it keeps to the middle of the screen with `EXPAND` too, away from the sides where the buttons sit. Buttons you move further in with `EDIT LAYOUT` don't push it around: with `CORNERS` and `CORNERS 2`, the party, the minimap and the buttons each step just far enough to sit beside a button that's in their corner, and go back to the corner when it's clear.

When the game's picture is too narrow for your party beside the buttons, for example with the companion's side panel locked beside the game, the HUD gets smaller: the party and the minimap step aside, and your balls and the buttons sit in the bottom left corner, with a `MAP` button after `WILD`. The party is still a tap away in the companion. `LAYOUT` can't be changed meanwhile: it says it's not available because the screen is too small.

In a DS game on one screen, a `TOUCH` button joins them while `DS LAYOUT` floats the touch screen in: it brings the touch screen up over the game, and a second tap closes it (see [DS layout](devices.md#ds-layout-two-screens-on-one)).

On a device with one screen, the button that opens the companion joins `BAG` and `WILD` as `COMPANION` while the HUD is up, instead of sitting on the screen's edge. It stays there while the companion's window is open. When the HUD steps aside (in a battle or a menu), the edge tab is back.

## Make it yours

Tap `LAYOUT`, `LOOK` or `SIZE` to step to the next choice. The HUD changes right away, so you can see each one on the game as you go.

| Setting | Choices | What it does |
| --- | --- | --- |
| `HUD` | **`ON`**, `OFF` | The whole HUD. |
| `LAYOUT` | **`CORNERS`**, `CORNERS 2`, `RAILS`, `DOCK` | `CORNERS` puts the minimap top right (the games show a new area's name at the top left), your party bottom left and the rest bottom right. `CORNERS 2` swaps the right side: the minimap bottom right, the balls and buttons top right. `RAILS` lines everything up in two columns at the sides of the screen, in the bars beside the game (or over the extra map with `EXPAND`), so the game's own picture is never covered. `DOCK` puts your party, balls and buttons in one bar along the bottom, and the minimap top right. |
| `LOOK` | **`GLASS`**, `WHITE GLASS`, `WINDOW` | `GLASS` is a dark see-through panel that lets the game show through, with light text. `WHITE GLASS` is the same in white, with dark text. `WINDOW` uses the white windows the rest of the app has. In every look the windows are shaped like the games' battle HP boxes, with a shadow below and to the right, and whatever is picked (a pocket, an item, `KEEP OPEN`) gets an orange edge, like the chosen slot on the games' party screen. |
| `SIZE` | `SMALL`, **`NORMAL`**, `LARGE` | How big it is, from small to large. |
| `PARTY`, `MINIMAP`, `POKé BALLS`, the steps left, `PC`, `BAG`, `WILD POKéMON` | **`ON`**, `OFF` | Show or hide each part. |

## On Linux and in other launchers

When PokeDaisy runs as a libretro core (the [Linux program](linux.md), plorpOS, the NXRedux collection, RetroArch), the
HUD is drawn in the game's own pixels, and the screen shows those several times larger. So at `SMALL` and `NORMAL` it's
drawn at about half the app's size: smaller pictures of your party and Poké Balls, small numbers, and buttons with
their pictures but no names. `LARGE` draws it at full size, with the names on the buttons. Every `LAYOUT` works at each
size. When your party and the rest don't fit side by side, the HUD keeps to the bottom left corner, with a `MAP` button
in place of the minimap.
