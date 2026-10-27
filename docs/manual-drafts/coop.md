---
title: Play together
summary: How to pair with a friend's Emerald save, what the link shows, and how to battle together.
section: player
order: 20
keywords: [co-op, coop, multiplayer, two player, netplay, online, link, friend, invite, qr]
---

> DRAFT, not published. Co-op is behind `FeatureFlags.coop` (off by default), so this page lives in
> `docs/manual-drafts/` and the manual doesn't list it. When the flag goes: move this file into
> `docs/manual/`, set `order` to one after the Playing section's last page, add the CO-OP rows to
> `settings-reference.md` (both screens' tables) and the search words to `synonyms.yml`, move the rows'
> keyword lines out of `FLAGGED_SETTING_KEYWORDS`, and add `Shot`s to `ui-preview/src/render/DocsShots.kt`
> (`coop-invite`, `coop-confirm`, `companion-coop-connected`: the invented save's trainer, never a real
> name). No shots exist yet, so this page references none. Delete this note when it moves.

Co-op lets two players, each on their own device with their own Emerald save, pair up and fight wild and trainer battles
together. You pair first, the link between your devices stays alive, and then the battles can be shared.

## What you need

- Pokémon Emerald (English) on both devices, and the same ROM on both.
- A save you have saved in the game at least once.
- Both devices on the internet. The same Wi-Fi is not required.
- No cheats loaded. The link stays off while any cheat is on. Once the link is on, switching a cheat on is refused with a notice.

## Pair with a link or QR code

1. On the top screen open Settings, then `CO-OP`, then `PAIR`.
2. Under `PICK A SAVE`, tap the save you want to pair. Each save has one partner.
3. You now see an invite: a QR code, `COPY LINK` and `SHARE LINK`.
4. Your partner opens the same page on their device and pastes your link under `JOIN WITH A LINK`, or scans
   your QR code with the system camera, which opens the link in PokeDaisy.

Both devices then show `ACCEPT THIS PLAYER?` with the other trainer's name and ID. Check them, then accept.
Pairing finishes only when both of you accept. If either says no, nothing is saved.

## Pair on the same Wi-Fi

Under the invite, `SAME WI-FI` lists players on your network who are on their own invite page. Tap one to
connect. If nothing shows up, use the link, or try `BY IP`.

## Pair by IP address

`BY IP` shows your address. Type your partner's address in the box and connect. Use it when the same
Wi-Fi list stays empty, for example on a network that blocks discovery.

> [!NOTE]
> `SAME WI-FI` and `BY IP` are hidden while `RELAY ONLY` is on, because both need your devices to see each
> other's addresses.

## Partners and unpairing

`PARTNERS` lists every pairing, one row each, with the save it belongs to. A save has one partner. To pair it
with someone else, tap the row and choose `UNPAIR` first. The same list is in the companion's SETTINGS.

## Relay and relay only

Players connect directly when they can. When they can't, a relay server passes the traffic along. A build
comes with a relay of its own, or you type one. With no relay at all, only `SAME WI-FI` and `BY IP` work.

| Setting | What it does |
| --- | --- |
| `RELAY` | Shows which relay is used: the build's own, one you typed (https only) or `NONE`. |
| `RELAY ONLY` | Never connects directly, so your partner never sees your IP address. It can be slower, and `SAME WI-FI` and `BY IP` are hidden. |

## The link in the companion

The companion's SETTINGS has a `LINK` row that tells you where the link stands.

| Value | What it means |
| --- | --- |
| `OFF` | Nothing is running yet, or there is no save to pair. |
| `CONNECTING` | Looking for your partner. |
| `CONNECTED` | Your partner is there. |
| `RECONNECTING` | The connection dropped and the app is trying again. |
| `NOT PAIRED` | This save has no partner. Pair it first. |
| `NOT RECOGNISED` | Your partner's device didn't accept you, for example after they unpaired. Pair again. |
| `CHEATS ON` | Turn every cheat off to use the link. |
| `NOT EMERALD` | The game running isn't English Emerald. |

## When pairing fails

| You see | What to do |
| --- | --- |
| Your partner's PokeDaisy is a different version | Update both devices to the same version. |
| Your partner plays a different Emerald ROM | Both of you need the same ROM. |
| This save is already paired | Open `PARTNERS` and `UNPAIR` it first. |
| The other player said no | Ask them to try again and accept. |
| Couldn't reach your partner | Check both internet connections, then `TRY AGAIN`. |
| No answer | The other device didn't respond in time. `TRY AGAIN`. |
| That invite link isn't valid | Copy the whole link again. |

## Battling together

Once you are paired and both devices are connected, a wild battle, a trainer battle or a story battle can be shared.
Your partner can join yours, and you can join theirs. A joined battle is the game's own link battle, running in both
games at once: each of you plays it in your own game, from your own side, with your own game's menus, at your own
screen's size. Nothing is streamed from one device to the other and nothing is drawn over the battle.

### The join prompt

When one of you starts a battle that can be joined, the other one sees `{NAME} STARTED A BATTLE NEARBY.` with
`JOIN` and `NOT NOW`. The prompt stays for 30 seconds. If you don't answer, it counts as `NOT NOW` and the battle
goes on alone. While the prompt is open, the battle waits at its start on the device that began it.

### Who can join

- Your partner must be on the same map as you and within `NEARBY RANGE` steps of you. Set it in the CO-OP group
  of SETTINGS: `2 STEPS`, `4 STEPS`, `8 STEPS` (the default), `16 STEPS` or `32 STEPS`.
- Your partner must be free: walking around, or on the top level of the START menu. Someone who is in a battle,
  in a menu screen, in a talk or on a fade is not asked.
- Ordinary wild battles, battles against two trainers at once, battles against one trainer who has two or more
  Pokémon, and story battles (see below) can be joined. A trainer with only one Pokémon, a legendary or other one off
  wild Pokémon, the Safari Zone, link battles and the Battle Frontier can't be joined, so no prompt appears.
- Against one trainer you both face the same person, who sends out Pokémon on both sides. The prize is what
  that trainer pays on their own, and you both get it.
- Both players need `PLAYER LOOK` and `TEXT LOOK` off. If either is on, nothing is offered until it is off.

### Story battles

A gym leader, your rival or a team's boss can be joined in any save, not only when `PLAY TOGETHER ONLY` is on. You
both have to stand at the same spot in your own games, in front of the same trainer, and neither of you has beaten
them yet. Whoever stood there first asks, and the other one gets the prompt. Say `JOIN`, and each of you talks to the trainer in
your own game, as you always do. The game's own screen asks each of you to choose three Pokémon, and when you have
both chosen, the battle starts. The win counts in both stories, badge included. If your partner isn't there, the
battle is a normal one for you alone.

### Three each

In an ordinary joined battle each of you sends your first three Pokémon that can still fight, in party order. In a
story battle you choose your own three. In the battle you control your own Pokémon from your own screen. When it
is your partner's turn to choose, you see the game's own waiting messages, as in any link battle. Battle text moves
on by itself, so neither of you waits for the other to press a button.

### Menus and items

The battle box, the moves, the party and the bag are your game's own. `BAG` uses your own bag: whatever you use is
taken from your own save. Items that restore a move's PP can't be used by the player who joined: the game says
`Not now!`, and nothing is used. `RUN` works in wild battles only, and a successful run ends the battle for both of you.

### Balls with two wild Pokémon

With two wild Pokémon in the battle, a ball is refused, just as in the game's own double battles. Weaken one,
knock the other out, then throw. Either of you can throw. Whoever catches the last one keeps it: it goes to their own
party, or their PC if the party is full, with their own name on it.

### Experience and level-ups

Each Pokémon gets the experience a normal single battle would give. For the player who joined, a level-up box
closes by itself, and a Pokémon that wants to learn a move or to evolve does that after the battle, in their own game.

### What each save gets

When the battle ends, each save gets its own result: your Pokémon with their new health, PP and experience, a
Pokémon you caught, the items you used, evolutions and the prize money, which is what a two trainer battle pays.
Trainers you beat stay beaten for both of you. A trainer your game already had beaten pays you nothing a second time.
The game saves for you after a battle that changed your progress, and keeps a backup of your save first. A Pokémon the
player who joined catches is handed over right after the battle, and the other device keeps it safe until that save is done,
so a dropped link or a crash never loses it.

### Blackout

You black out only when none of the Pokémon in your whole party can fight, not just the three you sent. Then the
game's own blackout happens for you, as if you had lost alone.

### If the link drops

Both devices pause the battle and show `WAITING FOR {NAME}...` with the seconds left. If the link comes back within a
minute, the battle carries on where it was. Nobody times out while the link is up, however long you take to choose.

If it doesn't come back:

- A story battle counts as a loss with a blackout for both of you, in every save. The same goes if the link drops while
  you are both still choosing your three. The trainer is not beaten, nothing is paid, and what your Pokémon earned
  before the drop stays, as in any blackout.
- Any other battle ends for both of you and you are back on the field. You win nothing: no prize, and a trainer you
  were fighting can be fought again. What your Pokémon earned before the drop stays (experience, a Pokémon already
  caught), but a Pokémon that grew a level in that battle does not evolve or learn a move because of it; that waits for
  its next level-up.

### What is blocked meanwhile

Until the battle is over you can't load or save a state, rewind, restart or soft reset the game, change cheats, turn
`REUSABLE TMS` on or off, or change `PLAYER LOOK` or `TEXT LOOK`. You see a notice when you try. Achievements pause, as with cheats.

## Playing together only

`PLAY TOGETHER ONLY` is in the companion's SETTINGS, in the `CO-OP` group. It turns your two games into one shared
run: you can only play while your partner is connected, and the story waits for both of you.

### When you can turn it on

- You are paired with that partner, and you are both connected.
- Both of you are on a new game. If either of you already has a starter, the row stays off and says which of you it is.

Tap the row and confirm with `YES`. Your partner gets a question on their device: `ACCEPT` or `NO`. Until they
answer, the row says it is waiting. It only turns on when both of you agree.

### What waits

- If your partner drops off, the game holds with `Waiting for your partner...`. It carries on when they are back.
  `CLOSE GAME` is always there if you would rather stop.
- When you reach a story moment, the game holds until your partner stands at the same spot. The screen says
  `Waiting for` your partner's name `to get here.` To cancel, walk away. Your partner can walk away too.
- Everyday trainers do not wait for anyone.

### What it turns off

`LOAD STATE` and `REWIND` do not work in a game that is locked, because they would pull the two runs apart.
`SAVE STATE`, the companion and the rest of the settings work as usual.

### Turning it off

Tap the row and confirm. It can't be turned on again for those two games, so only turn it off when you mean it.
