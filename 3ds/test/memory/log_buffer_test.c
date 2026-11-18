#include "../../host/pd_log_buffer.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    struct { char before[8], buffer[32], after[8]; } guarded;
    memset(&guarded, 0x5a, sizeof(guarded));
    size_t used = 0;
    const char* lines[] = { "first\n", "second\n", "third\n", "fourth line\n", "fifth\n" };
    unsigned dropped = 0;
    for (unsigned i = 0; i < 5; ++i)
        dropped += pd_log_buffer_append(guarded.buffer, sizeof(guarded.buffer), &used, lines[i], strlen(lines[i]));
    const char expected[] = "second\nthird\nfourth line\nfifth\n";
    assert(dropped == 1 && used == strlen(expected));
    assert(!memcmp(guarded.buffer, expected, used));
    char oversized[40];
    memset(oversized, 'x', sizeof(oversized));
    assert(pd_log_buffer_append(guarded.buffer, 32, &used, oversized, 40) == 1);
    assert(used == strlen(expected) && !memcmp(guarded.buffer, expected, used));
    for (unsigned i = 0; i < 8; ++i) assert(guarded.before[i] == 0x5a && guarded.after[i] == 0x5a);

    char buffer[16384];
    used = 0;
    for (unsigned i = 0; i < 10000; ++i) {
        char line[40];
        int n = snprintf(line, sizeof(line), "sample %u\n", i);
        pd_log_buffer_append(buffer, sizeof(buffer), &used, line, (size_t)n);
        assert(used <= sizeof(buffer) && buffer[used - 1] == '\n');
    }
    assert(!memcmp(buffer + used - strlen("sample 9999\n"), "sample 9999\n", strlen("sample 9999\n")));
    puts("PASS: bounded log ring, complete recent lines, overflow, oversized entries, guard bytes");
    return 0;
}
