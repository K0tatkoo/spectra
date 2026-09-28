// Spectra's one piece of its own native code: Android has no Java API for
// thread affinity, and the stem worker needs to keep itself on the phone's
// fastest core. See CpuAffinity.kt for why.

// sched_setaffinity and cpu_set_t are GNU extensions in bionic too.
#define _GNU_SOURCE

#include <errno.h>
#include <jni.h>
#include <sched.h>

// Pins the calling thread to the CPUs in `mask` (bit n = cpu n). 0, or -errno.
JNIEXPORT jint JNICALL
Java_com_n3d_spectra_stems_CpuAffinity_pinCallingThread(JNIEnv *env, jclass cls, jlong mask) {
    (void) env;
    (void) cls;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int cpu = 0; cpu < 64; cpu++) {
        if (((unsigned long long) mask >> cpu) & 1ULL) CPU_SET(cpu, &set);
    }
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? 0 : -errno;
}

// The calling thread's affinity as a mask, or -errno.
JNIEXPORT jlong JNICALL
Java_com_n3d_spectra_stems_CpuAffinity_callingThreadMask(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return -errno;
    unsigned long long mask = 0;
    for (int cpu = 0; cpu < 64; cpu++) {
        if (CPU_ISSET(cpu, &set)) mask |= 1ULL << cpu;
    }
    return (jlong) mask;
}
