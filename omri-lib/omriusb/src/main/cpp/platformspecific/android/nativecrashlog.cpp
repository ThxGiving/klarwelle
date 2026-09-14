/*
 * Native crash logger — see nativecrashlog.h. Everything in the signal handler uses only
 * async-signal-safe calls (open/write/close, _Unwind_Backtrace); no malloc, no stdio, no JNI.
 */
#include "nativecrashlog.h"

#include <csignal>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <ctime>
#include <unistd.h>
#include <fcntl.h>
#include <unwind.h>

namespace {

    char g_path[1024] = {0};
    const int g_sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    struct sigaction g_old[sizeof(g_sigs) / sizeof(g_sigs[0])];

    // ---- async-signal-safe writers ----
    void wr(int fd, const char* s, size_t n) {
        while (n > 0) {
            ssize_t r = write(fd, s, n);
            if (r < 0) { if (errno == EINTR) continue; break; }
            s += r; n -= (size_t) r;
        }
    }
    void wrs(int fd, const char* s) { wr(fd, s, strlen(s)); }
    void wrdec(int fd, long v) {
        char buf[24]; int i = sizeof(buf); bool neg = v < 0; unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
        if (u == 0) buf[--i] = '0';
        while (u > 0) { buf[--i] = (char)('0' + (u % 10)); u /= 10; }
        if (neg) buf[--i] = '-';
        wr(fd, buf + i, sizeof(buf) - i);
    }
    void wrhex(int fd, uintptr_t v) {
        static const char* hex = "0123456789abcdef";
        char buf[2 + sizeof(uintptr_t) * 2]; buf[0] = '0'; buf[1] = 'x';
        const int nib = sizeof(uintptr_t) * 2;
        for (int i = 0; i < nib; i++) buf[2 + i] = hex[(v >> ((nib - 1 - i) * 4)) & 0xF];
        wr(fd, buf, 2 + nib);
    }

    struct BtState { int fd; int count; };
    _Unwind_Reason_Code btCb(struct _Unwind_Context* ctx, void* arg) {
        BtState* st = (BtState*) arg;
        uintptr_t ip = _Unwind_GetIP(ctx);
        if (ip != 0) {
            wrs(st->fd, "  #"); wrdec(st->fd, st->count); wrs(st->fd, " "); wrhex(st->fd, ip); wrs(st->fd, "\n");
        }
        if (++st->count >= 48) return _URC_END_OF_STACK;
        return _URC_NO_REASON;
    }

    void handler(int sig, siginfo_t* info, void* /*uctx*/) {
        if (g_path[0]) {
            int fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (fd >= 0) {
                wrs(fd, "\n=== omri NATIVE CRASH ===\n");
                wrs(fd, "signal="); wrdec(fd, sig);
                wrs(fd, " code="); wrdec(fd, info ? info->si_code : 0);
                wrs(fd, " faultAddr="); wrhex(fd, (uintptr_t)(info ? info->si_addr : nullptr));
                wrs(fd, " tid="); wrdec(fd, (long) gettid());
                wrs(fd, "\nbacktrace:\n");
                BtState st { fd, 0 };
                _Unwind_Backtrace(btCb, &st);
                wrs(fd, "=== end native crash (offline-symbolize the pcs against libirtdab.so) ===\n");
                close(fd);
            }
        }
        // Restore the default action and re-raise, so Android still produces its tombstone and the
        // process actually terminates (we only wanted to leave a breadcrumb first).
        for (size_t i = 0; i < sizeof(g_sigs) / sizeof(g_sigs[0]); i++) {
            if (g_sigs[i] == sig) { sigaction(sig, &g_old[i], nullptr); break; }
        }
        raise(sig);
    }

} // namespace

void installNativeCrashLogger(const char* path) {
    if (path == nullptr) return;
    strncpy(g_path, path, sizeof(g_path) - 1);
    g_path[sizeof(g_path) - 1] = '\0';

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    for (size_t i = 0; i < sizeof(g_sigs) / sizeof(g_sigs[0]); i++) {
        sigaction(g_sigs[i], &sa, &g_old[i]);
    }
}
