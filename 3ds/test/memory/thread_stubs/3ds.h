#ifndef TEST_3DS_H
#define TEST_3DS_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
typedef int32_t s32;
typedef uint32_t Handle;
typedef void (*ThreadFunc)(void*);
typedef struct TestThread* Thread;
typedef struct { int count; } LightSemaphore;
#define CUR_THREAD_HANDLE 0xffff8000u
#define U64_MAX UINT64_MAX
#define SYSCLOCK_ARM11 268111856ULL
void LightSemaphore_Init(LightSemaphore*, int, int);
void LightSemaphore_Release(LightSemaphore*, int);
void LightSemaphore_Acquire(LightSemaphore*, int);
int svcGetThreadPriority(s32*, Handle);
int svcGetProcessorID(void);
uint64_t svcGetSystemTick(void);
int APT_SetAppCpuTimeLimit(unsigned);
Thread threadCreate(ThreadFunc, void*, size_t, int, int, bool);
int svcSetThreadPriority(Handle, s32);
Handle threadGetHandle(Thread);
int threadJoin(Thread, uint64_t);
void threadFree(Thread);
#endif
