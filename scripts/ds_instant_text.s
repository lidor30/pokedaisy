@ INSTANT TEXT on the Gen 4 DS games (DsInstantText.kt): the routine their text printer task calls in place of RenderFont.
@ Assemble: llvm-mc -triple=thumbv5te -filetype=obj ds_instant_text.s -o t.o && llvm-objcopy -O binary --only-section=.text t.o t.bin
@ Its two `bl textfast` are placeholders: DsInstantText fills them with the cart's RenderFont (+0x08) and CopyWindowToVram (+0x2A).
	.syntax unified
	.thumb
	.text
@ r0 = the printer. RenderFont again after each printed letter, the letter delay zeroed, until it stops printing
@ (a wait, a page break, a pause, the end); the window copied if the text ended. Returns what the caller expects.
textfast:
	push {r4, r5, r6, lr}
	adds r4, r0, #0
	movs r5, #0
loop:
	adds r0, r4, #0
	bl textfast            @ placeholder: RenderFont
	cmp r0, #0
	bne out
	adds r5, #1
	cmp r5, #255
	bhs print
	movs r6, #0
	adds r1, r4, #0
	adds r1, #42
	strb r6, [r1]
	b loop
out:
	cmp r5, #0
	beq ret
	cmp r0, #1
	bne print
	ldr r0, [r4, #4]
	bl textfast            @ placeholder: CopyWindowToVram
	movs r0, #1
	b ret
print:
	movs r0, #0
ret:
	pop {r4, r5, r6, pc}
