// App hosts use the same RTC repair and mix rate as a single running cartridge.
#include "pk_api.h"
#include "pk_host.h"
#include "pk_link.h"

bool pk_link_open_with_rtc(int n, const char* const* roms, const char* const* saves) {
    pk_link_after_reset = pk_heal_rtc;
    return pk_link_open(n, roms, saves, PK_API_SAMPLE_RATE);
}
