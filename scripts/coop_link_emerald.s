@ The co-op link bridge on retail English Emerald (plan 6, app/src/main/cpp/pk_coop_link.c): link.c's block layer without the
@ cable. Our Thumb code, assembled by scripts/gen_coop_emerald.py at COOP_EM_LINK_CODE_ADDR (the ROM's empty tail, 0x08FFD000:
@ below the story lock's 0x08FFE000) into pk_coop_emerald.h's COOP_EM_LINK_CODE.
@
@ While a link battle runs (pk_coop_link_begin .. pk_coop_link_reset) each game function below has its first 8 bytes replaced by
@ `ldr r3, [pc, #0]; bx r3; .word <our routine | 1>` (the generator's COOP_EM_LINK_HEAD_*): the routine takes the function's
@ place, whole - nothing of the original runs. The bridge's RAM (struct pk_coop_link_ram, pk_coop.h) is at link_ram:
@   SendBlock          the block goes into the outbox (the app takes it from there and sends it over the network) and, as the
@                      cable's serial interrupt would do for the sender's own slot, into gBlockRecvBuffer[myId] with
@                      gBlockReceivedStatus[myId] set; a block over BLOCK_BUFFER_SIZE, or an outbox without room, is refused;
@   IsLinkTaskFinished  1 while the outbox has room and the own slot has been read (the game resets it) - the cable's "the
@                      last block is out";
@   GetMultiplayerId   the bridge's myId (0 = the host, the link master; 1 = the partner), not the SIO register;
@   HandleLinkConnection / HasLinkErrorOccurred   nothing happens on a serial line that doesn't exist (the game's
@                      link status, gLinkStatus, is written once by pk_coop_link_begin);
@   OpenLink           the link is open at once: gReceivedRemoteLinkPlayers set;
@   CloseLink / SetCloseLinkCallback   the link is closed at once: gReceivedRemoteLinkPlayers cleared, and `ended` when it is the
@                      battle itself that closes it (BATTLE_TYPE_LINK_IN_BATTLE set).
@ Blocks from the other player are written by the app (pk_coop_link_give) into gBlockRecvBuffer[1 - myId] and the game's own
@ gBlockReceivedStatus: no hook is needed to receive.
@
@ The generator puts `.set COOP_EM_<name>, value` for every address / offset / constant of the header in front of this
@ file. Each routine the game is pointed at starts at a global `link_entry_<NAME>` label (COOP_EM_LINK_ENTRY_<NAME>).

    .syntax unified
    .cpu arm7tdmi
    .thumb
    .text

@ The bridge's RAM (struct pk_coop_link_ram at PK_COOP_LINK_RAM). Global, so the header carries them and pk_coop_link.c checks
@ them against the struct and pk_coop.h at compile time.
    .set link_ram, 0x0203D000
    .set link_active, 0
    .set link_myid, 1
    .set link_ended, 2
    .set link_head, 4
    .set link_tail, 5
    .set link_overrun, 10
    .set link_out, 0x40
    .set link_esize, 0x108
    .set link_out_n, 16
    .set link_block_max, 0x100
    .global link_ram, link_active, link_myid, link_ended, link_head, link_tail, link_overrun, link_out, link_esize
    .global link_out_n, link_block_max

@ SendBlock(u8 unused, const void *src, u16 size): r1 = src, r2 = size. Returns 1 (queued) or 0 (refused).
@ An outbox entry is: u32 src (the game's address, for the app to tell the block's kind), u16 size, u16 0, the bytes.
    .global link_entry_SENDBLOCK
    .thumb_func
link_entry_SENDBLOCK:
    push {r4, r5, r6, r7, lr}
    ldr r6, =link_ram
    lsls r5, r2, #16
    lsrs r5, r5, #16                 @ r5 = the size
    adds r4, r1, #0                  @ r4 = the source
    movs r0, #1
    lsls r0, r0, #8                  @ BLOCK_BUFFER_SIZE
    cmp r5, r0
    bhi 8f
    @ task 4: a controller COMMAND (B_COMM_TO_CONTROLLER, the first byte of a battle buffer block) the PARTNER's own game makes
    @ is swallowed: only the master commands a controller, and the only such command the partner's game ever makes is the
    @ battle-data request a medicine's effect sends its own Pokemon's controller from the bag; it would wait for the controller
    @ the bag is still running (its IS_DONE is behind it in the receive ring) and stop the battle on both sides. The engine's
    @ replay of the item on the master (link_entry_USEITEM) asks again, properly.
    ldr r0, =link_ram
    ldrb r0, [r0, #link_myid]
    cmp r0, #0
    beq 5f
    ldr r0, =COOP_EM_gLinkBattleSendBuffer
    ldr r0, [r0]
    cmp r4, r0
    blo 5f
    ldr r1, =COOP_EM_BATTLE_BUFFER_LINK_SIZE
    adds r0, r0, r1
    cmp r4, r0
    bhs 5f
    ldrb r0, [r4]
    cmp r0, #0
    bne 5f
    ldr r1, =link_rw                 @ counted
    ldrb r2, [r1, #link_rw_swallowed]
    adds r2, #1
    strb r2, [r1, #link_rw_swallowed]
    movs r0, #1                      @ taken (never sent)
    b 9f
5:
    ldrb r0, [r6, #link_head]
    adds r7, r0, #1
    movs r1, #15
    ands r7, r1                      @ r7 = the next head
    ldrb r1, [r6, #link_tail]
    cmp r7, r1
    beq 8f                           @ the outbox is full
    ldr r1, =link_esize
    muls r0, r1                      @ head * entry size
    adds r0, r0, r6
    adds r0, #link_out
    str r4, [r0]
    strh r5, [r0, #4]
    adds r0, #8
    adds r1, r4, #0
    adds r2, r5, #0
    bl link_copy                     @ the block into the outbox entry
    ldrb r0, [r6, #link_myid]
    lsls r0, r0, #8                  @ * BLOCK_BUFFER_SIZE
    ldr r1, =COOP_EM_gBlockRecvBuffer
    adds r0, r0, r1
    adds r1, r4, #0
    adds r2, r5, #0
    bl link_copy                     @ and into the own slot, as the cable's own copy of what it sends
    ldrb r0, [r6, #link_myid]
    ldr r1, =COOP_EM_gBlockReceivedStatus
    ldrb r2, [r1, r0]
    cmp r2, #0
    beq 1f
    bl link_note_overrun             @ the own slot hadn't been read yet: counted
1:  movs r2, #1
    strb r2, [r1, r0]
    strb r7, [r6, #link_head]        @ the entry is the app's now
    movs r0, #1
    b 9f
8:  bl link_note_overrun
    movs r0, #0
9:  pop {r4, r5, r6, r7}
    pop {r3}
    bx r3
    .ltorg

@ r0 = dst, r1 = src, r2 = bytes: copies them (r0-r2 end consumed), r3 clobbered.
    .thumb_func
link_copy:
    cmp r2, #0
    beq 2f
1:  ldrb r3, [r1]
    strb r3, [r0]
    adds r0, #1
    adds r1, #1
    subs r2, #1
    bne 1b
2:  bx lr

@ One more in the bridge's overrun count (a send it had to refuse, or an own slot overwritten before the game read it). r2, r3 only.
    .thumb_func
link_note_overrun:
    ldr r3, =link_ram
    ldrh r2, [r3, #link_overrun]
    adds r2, #1
    strh r2, [r3, #link_overrun]
    bx lr
    .ltorg

@ IsLinkTaskFinished(): 1 while the outbox has room and the own slot has been read.
    .global link_entry_ISLINKTASKFINISHED
    .thumb_func
link_entry_ISLINKTASKFINISHED:
    ldr r3, =link_ram
    ldrb r0, [r3, #link_head]
    adds r0, #1
    movs r1, #15
    ands r0, r1
    ldrb r2, [r3, #link_tail]
    cmp r0, r2
    beq 1f
    ldrb r0, [r3, #link_myid]
    ldr r1, =COOP_EM_gBlockReceivedStatus
    ldrb r0, [r1, r0]
    cmp r0, #0
    bne 1f
    movs r0, #1
    bx lr
1:  movs r0, #0
    bx lr
    .ltorg

@ GetMultiplayerId(): the bridge's own id.
    .global link_entry_GETMULTIPLAYERID
    .thumb_func
link_entry_GETMULTIPLAYERID:
    ldr r0, =link_ram
    ldrb r0, [r0, #link_myid]
    bx lr
    .ltorg

@ HandleLinkConnection(): 0 = nothing to wait for, the game's callbacks run.
    .global link_entry_HANDLELINKCONNECTION
    .thumb_func
link_entry_HANDLELINKCONNECTION:
    movs r0, #0
    bx lr

@ OpenLink(): the link is open at once (a battle's setup opens it again after the party showcase closed it): the game sees its remote
@ players received, and the link players and status from pk_coop_link_begin are still there.
    .global link_entry_OPENLINK
    .thumb_func
link_entry_OPENLINK:
    ldr r0, =COOP_EM_gReceivedRemoteLinkPlayers
    movs r1, #1
    strb r1, [r0]
    bx lr
    .ltorg

@ HasLinkErrorOccurred(): never.
    .global link_entry_HASLINKERROROCCURRED
    .thumb_func
link_entry_HASLINKERROROCCURRED:
    movs r0, #0
    bx lr

@ CloseLink() and SetCloseLinkCallback() (the cable's "everyone is ready, close the link", which a battle does after the multi
@ battle's party showcase and again when it ends): the game's remote players are gone at once. Closing while the battle itself
@ runs (BATTLE_TYPE_LINK_IN_BATTLE) is the link battle's end: `ended`.
    .global link_entry_CLOSELINK
    .thumb_func
link_entry_CLOSELINK:
    ldr r0, =COOP_EM_gReceivedRemoteLinkPlayers
    movs r1, #0
    strb r1, [r0]
    ldr r0, =COOP_EM_gBattleTypeFlags
    ldrb r1, [r0]
    movs r2, #0x20                   @ BATTLE_TYPE_LINK_IN_BATTLE
    tst r1, r2
    beq 1f
    ldr r0, =link_ram
    movs r1, #1
    strb r1, [r0, #link_ended]
1:  bx lr
    .ltorg

@ ---- task 2: a joined trainer battle shows the real route trainers ---------------------------------------------------------
@ The tower flow names, draws and words its two AI trainers through the Frontier's own tables, by gTrainerBattleOpponent_A / _B;
@ a route trainer's id (300 and up, gTrainers') would index them with a junk number. For the length of a link battle these
@ functions answer from gTrainers instead (the ids in A / B are the real ones; the partner's game gets the same two).

@ r1 = a trainer id (any upper halfword): r0 = &gTrainers[id], or 0 when the id is past the table. r1, r2 clobbered.
    .thumb_func
link_trainer_rec:
    lsls r1, r1, #16
    lsrs r1, r1, #16
    ldr r2, =COOP_EM_TRAINERS_COUNT
    cmp r1, r2
    bcs 1f
    ldr r2, =COOP_EM_OFF_TRAINER_SIZE
    muls r1, r2
    ldr r0, =COOP_EM_gTrainers
    adds r0, r0, r1
    bx lr
1:  movs r0, #0
    bx lr
    .ltorg

@ GetFrontierTrainerName(u8 *dst, u16 id): the trainer's name, EOS-terminated. Into a gLinkPlayers name (8 bytes) it is cut to 7
@ letters; anywhere else (the battle's text buffers) it is whole (at most 11).
    .global link_entry_GETFRONTIERTRAINERNAME
    .thumb_func
link_entry_GETFRONTIERTRAINERNAME:
    push {r4, lr}
    adds r4, r0, #0                  @ r4 = dst
    bl link_trainer_rec
    movs r3, #0                      @ the copy's index
    cmp r0, #0
    beq 3f
    adds r0, #COOP_EM_OFF_TRAINER_NAME
    adds r1, r0, #0                  @ r1 = the trainer's name
    movs r2, #11
    ldr r3, =COOP_EM_gLinkPlayers
    subs r3, r4, r3
    cmp r3, #(4 * COOP_EM_OFF_LINK_PLAYER_SIZE)
    bcs 1f
    movs r2, #7                      @ dst is inside gLinkPlayers: PLAYER_NAME_LENGTH
1:  movs r3, #0
2:  cmp r3, r2
    bcs 3f
    ldrb r0, [r1, r3]
    cmp r0, #0xFF
    beq 3f
    strb r0, [r4, r3]
    adds r3, #1
    b 2b
3:  movs r0, #0xFF
    strb r0, [r4, r3]
    pop {r4}
    pop {r3}
    bx r3
    .ltorg

@ GetFrontierTrainerFrontSpriteId(u16 id): the trainer's picture index (gTrainers' own).
    .global link_entry_GETFRONTIERTRAINERFRONTSPRITEID
    .thumb_func
link_entry_GETFRONTIERTRAINERFRONTSPRITEID:
    push {lr}
    adds r1, r0, #0
    bl link_trainer_rec
    cmp r0, #0
    beq 1f
    ldrb r0, [r0, #COOP_EM_OFF_TRAINER_PIC]
1:  pop {r3}
    bx r3

@ GetFrontierOpponentClass(u16 id): the trainer's class (gTrainers' own).
    .global link_entry_GETFRONTIEROPPONENTCLASS
    .thumb_func
link_entry_GETFRONTIEROPPONENTCLASS:
    push {lr}
    adds r1, r0, #0
    bl link_trainer_rec
    cmp r0, #0
    beq 1f
    ldrb r0, [r0, #COOP_EM_OFF_TRAINER_CLASS]
1:  pop {r3}
    bx r3

@ GetBattleTowerTrainerLanguage(u8 *dst, u16 id): English, as the game's own trainers.
    .global link_entry_GETBATTLETOWERTRAINERLANGUAGE
    .thumb_func
link_entry_GETBATTLETOWERTRAINERLANGUAGE:
    movs r1, #COOP_EM_LANGUAGE_ENGLISH
    strb r1, [r0]
    bx lr

@ CopyFrontierTrainerText(u8 which, u16 id): the trainers' speeches live in the host's map script only (the partner's game has
@ none of them), so both games print an empty one: gStringVar4 is "" (the caller reads it from there).
    .global link_entry_COPYFRONTIERTRAINERTEXT
    .thumb_func
link_entry_COPYFRONTIERTRAINERTEXT:
    ldr r0, =COOP_EM_gStringVar4
    movs r1, #0xFF
    strb r1, [r0]
    bx lr
    .ltorg

@ TrySetLinkBattleTowerEnemyPartyLevel(): the Frontier's "foes are Lv 50" rule: nothing, the trainers keep their own levels.
    .global link_entry_TRYSETLINKBATTLETOWERENEMYPARTYLEVEL
    .thumb_func
link_entry_TRYSETLINKBATTLETOWERENEMYPARTYLEVEL:
    bx lr
@ ---- task 3: a joined wild battle is a link battle without trainers ----
@ FindLinkBattleMaster(u8 numPlayers, u8 multiPlayerId): the game's own picks the link master and ORs BATTLE_TYPE_TRAINER into the
@ flags for every link battle, which would make a wild battle's intro / sprites / AI / escape a trainer battle's. Both players run
@ the same version here, so this is the game's answer for that case (player 0 is the master) with the trainer bit left off for
@ the wild kind (link_kind 1).
    .set link_kind, 3
    .global link_kind
    .global link_entry_FINDLINKBATTLEMASTER
    .thumb_func
link_entry_FINDLINKBATTLEMASTER:
    ldr r2, =COOP_EM_gBattleTypeFlags
    ldr r3, [r2]
    cmp r1, #0
    bne 1f
    movs r0, #COOP_EM_BATTLE_TYPE_IS_MASTER
    orrs r3, r0
1:  ldr r0, =link_ram
    ldrb r0, [r0, #link_kind]
    cmp r0, #1                       @ PK_COOP_LINK_WILD
    beq 2f
    movs r0, #COOP_EM_BATTLE_TYPE_TRAINER
    orrs r3, r0
2:  str r3, [r2]
    bx lr
    .ltorg

@ The VS screen's task, for a wild battle: no screen, the task is gone at once (DestroyTask(taskId), taskId already in r0).
    .global link_entry_WILD_NOVS
    .thumb_func
link_entry_WILD_NOVS:
    ldr r1, =COOP_EM_DestroyTask
    adds r1, #1
    bx r1
    .ltorg

@ gBattleMainFunc while a wild link battle's escape (or a wild foe's flight) ends: the script's `end2` ends only this game's
@ battle, so the first time the battle is finished (gCurrentActionFuncId TRY_FINISH / FINISHED) the master sends the end to the
@ other player as a link battle's win / loss script does (Cmd_endlinkbattle: the controller of the battler at PLAYER_LEFT, which
@ the partner's game mirrors); then HandleEndTurn_FinishBattle itself, as before. link_ram + 0x3F is the "sent" byte.
    .set link_ransent, 0x3F
    .global link_ransent
    .global link_entry_RAN_FINISH
    .thumb_func
link_entry_RAN_FINISH:
    push {lr}
    ldr r0, =COOP_EM_gCurrentActionFuncId
    ldrb r0, [r0]
    cmp r0, #COOP_EM_B_ACTION_TRY_FINISH
    beq 1f
    cmp r0, #COOP_EM_B_ACTION_FINISHED
    bne 2f
1:  ldr r1, =link_ram
    adds r1, #link_ransent
    ldrb r0, [r1]
    cmp r0, #0
    bne 2f
    movs r0, #1
    strb r0, [r1]
    ldr r3, =COOP_EM_Cmd_endlinkbattle
    adds r3, #1
    bl link_callr3
2:  ldr r3, =COOP_EM_HandleEndTurn_FinishBattle
    adds r3, #1
    bl link_callr3
    pop {r0}
    bx r0
link_callr3:
    bx r3
    .ltorg
@ ---- task 6: story battles (pk_coop_link_story.c) ----------------------------------------------------------------------------
@ While a story join is armed, BattleSetup_StartTrainerBattle (the `dotrainerbattle` script command's function) has its head
@ patched to link_entry_STORYSTART. The story RAM is at link_story_ram (outside the bridge's area: begin zeroes that):
@   +0 state   0 idle, 1 armed, 2 picking, 3 ready (a team was picked), 4 cancelled (the pick screen was backed out of)
@   +1 result  gSpecialVar_Result after the pick screen   +2..+4 the picked party slots (1-based, gSelectedOrderFromParty)
@   +8 resume  the script address the trainer's own script went on at
@   +0x12 the pick script: special ChooseHalfPartyForBattle; waitstate; dotrainerbattle; goto <resume> (the goto's operand, aligned, is +0x18)
@ 1st entry (armed): the game's own script goes on at the pick script: ctx->scriptPtr = it. The script command returns TRUE, so the
@ pick script starts next frame. 2nd entry (the pick script's own dotrainerbattle): the pick is read and ScriptContext_Stop holds the
@ script: the field is idle until the app starts the link battle (or cancels).
    .set link_story_ram, 0x0203FA00
    .set link_story_state, 0
    .set link_story_result, 1
    .set link_story_picks, 2
    .set link_story_resume, 8
    .set link_story_expect, 6        @ u16: the one trainer (gTrainers id) the hook takes; any other dotrainerbattle runs the game's own
    .set link_story_script, 0x12
    .set link_story_operand, 0x18
    .global link_story_ram, link_story_state, link_story_result, link_story_picks, link_story_expect, link_story_resume, link_story_script
    .global link_story_operand

    .global link_entry_STORYSTART
    .thumb_func
link_entry_STORYSTART:
    push {r4, lr}
    ldr r4, =link_story_ram
    ldrb r0, [r4, #link_story_state]
    cmp r0, #1
    beq 1f
    cmp r0, #2
    beq 2f
9:  pop {r4}
    pop {r3}
    bx r3
1:  ldr r1, =COOP_EM_gTrainerBattleOpponent_A
    ldrh r1, [r1]
    ldrh r2, [r4, #link_story_expect]
    cmp r1, r2
    bne 4f
    ldr r1, =COOP_EM_sGlobalScriptContext
    ldr r2, =COOP_EM_OFF_SCRIPT_CONTEXT_SCRIPT_PTR
    adds r1, r1, r2
    ldr r0, [r1]
    str r0, [r4, #link_story_resume]
    str r0, [r4, #link_story_operand]
    movs r0, #link_story_script
    adds r0, r0, r4
    str r0, [r1]
    movs r0, #2
    strb r0, [r4, #link_story_state]
    b 9b
4:  @ another trainer's battle: state 5 (mismatch: pk_coop_story_state takes the hook out), then the game's own function, whose first 8
    @ bytes (push {r4, lr}; ldr r0, =gNoOfApproachingTrainers; ldrb r0, [r0]; cmp r0, #2) are done here - our push is the same one
    movs r0, #5
    strb r0, [r4, #link_story_state]
    ldr r0, =COOP_EM_gNoOfApproachingTrainers
    ldrb r0, [r0]
    cmp r0, #2
    ldr r3, =(COOP_EM_BattleSetup_StartTrainerBattle + 9)
    bx r3
2:  ldr r1, =COOP_EM_gSpecialVar_Result
    ldrh r2, [r1]
    strb r2, [r4, #link_story_result]
    ldr r1, =COOP_EM_gSelectedOrderFromParty
    ldrb r0, [r1, #0]
    strb r0, [r4, #link_story_picks]
    ldrb r0, [r1, #1]
    strb r0, [r4, #link_story_picks + 1]
    ldrb r0, [r1, #2]
    strb r0, [r4, #link_story_picks + 2]
    movs r0, #3
    cmp r2, #0
    bne 3f
    movs r0, #4
3:  strb r0, [r4, #link_story_state]
    ldr r3, =COOP_EM_ScriptContext_Stop
    adds r3, #1
    bl link_story_callr3
    b 9b
    .ltorg

    .thumb_func
link_story_callr3:
    bx r3
@ ---------------------------------------------------------------------------------------------------------------------
@ task 4: EXP, the BAG and catching in a joined link battle (app/src/main/cpp/pk_coop_link_rewards.c puts these in for the
@ battle: the table pointers of Cmd_getexp / Cmd_handlelearnnewmove / HandleAction_UseItem point at the first three, the game
@ functions the last four replace get a head patch like the block layer's). The rewards' RAM (struct pk_coop_link_rewards,
@ pk_coop.h) is at link_rw.
    .set link_rw, 0x0203D000 + 0x1400
    .set link_rw_exp_host, 0
    .set link_rw_exp_partner, 2
    .set link_rw_share_host, 4
    .set link_rw_share_partner, 6
    .set link_rw_caught_by, 8
    .set link_rw_caught, 0x10
    .set link_rw_swallowed, 10
    .set link_rw_boxframes, 11
    .set link_rw_thrower, 12
    .global link_rw_thrower
    .set link_backup_count, 18
    .global link_rw, link_rw_exp_host, link_rw_exp_partner, link_rw_share_host, link_rw_share_partner, link_rw_caught_by
    .global link_rw_caught, link_rw_swallowed, link_rw_boxframes, link_backup_count

@ A call into the game: `bl` sets lr (Thumb bit included), `bx rN` goes (our code is 14 MB from the game's: out of BL's reach).
    .thumb_func
link_call_r3:
    bx r3
    .thumb_func
link_call_r5:
    bx r5
    .thumb_func
link_call_r7:
    bx r7

@ --- EXP ------------------------------------------------------------------------------------------------------------------
@ Cmd_getexp's table pointer (a script command, called every frame until it has finished). Every Pokemon of a side gets a single
@ battle's full EXP: Cmd_getexp divides by every Pokemon sent in (slots 0-5), but the host's (0-2) and the partner's (3-5) are two
@ parties fighting two foes together, so each side divides by its own. Around the game's own Cmd_getexp:
@  state 1 (the shares are worked out and case 2 would use them at once): the controllers' exec flags are faked non-zero for the
@     call, so the game works the totals out and waits; then both sides' shares are worked out here (link_exp_sides);
@  state 2 (a Pokemon gets its EXP): the shares of its side go where the game reads them (link_exp_side_set). A Pokemon of the
@     partner's side that is not in battle has no battler: the game would send its EXP update to battler 0 (the host's controller,
@     which holds no copy of it), so battler 0 is marked absent for the call and it goes to battler 2;
@  state 3 (the EXP update goes to the controller): the controller of the partner's game gives its own copy the EXP, this
@     game's copy of a partner Pokemon (a stale one that came in a block) gets it too (link_exp_mirror), so the level-up text,
@     the level and the stats the engine reads are right.
    .align 1
    .global link_entry_GETEXP
    .thumb_func
link_entry_GETEXP:
    push {r4, r5, r6, r7, lr}
    sub sp, #8
    movs r6, #0                             @ bit 0: exec flags faked (saved at [sp]), bit 1: battler 0 absent (saved at [sp, #4])
    ldr r4, =COOP_EM_gBattleScripting
    ldrb r5, [r4, #COOP_EM_OFF_BATTLE_SCRIPTING_GETEXP_STATE]
    cmp r5, #1
    bne 1f
    ldr r0, =COOP_EM_gBattleControllerExecFlags
    ldr r1, [r0]
    str r1, [sp]
    movs r1, #0
    mvns r1, r1
    str r1, [r0]
    movs r6, #1
    b 3f
1:  cmp r5, #2
    bne 3f
    bl link_exp_side_set                    @ r0 = the Pokemon's party slot
    cmp r0, #3
    blt 3f
    ldr r1, =COOP_EM_gAbsentBattlerFlags
    ldrb r2, [r1]
    str r2, [sp, #4]
    movs r3, #1
    orrs r2, r3
    strb r2, [r1]
    movs r6, #2
3:  ldr r3, =COOP_EM_Cmd_getexp + 1
    bl link_call_r3
    cmp r6, #1
    bne 4f
    ldr r0, =COOP_EM_gBattleControllerExecFlags
    ldr r1, [sp]
    str r1, [r0]
    b 6f
4:  cmp r6, #2
    bne 6f
    ldr r1, =COOP_EM_gAbsentBattlerFlags
    ldr r2, [sp, #4]
    strb r2, [r1]
6:  cmp r5, #1
    bne 7f
    bl link_exp_sides
    b 9f
7:  cmp r5, #3
    bne 9f
    ldrb r0, [r4, #COOP_EM_OFF_BATTLE_SCRIPTING_GETEXP_STATE]
    cmp r0, #4
    bne 9f                                  @ the update wasn't sent this frame (a controller still busy)
    bl link_exp_mirror
9:  add sp, #8
    pop {r4, r5, r6, r7}
    pop {r0}
    bx r0
    .ltorg

@ *exp (gBattleStruct->expValue) and gExpShareExp for the side of the Pokemon whose turn it is; r0 = its party slot.
    .align 1
    .thumb_func
link_exp_side_set:
    ldr r0, =COOP_EM_gBattleStruct
    ldr r0, [r0]
    ldrb r1, [r0, #COOP_EM_OFF_BATTLE_STRUCT_EXP_GETTER_MON_ID]
    adds r0, #COOP_EM_OFF_BATTLE_STRUCT_EXP_VALUE
    ldr r2, =link_rw
    cmp r1, #3
    blt 1f
    ldrh r3, [r2, #link_rw_exp_partner]
    strh r3, [r0]
    ldrh r3, [r2, #link_rw_share_partner]
    b 2f
1:  ldrh r3, [r2, #link_rw_exp_host]
    strh r3, [r0]
    ldrh r3, [r2, #link_rw_share_host]
2:  ldr r0, =COOP_EM_gExpShareExp
    strh r3, [r0]
    movs r0, r1
    bx lr
    .ltorg

@ Both sides' shares, from the fainted foe's yield: counts the sent-in (bit i of gBattleStruct->sentInPokes) and the Exp. Share
@ holders among the living party Pokemon of each side - as Cmd_getexp's case 1 does for all six - then each side's EXP and Exp.
@ Share EXP into the rewards' RAM; then the Pokemon at slot 0's side's go where the game reads them.
@ Counts, a byte each in r6: sent-in host (0-7), sent-in partner (8-15), Exp. Share host (16-23), partner (24-31).
    .align 1
    .thumb_func
link_exp_sides:
    push {r4, r5, r6, r7, lr}
    ldr r0, =COOP_EM_gBattleStruct
    ldr r0, [r0]
    adds r0, #COOP_EM_OFF_BATTLE_STRUCT_SENT_IN_POKES
    ldrb r5, [r0]                           @ sentIn
    movs r6, #0
    movs r4, #0                             @ the party slot
1:  movs r7, #COOP_EM_OFF_MON_SIZE
    muls r7, r4
    ldr r0, =COOP_EM_gPlayerParty
    adds r7, r7, r0                         @ &gPlayerParty[slot]
    movs r0, r7
    movs r1, #COOP_EM_MON_DATA_SPECIES
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    cmp r0, #0
    beq 5f
    movs r0, r7
    movs r1, #COOP_EM_MON_DATA_HP
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    cmp r0, #0
    beq 5f
    movs r0, #1
    lsls r0, r4
    ands r0, r5
    beq 2f
    movs r2, #0                             @ the sent-in count's byte: 0 (slots 0-2) or 8 (slots 3-5)
    cmp r4, #3
    blt 11f
    movs r2, #8
11: movs r1, #1
    lsls r1, r2
    adds r6, r6, r1
2:  movs r0, r7
    movs r1, #COOP_EM_MON_DATA_HELD_ITEM
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    cmp r0, #COOP_EM_ITEM_ENIGMA_BERRY
    bne 3f
    ldr r0, =COOP_EM_gSaveBlock1Ptr         @ the Enigma Berry's hold effect is the save's own
    ldr r0, [r0]
    ldr r1, =COOP_EM_OFF_SAVEBLOCK1_ENIGMA_HOLD_EFFECT
    ldrb r0, [r0, r1]
    b 4f
3:  ldr r3, =COOP_EM_GetItemHoldEffect + 1
    bl link_call_r3
    lsls r0, r0, #24
    lsrs r0, r0, #24
4:  cmp r0, #COOP_EM_HOLD_EFFECT_EXP_SHARE
    bne 5f
    movs r2, #16
    cmp r4, #3
    blt 12f
    movs r2, #24
12: movs r1, #1
    lsls r1, r2
    adds r6, r6, r1
5:  adds r4, r4, #1
    cmp r4, #6
    blt 1b
    @ calculatedExp = gSpeciesInfo[gBattleMons[gBattlerFainted].species].expYield * level / 7 (agbcc's u16): x / 7 is
    @ x * 37450 >> 18, exact for the 25500 at most
    ldr r0, =COOP_EM_gBattlerFainted
    ldrb r0, [r0]
    movs r1, #COOP_EM_OFF_BATTLE_MON_SIZE
    muls r0, r1
    ldr r1, =COOP_EM_gBattleMons
    adds r1, r1, r0
    ldrh r2, [r1, #COOP_EM_OFF_BATTLE_MON_SPECIES]
    adds r1, #COOP_EM_OFF_BATTLE_MON_LEVEL
    ldrb r1, [r1]
    movs r0, #COOP_EM_OFF_SPECIES_INFO_SIZE
    muls r0, r2
    ldr r3, =COOP_EM_gSpeciesInfo
    adds r0, r0, r3
    ldrb r0, [r0, #COOP_EM_OFF_SPECIES_INFO_EXP_YIELD]
    muls r0, r1
    ldr r1, =37450
    muls r0, r1
    lsrs r7, r0, #18                        @ r7 = calculatedExp
    ldr r5, =link_rw
    movs r0, r7                             @ the host's side
    lsls r1, r6, #24
    lsrs r1, r1, #24
    lsls r2, r6, #8
    lsrs r2, r2, #24
    bl link_side_exp
    strh r0, [r5, #link_rw_exp_host]
    strh r1, [r5, #link_rw_share_host]
    movs r0, r7                             @ the partner's
    lsls r1, r6, #16
    lsrs r1, r1, #24
    lsrs r2, r6, #24
    bl link_side_exp
    strh r0, [r5, #link_rw_exp_partner]
    strh r1, [r5, #link_rw_share_partner]
    bl link_exp_side_set                    @ the Pokemon the game starts with (slot 0, the host's side)
    pop {r4, r5, r6, r7, pc}
    .ltorg

@ r0 = calculatedExp, r1 = this side's sent-in count, r2 = its Exp. Share holders -> r0 = *exp, r1 = gExpShareExp, as
@ Cmd_getexp's case 1 (SAFE_DIV, at least 1) for one side: with Exp. Share holders half goes to the sent-in, half to them.
    .align 1
    .thumb_func
link_side_exp:
    push {r4, r5, lr}
    movs r4, r1
    movs r5, r2
    cmp r5, #0
    beq 1f
    lsrs r0, r0, #1
1:  movs r3, r0
    movs r1, r4
    bl link_div_small
    cmp r0, #0
    bne 2f
    movs r0, #1
2:  movs r4, r0                             @ *exp
    movs r0, r3
    movs r1, r5
    bl link_div_small                       @ the Exp. Share EXP (0 with no holders)
    cmp r5, #0
    beq 3f
    cmp r0, #0
    bne 3f
    movs r0, #1
3:  movs r1, r0
    movs r0, r4
    pop {r4, r5, pc}

@ r0 / r1 for r1 = 0 (-> 0, SAFE_DIV), 1, 2, 3 and r0 <= 25500: r0, r1 and r2 change.
    .align 1
    .thumb_func
link_div_small:
    cmp r1, #0
    bne 1f
    movs r0, #0
    bx lr
1:  cmp r1, #1
    bne 2f
    bx lr
2:  cmp r1, #2
    bne 3f
    lsrs r0, r0, #1
    bx lr
3:  ldr r2, =43691                          @ x / 3 = x * 43691 >> 17
    muls r0, r2
    lsrs r0, r0, #17
    bx lr
    .ltorg

@ The EXP update the game just sent to the partner's controller, applied to this game's copy of its Pokemon as that controller
@ does to its own (Task_GiveExpToMon): to the next level's EXP and no further (the leftover comes back as the controller's reply,
@ which the game's case 4 hands to case 3 again), CalculateMonStats then makes the level and the stats. Slots 3-5 only.
    .align 1
    .thumb_func
link_exp_mirror:
    push {r4, r5, r6, lr}
    sub sp, #8
    ldr r0, =COOP_EM_gBattleStruct
    ldr r0, [r0]
    ldrb r1, [r0, #COOP_EM_OFF_BATTLE_STRUCT_EXP_GETTER_MON_ID]
    cmp r1, #3
    blt 9f
    movs r4, #COOP_EM_OFF_MON_SIZE
    muls r4, r1
    ldr r0, =COOP_EM_gPlayerParty
    adds r4, r4, r0                         @ r4 = &gPlayerParty[getter]
    movs r0, r4
    movs r1, #COOP_EM_MON_DATA_HP
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    cmp r0, #0
    beq 9f
    movs r0, r4
    movs r1, #COOP_EM_MON_DATA_LEVEL
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    lsls r5, r0, #24
    lsrs r5, r5, #24                        @ r5 = level
    cmp r5, #COOP_EM_MAX_LEVEL
    bge 9f
    movs r0, r4
    movs r1, #COOP_EM_MON_DATA_EXP
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    movs r6, r0                             @ r6 = its EXP
    movs r0, r4
    movs r1, #COOP_EM_MON_DATA_SPECIES
    ldr r3, =COOP_EM_GetMonData + 1
    bl link_call_r3
    movs r1, #COOP_EM_OFF_SPECIES_INFO_SIZE
    muls r0, r1
    ldr r1, =COOP_EM_gSpeciesInfo
    adds r0, r0, r1
    ldrb r0, [r0, #COOP_EM_OFF_SPECIES_INFO_GROWTH_RATE]
    movs r1, #COOP_EM_MAX_LEVEL + 1
    muls r0, r1
    adds r0, r0, r5
    adds r0, #1
    lsls r0, r0, #2
    ldr r1, =COOP_EM_gExperienceTables
    ldr r0, [r1, r0]                        @ the next level's EXP
    ldr r1, =COOP_EM_gBattleMoveDamage
    ldrh r1, [r1]                           @ the EXP given
    adds r6, r6, r1
    cmp r6, r0
    blo 1f
    movs r6, r0
1:  str r6, [sp]
    movs r0, r4
    movs r1, #COOP_EM_MON_DATA_EXP
    mov r2, sp
    ldr r3, =COOP_EM_SetMonData + 1
    bl link_call_r3
    movs r0, r4
    ldr r3, =COOP_EM_CalculateMonStats + 1
    bl link_call_r3
9:  add sp, #8
    pop {r4, r5, r6, pc}
    .ltorg

@ Cmd_handlelearnnewmove's table pointer: a Pokemon of the partner's side learns its moves in its own game, after the battle (this
@ game's copy of it isn't the one that counts): nothing to learn, the script's own `return` (nothingToLearnPtr, bytes 5-8).
    .align 1
    .global link_entry_LEARN
    .thumb_func
link_entry_LEARN:
    ldr r0, =COOP_EM_gBattleStruct
    ldr r0, [r0]
    ldrb r0, [r0, #COOP_EM_OFF_BATTLE_STRUCT_EXP_GETTER_MON_ID]
    cmp r0, #3
    blt 1f
    ldr r2, =COOP_EM_gBattlescriptCurrInstr
    ldr r0, [r2]
    ldrb r1, [r0, #5]
    ldrb r3, [r0, #6]
    lsls r3, r3, #8
    orrs r1, r3
    ldrb r3, [r0, #7]
    lsls r3, r3, #16
    orrs r1, r3
    ldrb r3, [r0, #8]
    lsls r3, r3, #24
    orrs r1, r3
    str r1, [r2]
    bx lr
1:  ldr r3, =COOP_EM_Cmd_handlelearnnewmove + 1
    bx r3
    .ltorg

@ --- the BAG ----------------------------------------------------------------------------------------------------------------
@ CompleteWhenChoseItem (replaced whole): the player's controller answers the engine with the item the bag chose. The master's
@ own is the game's answer; the partner's goes as TWORETURNVALUES, the item in the first byte and the low byte of the second and,
@ when the item is a medicine, the party slot it was used on in the high byte of the second (the engine reads bytes 1-2 as the
@ item either way): the master's engine applies the item's effect to its own copy of that Pokemon (link_entry_USEITEM).
    .align 1
    .global link_entry_CHOSE_ITEM
    .thumb_func
link_entry_CHOSE_ITEM:
    push {r4, lr}
    ldr r0, =COOP_EM_gMain
    ldr r1, [r0, #COOP_EM_OFF_MAIN_CALLBACK2]
    ldr r0, =COOP_EM_BattleMainCB2 + 1
    cmp r1, r0
    bne 9f
    ldr r0, =COOP_EM_gPaletteFade           @ && !gPaletteFade.active (bit 15 of the halfword at +6)
    ldrb r1, [r0, #7]
    movs r0, #0x80
    tst r1, r0
    bne 9f
    ldr r0, =COOP_EM_gSpecialVar_ItemId
    ldrh r4, [r0]                           @ r4 = the item
    ldr r0, =link_ram
    ldrb r0, [r0, #link_myid]
    cmp r0, #0
    bne 2f
    movs r0, #1                             @ B_COMM_TO_ENGINE
    movs r1, r4
    ldr r3, =COOP_EM_BtlController_EmitOneReturnValue + 1
    bl link_call_r3
    b 8f
2:  movs r2, #0                             @ the slot byte: 0 unless the item is a medicine
    cmp r4, #0
    beq 3f
    ldr r0, =COOP_EM_gItems
    movs r1, #COOP_EM_OFF_ITEM_SIZE
    muls r1, r4
    adds r0, r0, r1
    ldr r0, [r0, #COOP_EM_OFF_ITEM_BATTLE_USE_FUNC]
    ldr r1, =COOP_EM_ItemUseInBattle_Medicine + 1
    cmp r0, r1
    bne 3f
    ldr r0, =COOP_EM_gPartyMenu             @ the party menu's slot (the menu lists the player's own Pokemon first), as the party
    ldrb r0, [r0, #COOP_EM_OFF_PARTY_MENU_SLOT_ID]   @ id the battle's two games share (the host's three 0-2, the partner's 3-5)
    ldr r3, =COOP_EM_GetPartyIdFromBattleSlot + 1
    bl link_call_r3
    lsls r2, r0, #24
    lsrs r2, r2, #24
3:  lsls r2, r2, #8
    lsrs r1, r4, #8
    orrs r2, r1                             @ ret16 = item high byte | slot << 8
    movs r1, #0xFF
    ands r1, r4                             @ ret8 = item low byte
    movs r0, #1
    ldr r3, =COOP_EM_BtlController_EmitTwoReturnValues + 1
    bl link_call_r3
8:  ldr r3, =COOP_EM_PlayerBufferExecCompleted + 1
    bl link_call_r3
9:  pop {r4}
    pop {r0}
    bx r0
    .ltorg

@ sTurnActionsFuncsTable[B_ACTION_USE_ITEM]: the game's HandleAction_UseItem, then, for the partner's battler (2) using a medicine
@ or an X item, its effect on this (the master's) copy of the Pokemon, as the bag's own does it on the partner's game: through
@ ExecuteTableBasedItemEffect with gBattlerInMenuId = 2, which also updates gBattleMons[2] and asks the partner's controller for
@ the Pokemon's data. The party slot comes from byte 3 of the controller's answer (a medicine; slots 3-5 only: never one of the
@ host's) or is the battler's own (an X item). The item came out of the partner's own bag in its own game.
    .align 1
    .global link_entry_USEITEM
    .thumb_func
link_entry_USEITEM:
    push {r4, r5, r6, r7, lr}
    ldr r3, =COOP_EM_HandleAction_UseItem + 1
    bl link_call_r3
    ldr r0, =COOP_EM_gBattlerAttacker
    ldrb r0, [r0]
    cmp r0, #2
    bne 9f
    ldr r0, =COOP_EM_gLastUsedItem
    ldrh r4, [r0]                           @ the item
    cmp r4, #COOP_EM_LAST_BALL
    bhi 5f
    bl link_ball_refused                    @ the master holds the ball rule too: a modified partner's ball with two foes up is
    cmp r0, #0                              @ cancelled (the script's action just ends)
    beq 9f
    ldr r1, =link_script_cancel
    ldr r2, =COOP_EM_gBattlescriptCurrInstr
    str r1, [r2]
    b 9f
5:  ldr r0, =COOP_EM_gItems
    movs r1, #COOP_EM_OFF_ITEM_SIZE
    muls r1, r4
    adds r0, r0, r1
    ldr r0, [r0, #COOP_EM_OFF_ITEM_BATTLE_USE_FUNC]
    ldr r1, =COOP_EM_ItemUseInBattle_Medicine + 1
    cmp r0, r1
    beq 1f
    ldr r1, =COOP_EM_ItemUseInBattle_StatIncrease + 1
    cmp r0, r1
    bne 9f
    ldr r0, =COOP_EM_gBattlerPartyIndexes
    ldrh r5, [r0, #4]
    b 2f
1:  ldr r0, =COOP_EM_gBattleBufferB + 2 * COOP_EM_OFF_BATTLE_BUFFER_B_STRIDE + 3
    ldrb r5, [r0]
    cmp r5, #3
    blo 9f
    cmp r5, #5
    bhi 9f
2:  sub sp, #8
    movs r0, #COOP_EM_OFF_MON_SIZE
    muls r0, r5
    ldr r1, =COOP_EM_gPlayerParty
    adds r0, r0, r1
    movs r6, r0                             @ r6 = the Pokemon (until gBattlerInMenuId's address replaces it below)
    ldr r0, =COOP_EM_gBattlerPartyIndexes   @ the Pokemon in battle: this copy of it is a stale one (the partner's game keeps
    ldrh r0, [r0, #4]                       @ the real, the engine its battle data): HP and status from the battle data first
    cmp r0, r5
    bne 3f
    movs r0, r6
    movs r1, #COOP_EM_MON_DATA_HP
    ldr r2, =COOP_EM_gBattleMons + 2 * COOP_EM_OFF_BATTLE_MON_SIZE + COOP_EM_OFF_BATTLE_MON_HP
    ldr r3, =COOP_EM_SetMonData + 1
    bl link_call_r3
    movs r0, r6
    movs r1, #COOP_EM_MON_DATA_STATUS
    ldr r2, =COOP_EM_gBattleMons + 2 * COOP_EM_OFF_BATTLE_MON_SIZE + COOP_EM_OFF_BATTLE_MON_STATUS1
    ldr r3, =COOP_EM_SetMonData + 1
    bl link_call_r3
3:  ldr r6, =COOP_EM_gBattlerInMenuId
    ldrb r0, [r6]
    str r0, [sp]
    movs r0, #2
    strb r0, [r6]
    ldr r0, =COOP_EM_gActiveBattler
    ldrb r0, [r0]
    str r0, [sp, #4]
    movs r0, #COOP_EM_OFF_MON_SIZE
    muls r0, r5
    ldr r1, =COOP_EM_gPlayerParty
    adds r0, r0, r1
    movs r1, r4
    movs r2, r5
    movs r3, #0
    ldr r7, =COOP_EM_ExecuteTableBasedItemEffect + 1
    bl link_call_r7
    ldr r1, [sp]
    strb r1, [r6]                           @ gBattlerInMenuId and gActiveBattler as they were
    ldr r1, [sp, #4]
    ldr r0, =COOP_EM_gActiveBattler
    strb r1, [r0]
    add sp, #8
9:  pop {r4, r5, r6, r7}
    pop {r0}
    bx r0
    .ltorg

@ --- catching -----------------------------------------------------------------------------------------------------------------
@ A ball: "There are two wild POKéMON!\nYou can't aim a BALL!" while two foes stand, or the one the ball would hit (the one in
@ front of the thrower: battler 1 for the host, 3 for the partner) isn't the one left. The foes' standing is read from the
@ parties (gEnemyParty[gBattlerPartyIndexes[1 / 3]]'s HP: the one thing both games keep up to date), so each game can say it for
@ its own player before the ball leaves the bag. Not a wild battle (a TRAINER one): the game's own answer, the ball is wasted.
@ r0 = 1 when the ball is refused. Clobbers r1-r3.
    .align 1
    .thumb_func
link_ball_refused:
    ldr r0, =COOP_EM_gBattleTypeFlags
    ldr r0, [r0]
    movs r1, #COOP_EM_BATTLE_TYPE_TRAINER
    tst r0, r1
    bne 8f
    ldr r2, =COOP_EM_gBattlerPartyIndexes
    ldr r3, =COOP_EM_gEnemyParty
    ldrh r0, [r2, #2]                       @ battler 1's party slot
    movs r1, #COOP_EM_OFF_MON_SIZE
    muls r0, r1
    adds r0, r0, r3
    adds r0, #COOP_EM_OFF_MON_HP
    ldrh r0, [r0]                           @ r0 = foe 1's HP
    ldrh r1, [r2, #6]                       @ battler 3's party slot
    movs r2, #COOP_EM_OFF_MON_SIZE
    muls r1, r2
    adds r1, r1, r3
    adds r1, #COOP_EM_OFF_MON_HP
    ldrh r1, [r1]                           @ r1 = foe 3's HP
    cmp r0, #0
    beq 1f
    cmp r1, #0
    bne 7f                                  @ both stand
1:                                          @ one foe left, whichever slot: link_entry_BALLTHROW aims the ball at it
8:  movs r0, #0
    bx lr
7:  movs r0, #1
    bx lr
    .ltorg

@ ItemUseInBattle_PokeBall (replaced whole; the bag calls it with r0 = its task id when USE is picked on a ball): the refusal above
@ as the bag's own message, else what the game's function does (battle pyramid apart): no room for it (party and every box full) =
@ "The BOX is full.", else the ball leaves the bag (the thrower's own) and the bag closes.
    .align 1
    .global link_entry_BALL
    .thumb_func
link_entry_BALL:
    push {r4, r5, lr}
    movs r4, r0                             @ the bag's task id
    bl link_ball_refused
    cmp r0, #0
    beq 1f
    movs r0, r4
    movs r1, #COOP_EM_FONT_NORMAL
    ldr r2, =link_ball_text
    ldr r3, =COOP_EM_CloseItemMessage + 1
    ldr r5, =COOP_EM_DisplayItemMessage + 1
    bl link_call_r5
    b 9f
1:  ldr r0, =link_ram                       @ the catcher's REAL party (the link party always holds six): room in it, else the boxes
    ldrb r0, [r0, #link_backup_count]
    cmp r0, #6
    blt 4f
    ldr r3, =COOP_EM_IsPlayerPartyAndPokemonStorageFull + 1
    bl link_call_r3
    lsls r0, r0, #24
    bne 2f
4:  ldr r0, =COOP_EM_gSpecialVar_ItemId
    ldrh r0, [r0]
    movs r1, #1
    ldr r3, =COOP_EM_RemoveBagItem + 1
    bl link_call_r3
    movs r0, r4
    ldr r3, =COOP_EM_Task_FadeAndCloseBagMenu + 1
    bl link_call_r3
    b 9f
2:  movs r0, r4
    movs r1, #COOP_EM_FONT_NORMAL
    ldr r2, =COOP_EM_gText_BoxFull
    ldr r3, =COOP_EM_CloseItemMessage + 1
    ldr r5, =COOP_EM_DisplayItemMessage + 1
    bl link_call_r5
9:  pop {r4, r5}
    pop {r0}
    bx r0
    .ltorg

@ "There are two wild POKéMON!\nYou can't aim a BALL!{PAUSE_UNTIL_PRESS}", in Emerald's charset (charmap.txt: A = BB, a = D5, space
@ 00, é 1B, ' B4, ! AB, \n FE, PAUSE_UNTIL_PRESS FC 09, EOS FF).
    .align 2
link_ball_text:
    .byte 0xCE, 0xDC, 0xD9, 0xE6, 0xD9, 0x00, 0xD5, 0xE6, 0xD9, 0x00, 0xE8, 0xEB, 0xE3, 0x00   @ There are two
    .byte 0xEB, 0xDD, 0xE0, 0xD8, 0x00, 0xCA, 0xC9, 0xC5, 0x1B, 0xC7, 0xC9, 0xC8, 0xAB, 0xFE   @ wild POKéMON!\n
    .byte 0xD3, 0xE3, 0xE9, 0x00, 0xD7, 0xD5, 0xE2, 0xB4, 0xE8, 0x00, 0xD5, 0xDD, 0xE1, 0x00   @ You can't aim
    .byte 0xD5, 0x00, 0xBC, 0xBB, 0xC6, 0xC6, 0xAB, 0xFC, 0x09, 0xFF                           @ a BALL!{PAUSE_UNTIL_PRESS}
    .align 2

@ Cmd_trysetcaughtmondexflags (replaced whole): the Pokedex flags and the "added to the POKéDEX" screen read gEnemyParty[0], which
@ is not always the foe that was caught, and belong to the catcher's own game: the catch is handed over after the battle
@ (pk_coop_link_caught; the game that receives it sets them). The partner's catch goes to BattleScript_GiveCaughtMonEnd (just
@ givecaughtmon: no nickname question on the host's screen), the host's to the script's own jump (the nickname question, then
@ givecaughtmon).
    .align 1
    .global link_entry_CATCH_DEX
    .thumb_func
link_entry_CATCH_DEX:
    ldr r2, =COOP_EM_gBattlescriptCurrInstr
    ldr r0, =link_rw                        @ who threw the ball (link_entry_BALLTHROW keeps it: gBattlerAttacker may be the retargeted one)
    ldrb r0, [r0, #link_rw_thrower]
    subs r0, #1
    cmp r0, #2
    bne 1f
    ldr r1, =COOP_EM_BattleScript_GiveCaughtMonEnd
    str r1, [r2]
    bx lr
1:  ldr r0, [r2]
    ldrb r1, [r0, #1]
    ldrb r3, [r0, #2]
    lsls r3, r3, #8
    orrs r1, r3
    ldrb r3, [r0, #3]
    lsls r3, r3, #16
    orrs r1, r3
    ldrb r3, [r0, #4]
    lsls r3, r3, #24
    orrs r1, r3
    str r1, [r2]
    bx lr
    .ltorg

@ Cmd_givecaughtmon (replaced whole): the caught foe (gEnemyParty[gBattlerPartyIndexes[gBattlerTarget]], the one the ball was thrown
@ at, with the nickname the host gave it) is copied into the rewards' RAM with who threw (1 the host, 2 the partner) instead of
@ going into the party or a box of the temporary link party, which the battle's end takes back. The thrower's own game gives it to
@ itself after the battle (pk_coop_link_caught, then pk_coop_give_mon: its own OT, name and gender, the Pokedex).
    .align 1
    .global link_entry_CATCH_GIVE
    .thumb_func
link_entry_CATCH_GIVE:
    push {r4, lr}
    ldr r0, =link_rw
    ldrb r0, [r0, #link_rw_thrower]
    subs r0, #1
    movs r1, #1
    cmp r0, #2
    bne 1f
    movs r1, #2
1:  ldr r2, =link_rw
    strb r1, [r2, #link_rw_caught_by]
    ldr r0, =COOP_EM_gBattlerTarget
    ldrb r0, [r0]
    lsls r0, r0, #1
    ldr r1, =COOP_EM_gBattlerPartyIndexes
    ldrh r1, [r1, r0]
    movs r3, #COOP_EM_OFF_MON_SIZE
    muls r1, r3
    ldr r3, =COOP_EM_gEnemyParty
    adds r1, r1, r3
    adds r2, #link_rw_caught
    movs r3, #COOP_EM_OFF_MON_SIZE
2:  ldrb r4, [r1]
    strb r4, [r2]
    adds r1, #1
    adds r2, #1
    subs r3, #1
    bne 2b
    @ on to our copy of the script's tail (with the end of the battle sent to the partner's game too): the host's catch has the
    @ game's "Gotcha" table line first, the partner's goes straight to the end
    ldr r2, =COOP_EM_gBattlescriptCurrInstr
    ldr r0, =link_script_print
    ldr r1, =link_rw
    ldrb r1, [r1, #link_rw_caught_by]
    cmp r1, #1
    beq 3f
    ldr r0, =link_script_end
3:  str r0, [r2]
    pop {r4}
    pop {r0}
    bx r0
    .ltorg

@ The tail of BattleScript_SuccessBallThrow (after givecaughtmon), our copy: [printfromtable gCaughtMonStringIds; waitmessage
@ B_WAIT_TIME_LONG;] setbyte gBattleOutcome, B_OUTCOME_CAUGHT; endlinkbattle; finishturn. A caught foe ends the battle without the
@ script that sends the end to the link's other player (BattleScript_LinkBattleHandleResult): `endlinkbattle` is that one command,
@ the partner's game ends with the same outcome.
    .align 2
link_script_print:
    .byte COOP_EM_B_SCR_OP_PRINTFROMTABLE
    .4byte COOP_EM_gCaughtMonStringIds
    .byte COOP_EM_B_SCR_OP_WAITMESSAGE
    .2byte COOP_EM_B_WAIT_TIME_LONG
link_script_end:
    .byte COOP_EM_B_SCR_OP_SETBYTE
    .4byte COOP_EM_gBattleOutcome
    .byte COOP_EM_B_OUTCOME_CAUGHT
    .byte COOP_EM_B_SCR_OP_ENDLINKBATTLE
    .byte COOP_EM_B_SCR_OP_FINISHTURN
    .align 2

@ --- fix round 1 -------------------------------------------------------------------------------------------------------------
@ Cmd_handleballthrow's table pointer: a ball goes at the one wild foe that is left, whichever slot it is in. The game aims it at
@ BATTLE_OPPOSITE(gBattlerAttacker); with one foe down and the other the thrower's side-mate's opposite, the attacker is the
@ battler in front of the living foe (0 for foe 1, 2 for foe 3) for the game's own call only, then the real thrower again
@ (the catch commands read who threw it; gBattlerTarget stays at the foe the ball hit). The foes' standing is the engine's own.
    .align 1
    .global link_entry_BALLTHROW
    .thumb_func
link_entry_BALLTHROW:
    push {r4, lr}
    ldr r0, =COOP_EM_gBattleMons + COOP_EM_OFF_BATTLE_MON_SIZE + COOP_EM_OFF_BATTLE_MON_HP
    ldrh r1, [r0]                           @ foe 1's HP
    ldr r0, =COOP_EM_gBattleMons + 3 * COOP_EM_OFF_BATTLE_MON_SIZE + COOP_EM_OFF_BATTLE_MON_HP
    ldrh r3, [r0]                           @ foe 3's HP
    ldr r0, =COOP_EM_gBattlerAttacker
    ldrb r4, [r0]                           @ the thrower
    adds r2, r4, #1
    ldr r0, =link_rw
    strb r2, [r0, #link_rw_thrower]         @ (thrower + 1: 0 = none yet)
    ldr r0, =COOP_EM_gBattlerAttacker
    movs r2, r4
    cmp r1, #0
    beq 1f
    cmp r3, #0
    bne 2f                                  @ both stand: the game's own answer
    movs r2, #0                             @ only foe 1
    b 2f
1:  cmp r3, #0
    beq 2f
    movs r2, #2                             @ only foe 3
2:  strb r2, [r0]
    ldr r3, =COOP_EM_Cmd_handleballthrow + 1
    bl link_call_r3
    ldr r0, =COOP_EM_gBattlerAttacker
    strb r4, [r0]
    pop {r4}
    pop {r0}
    bx r0
    .ltorg

@ Cmd_drawlvlupbox's table pointer: the level-up box of a Pokemon of the partner's side closes by itself (an A press made for it every
@ 49 frames, the game's link text delay: the host's player isn't asked to answer for the partner's Pokemon); the host's own boxes
@ stay as in the game.
    .align 1
    .global link_entry_LVLBOX
    .thumb_func
link_entry_LVLBOX:
    ldr r0, =COOP_EM_gBattleStruct
    ldr r0, [r0]
    ldrb r0, [r0, #COOP_EM_OFF_BATTLE_STRUCT_EXP_GETTER_MON_ID]
    cmp r0, #3
    blt 9f
    ldr r1, =link_rw
    ldrb r0, [r1, #link_rw_boxframes]
    adds r0, #1
    cmp r0, #49
    blt 8f
    movs r0, #0
    ldr r2, =COOP_EM_gMain + COOP_EM_OFF_MAIN_NEW_KEYS
    ldrh r3, [r2]
    movs r1, #COOP_EM_A_BUTTON
    orrs r3, r1
    strh r3, [r2]
    ldr r1, =link_rw
8:  strb r0, [r1, #link_rw_boxframes]
9:  ldr r3, =COOP_EM_Cmd_drawlvlupbox + 1
    bx r3
    .ltorg

@ The PP items' gItems battleUseFunc (ItemUseInBattle_PPRecovery: Ether / Elixir): the master's engine can't tell which move of the
@ partner's Pokemon was restored (its battle data would stay stale), so the partner's bag refuses them ("Not now!", nothing used);
@ the master's own bag is the game's.
    .align 1
    .global link_entry_PPITEM
    .thumb_func
link_entry_PPITEM:
    ldr r1, =link_ram
    ldrb r1, [r1, #link_myid]
    cmp r1, #0
    beq 1f
    push {r5, lr}
    movs r1, #COOP_EM_FONT_NORMAL
    ldr r2, =link_pp_text
    ldr r3, =COOP_EM_CloseItemMessage + 1
    ldr r5, =COOP_EM_DisplayItemMessage + 1
    bl link_call_r5
    pop {r5}
    pop {r0}
    bx r0
1:  ldr r3, =COOP_EM_ItemUseInBattle_PPRecovery + 1
    bx r3
    .ltorg

@ "Not now!{PAUSE_UNTIL_PRESS}" (N = C8, o = E3, t = E8, space 00, n = E2, w = EB, ! = AB)
    .align 2
link_pp_text:
    .byte 0xC8, 0xE3, 0xE8, 0x00, 0xE2, 0xE3, 0xEB, 0xAB, 0xFC, 0x09, 0xFF
    .align 2

@ A ball a modified partner threw with two wild foes up: the action just ends.
link_script_cancel:
    .byte COOP_EM_B_SCR_OP_FINISHACTION
    .align 2
