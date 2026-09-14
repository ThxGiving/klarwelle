/*
 * Minimal native crash logger for omri: installs signal handlers that write the signal + a raw
 * backtrace to a file (async-signal-safe), then re-raise so the process still dies (and Android
 * still makes its tombstone). Lets a native SIGSEGV in omri be diagnosed off a USB stick without adb.
 *
 * App diagnostic add-on (LGPL 2.1, like the rest of omri-usb).
 */
#ifndef NATIVECRASHLOG_H
#define NATIVECRASHLOG_H

// Install handlers for SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE writing to [path]. Safe to call once.
void installNativeCrashLogger(const char* path);

#endif // NATIVECRASHLOG_H
