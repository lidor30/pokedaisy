#ifndef PD_TEST_DIAGNOSTIC_ROM_H
#define PD_TEST_DIAGNOSTIC_ROM_H

// Our own ARM7 program, generated only under the test's build directory.
// No Nintendo logo, BIOS, game data, external ROM or ARM assembler is needed.
// Set mode 3, paint red with one green and one blue pixel, write RAM markers,
// then spin. The remaining 32 MiB has an address-dependent pattern so that
// aliased or omitted cartridge blocks cannot pass the boundary/CRC checks.
static void diagnostic_word(unsigned char* out, uint32_t word) {
    for (unsigned i = 0; i < 4; ++i) out[i] = (unsigned char)(word >> (i * 8));
}

static void diagnostic_rom(const char* path) {
    static const uint32_t program[] = {
        0xe3a00301, // mov r0, #0x04000000
        0xe3a01b01, // mov r1, #0x400
        0xe3811003, // orr r1, r1, #3  (BG2, mode 3)
        0xe1c010b0, // strh r1, [r0]
        0xe3a00406, // mov r0, #0x06000000
        0xe3a0101f, // mov r1, #31
        0xe3a02c96, // mov r2, #38400
        0xe0c010b2, // fill: strh r1, [r0], #2
        0xe2522001, // subs r2, r2, #1
        0x1afffffc, // bne fill
        0xe3a00406, // mov r0, #0x06000000
        0xe3a01e3e, // mov r1, #0x3e0
        0xe1c010b2, // strh r1, [r0, #2]
        0xe3a01b1f, // mov r1, #0x7c00
        0xe1c010b4, // strh r1, [r0, #4]
        0xe3a00402, // mov r0, #0x02000000
        0xe59f1010, // ldr r1, [pc, #16]
        0xe5801000, // str r1, [r0]
        0xe3a00403, // mov r0, #0x03000000
        0xe59f1008, // ldr r1, [pc, #8]
        0xe5801000, // str r1, [r0]
        0xeafffffe, // stop: b stop
        0x12345678, // EWRAM marker
        0x89abcdef, // IWRAM marker
    };
    FILE* file = fopen(path, "wb");
    assert(file);
    unsigned char block[65536];
    for (uint32_t base = 0; base < (32u << 20); base += sizeof(block)) {
        for (uint32_t i = 0; i < sizeof(block); ++i) {
            uint32_t at = base + i;
            block[i] = (unsigned char)((at * 33) ^ (at >> 8) ^ (at >> 16) ^ (at >> 24));
        }
        if (!base) {
            memset(block, 0, 0xc0);
            diagnostic_word(block, 0xea00002e); // b 0x080000c0
            memcpy(block + 0xa0, "PD MEM TEST", 11);
            memcpy(block + 0xac, "PDMT00", 6);
            block[0xb2] = 0x96;
            unsigned checksum = 0x19;
            for (unsigned i = 0xa0; i <= 0xbc; ++i) checksum += block[i];
            block[0xbd] = (unsigned char)-checksum;
            for (unsigned i = 0; i < sizeof(program) / sizeof(*program); ++i)
                diagnostic_word(block + 0xc0 + i * 4, program[i]);
        }
        assert(fwrite(block, 1, sizeof(block), file) == sizeof(block));
    }
    assert(!fclose(file));
}
#endif
