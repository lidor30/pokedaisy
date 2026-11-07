@ The co-op story lock on retail English Emerald (app/src/main/cpp/pk_coop_lock.c): our Thumb code, assembled by
@ scripts/gen_coop_story.py at COOP_LK_CODE_ADDR (the ROM's empty tail, 0x08FFE000) into pk_coop_lock_emerald.h's
@ COOP_LK_CODE, with the story table (COOP_LK_STORY, 8 bytes an event: script u32, id u16, kind u8, 0) at
@ COOP_LK_TABLE_ADDR in the same block.
@
@ The generator puts `.set COOP_LK_<name>, value` for every address / offset / constant of the header in front of this
@ file. The head of ScriptContext_SetupScript (its first 8 bytes) is patched to `ldr r3, [pc, #0]; bx r3; .word
@ lock_entry_SETUP | 1`: it comes in here with r0 = the script and lr = the caller's, r3 free. The four instructions
@ that patch displaced (`push {r4, r5, lr}; adds r5, r0, #0; ldr r4, [pc, #32]; ldr r1, [pc, #36]`: the generator checks
@ them) run in lock_orig, the two pc-relative loads taken from the function's own literal pool, and the function goes
@ on from its fifth instruction (COOP_LK_RESUME).
@
@ The lock's mailbox at 0x0203F400 (struct pk_coop_lock_mailbox, pk_coop_lock.h): the .set names below are carried into the
@ header as COOP_LK_ASM_*, which pk_coop_lock.c checks against the struct at compile time.

    .syntax unified
    .cpu arm7tdmi
    .thumb
    .text

    .set lock_mailbox, 0x0203F400
    .set lock_mb_active, 0
    .set lock_mb_kind, 1
    .set lock_mb_id, 2
    .set lock_mb_allow, 4
    .set lock_mb_map_group, 6
    .set lock_mb_map_num, 7
    .set lock_mb_x, 8
    .set lock_mb_y, 10
    .set lock_mb_facing, 12
    .set lock_mb_selected, 13
    .set lock_mb_last_talked, 14
    .set lock_mb_script, 0x20
    .global lock_mailbox, lock_mb_active, lock_mb_kind, lock_mb_id, lock_mb_allow, lock_mb_map_group, lock_mb_map_num
    .global lock_mb_x, lock_mb_y, lock_mb_facing, lock_mb_selected, lock_mb_last_talked, lock_mb_script

@ ---------------------------------------------------------------------------------------------------------------------
@ ScriptContext_SetupScript's new head. r0 = the script, lr = the caller's.
@   not active, or the script isn't a story event's, or it is the one the app allowed (allow back to 0): the game's own
@   function.
@   else the hold is recorded (id, kind, map, the player's tile + facing, LastTalked, SelectedObjectEvent) and
@     OBJECT / FRAME: back to the caller, nothing set up (the game goes on as if nothing was triggered);
@     COORD: the game's function runs the step-back script instead (one step opposite the facing, then releaseall), the
@     tile recorded being the one after that step. A COORD trigger with no 4-way facing runs the story script.
    .global lock_entry_SETUP
    .thumb_func
lock_entry_SETUP:
    push {r4, r5, r6, r7}
    ldr r7, =lock_mailbox
    ldrb r1, [r7, #lock_mb_active]
    cmp r1, #0
    bne 2f
    b lock_orig
2:  ldr r1, =COOP_LK_TABLE_ADDR
    ldr r2, =COOP_LK_STORY_COUNT
3:  ldr r3, [r1]
    cmp r3, r0
    beq 4f
    adds r1, #8
    subs r2, #1
    bne 3b
    b lock_orig
4:  ldrh r4, [r1, #4]                @ r4 = the event's id
    ldrb r5, [r1, #6]                @ r5 = its kind (0 COORD, 1 OBJECT, 2 FRAME)
    ldrh r2, [r7, #lock_mb_allow]
    cmp r2, r4
    bne 5f
    movs r2, #0
    strh r2, [r7, #lock_mb_allow]    @ the app's word is good for one start
    b lock_orig
5:  ldr r1, =COOP_LK_gPlayerAvatar
    ldrb r1, [r1, #COOP_LK_AVATAR_OBJ_ID]
    movs r2, #COOP_LK_OBJ_SIZE
    muls r1, r2
    ldr r2, =COOP_LK_gObjectEvents
    adds r1, r1, r2                  @ r1 = the player's object event
    ldrb r6, [r1, #COOP_LK_OBJ_FACING]
    movs r3, #0xF
    ands r6, r3                      @ r6 = its facing direction (1 south, 2 north, 3 west, 4 east)
    cmp r5, #0
    bne 6f
    subs r2, r6, #1
    cmp r2, #3
    bls 6f
    b lock_orig                      @ a COORD trigger the step back can't answer
6:  strb r5, [r7, #lock_mb_kind]
    strb r6, [r7, #lock_mb_facing]
    ldrh r2, [r1, #COOP_LK_OBJ_CURRENT_X]
    strh r2, [r7, #lock_mb_x]
    ldrh r2, [r1, #COOP_LK_OBJ_CURRENT_Y]
    strh r2, [r7, #lock_mb_y]
    ldr r1, =COOP_LK_gSaveBlock1Ptr
    ldr r1, [r1]
    ldrb r2, [r1, #COOP_LK_SB1_LOCATION_GROUP]
    strb r2, [r7, #lock_mb_map_group]
    ldrb r2, [r1, #COOP_LK_SB1_LOCATION_NUM]
    strb r2, [r7, #lock_mb_map_num]
    ldr r1, =COOP_LK_gSpecialVar_LastTalked
    ldrh r2, [r1]
    strh r2, [r7, #lock_mb_last_talked]
    ldr r1, =COOP_LK_gSelectedObjectEvent
    ldrb r2, [r1]
    strb r2, [r7, #lock_mb_selected]
    strh r4, [r7, #lock_mb_id]       @ last: the hold exists from here
    cmp r5, #0
    beq 7f
    pop {r4, r5, r6, r7}
    bx lr
7:  subs r2, r6, #1                  @ r2 = facing - 1: the step back's index
    ldr r3, =lock_step_dx
    ldrsb r1, [r3, r2]
    ldrh r3, [r7, #lock_mb_x]
    adds r3, r3, r1
    strh r3, [r7, #lock_mb_x]
    ldr r3, =lock_step_dy
    ldrsb r1, [r3, r2]
    ldrh r3, [r7, #lock_mb_y]
    adds r3, r3, r1
    strh r3, [r7, #lock_mb_y]
    lsls r1, r2, #4
    ldr r0, =lock_stepback
    adds r0, r0, r1                  @ the step-back script for this facing
    b lock_orig

@ The displaced instructions, then on into the function. r0 = the script to set up.
lock_orig:
    pop {r4, r5, r6, r7}
    push {r4, r5, lr}
    adds r5, r0, #0
    ldr r4, =COOP_LK_POOL_R4
    ldr r4, [r4]
    ldr r1, =COOP_LK_POOL_R1
    ldr r1, [r1]
    ldr r3, =COOP_LK_RESUME + 1
    bx r3
    .ltorg

@ The step back's tile offsets, by facing - 1 (south, north, west, east: one step the other way).
    .balign 4
lock_step_dx:
    .byte 0, 0, 1, -1
lock_step_dy:
    .byte -1, 1, 0, 0

@ The four step-back scripts, 16 bytes each, by facing - 1:
@   applymovement OBJ_EVENT_ID_PLAYER, <one step>; waitmovement 0; releaseall; end   + the movement (step, step_end).
    .macro step_script move
9:  .byte COOP_LK_OP_applymovement
    .hword COOP_LK_LOCALID_PLAYER
    .word 9b + 12
    .byte COOP_LK_OP_waitmovement
    .hword 0
    .byte COOP_LK_OP_releaseall, COOP_LK_OP_end
    .byte \move, COOP_LK_MOVE_step_end
    .byte 0, 0
    .endm
    .balign 4
lock_stepback:
    step_script COOP_LK_MOVE_walk_up       @ facing south: back is north
    step_script COOP_LK_MOVE_walk_down     @ facing north
    step_script COOP_LK_MOVE_walk_right    @ facing west
    step_script COOP_LK_MOVE_walk_left     @ facing east
