// Shared by the app and its broadcast extension.
//
// The extension and the app are two processes. The audio passes between them
// through a file in the App Group that both map into memory, and the writer's
// position in it has to be published with the same release/acquire ordering a
// ring inside one process gets from Swift's Atomic — which cannot be placed on
// memory another process owns. These are the two operations, as the compiler's
// builtins.

#include <stdint.h>

static inline uint64_t spectra_load_acquire(const void *p) {
    return __atomic_load_n((const uint64_t *)p, __ATOMIC_ACQUIRE);
}

static inline void spectra_store_release(void *p, uint64_t value) {
    __atomic_store_n((uint64_t *)p, value, __ATOMIC_RELEASE);
}
