#ifndef PD_LOG_BUFFER_H
#define PD_LOG_BUFFER_H
#include <stddef.h>
#include <string.h>

// Keep recent complete lines in RAM. Overflow must never force SD I/O while
// gameplay is running. Returns the number of discarded lines.
static inline unsigned pd_log_buffer_append(char* buffer, size_t capacity, size_t* used,
                                            const char* line, size_t len) {
    if (len > capacity) return 1;
    size_t cut = 0;
    unsigned dropped = 0;
    while (*used - cut > capacity - len) {
        const char* newline = memchr(buffer + cut, '\n', *used - cut);
        cut = newline ? (size_t) (newline - buffer) + 1 : *used;
        ++dropped;
    }
    if (cut) {
        memmove(buffer, buffer + cut, *used - cut);
        *used -= cut;
    }
    memcpy(buffer + *used, line, len);
    *used += len;
    return dropped;
}
#endif
