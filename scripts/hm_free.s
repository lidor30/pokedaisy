@ NO HMS NEEDED (companion/data/HmFree.kt): ScrCmd_checkpartymove rewritten in its own 124 bytes (agbcc's build of it,
@ the same in every FireRed / LeafGreen / Emerald / Ruby / Sapphire). The game's own version
@ answers the first Pokémon (not an egg) that knows the move, else PARTY_SIZE; this one does the same, but with nobody
@ knowing it answers the first Pokémon that isn't an egg, so every field move script (whose badge check comes first)
@ goes on with that one. MOVE_SECRET_POWER (290, Emerald's secret bases: a TM, not an HM) keeps the game's rule.
@
@ Registers: r4 = &gPlayerParty[i], r5 = the answer (6 = none yet), r6 = i, r7 = the move.
@ gSpecialVar_Result is gSpecialVar_0x8004 + 0x10 in every build this goes on (HmFree.kt checks it).
@ The three calls and the two words are filled in per ROM from the original function (HmFree.kt).
@
@ Assemble: clang --target=thumbv4t-none-eabi -mcpu=arm7tdmi -c hm_free.s -o t.o (the bytes are HmFree.CHECK_CODE).
    .syntax unified
    .thumb
    .text
start:
    push {r4, r5, r6, r7, lr}
    bl ScriptReadHalfword
    lsls r0, r0, #16
    lsrs r7, r0, #16
    movs r5, #6
    movs r6, #0
    ldr r4, Party
loop:
    cmp r6, #5
    bhi done
    adds r0, r4, #0
    movs r1, #11
    bl GetMonData
    cmp r0, #0
    beq done
    adds r0, r4, #0
    movs r1, #0x2d
    bl GetMonData
    cmp r0, #0
    bne next
    cmp r5, #6
    bne knows
    adds r5, r6, #0
knows:
    adds r0, r4, #0
    adds r1, r7, #0
    bl MonKnowsMove
    cmp r0, #0
    bne found
next:
    adds r6, r6, #1
    adds r4, #100
    b loop
done:
    movs r0, #0x91
    lsls r0, r0, #1
    cmp r7, r0
    bne set
    movs r5, #6
    b set
found:
    adds r5, r6, #0
set:
    ldr r1, Var8004
    strh r5, [r1, #0x10]
    cmp r5, #6
    beq out
    movs r0, #100
    muls r0, r5, r0
    ldr r1, Party
    adds r0, r0, r1
    movs r1, #11
    bl GetMonData
    ldr r1, Var8004
    strh r0, [r1]
out:
    movs r0, #0
    pop {r4, r5, r6, r7}
    pop {r1}
    bx r1
    .align 2
Party: .word 0
Var8004: .word 0
