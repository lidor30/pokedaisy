#include "3ds.h"
#include "../../../third_party/gpsp/video_thread_sys.h"
#include <assert.h>
#include <stdio.h>

struct TestThread { ThreadFunc fn; void* arg; };
static struct TestThread worker;
static int priorities[8], attempts, failAttempts, ran, appPriority = 0x30;
static int cpuLimit;

void LightSemaphore_Init(LightSemaphore* s, int a, int b) { (void)b; s->count = a; }
void LightSemaphore_Release(LightSemaphore* s, int n) { s->count += n; }
void LightSemaphore_Acquire(LightSemaphore* s, int n) { s->count -= n; }
int svcGetThreadPriority(s32* out, Handle h) { (void)h; *out = appPriority; return 0; }
int svcGetProcessorID(void) { return 1; }
uint64_t svcGetSystemTick(void) { return 268111856ULL; }
int APT_SetAppCpuTimeLimit(unsigned limit) { cpuLimit = (int)limit; return 0; }
int svcSetThreadPriority(Handle h, s32 p) { (void)h; (void)p; return 0; }
Handle threadGetHandle(Thread t) { assert(t == &worker); return 1; }
int threadJoin(Thread t, uint64_t timeout) { assert(t == &worker && timeout == U64_MAX); return 0; }
void threadFree(Thread t) { assert(t == &worker); }

Thread threadCreate(ThreadFunc fn, void* arg, size_t stack, int priority, int core, bool detached) {
    assert(core == 1 && stack == 64 * 1024 && !detached);
    assert(attempts < 8);
    priorities[attempts++] = priority;
    if (attempts <= failAttempts) return NULL;
    worker = (struct TestThread){fn, arg};
    return &worker;
}

static void render(void* p) { assert(p == &ran); ++ran; }

int main(void) {
    assert(vt_thread_cpu() == -1);
    for (failAttempts = 0; failAttempts <= 3; ++failAttempts) {
        attempts = 0;
        int result = vt_thread_start(render, &ran, 1);
        assert(cpuLimit == 80);
        assert(priorities[0] == 0x2f);
        if (failAttempts >= 1) assert(priorities[1] == 0x30);
        if (failAttempts >= 2) assert(priorities[2] == 0x3f);
        if (failAttempts == 3) {
            assert(result != 0 && attempts == 3);
        } else {
            assert(result == 0 && attempts == failAttempts + 1);
            assert(vt_thread_cpu() == -1);
            int before = ran;
            worker.fn(worker.arg);
            assert(ran == before + 1 && vt_thread_cpu() == 1);
            vt_thread_join();
        }
    }
    appPriority = 0x31;
    attempts = 0;
    failAttempts = 8;
    assert(vt_thread_start(render, &ran, 1) != 0);
    assert(attempts == 2 && priorities[0] == 0x30 && priorities[1] == 0x3f);
    puts("PASS: worker priority retries, same CPU, CPU budget, entry arguments, failure fallback, duplicate priorities");
    return 0;
}
