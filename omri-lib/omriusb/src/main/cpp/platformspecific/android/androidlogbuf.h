/*
 * Copyright (C) 2018 IRT GmbH
 *
 * Author:
 *  Fabian Sattler
 *
 * This file is a part of IRT DAB library.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

#ifndef CMAKETEST_ANDROIDLOGBUF_H
#define CMAKETEST_ANDROIDLOGBUF_H

#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <streambuf>

//Hackish thing to redirect std::cout to androids logcat
class androidlogbuf : public std::streambuf {
public:
    static constexpr int bufsize{512};
    androidlogbuf() { this->setp(buffer, buffer + bufsize - 1); }

    // Optional file tee (path set from Java via UsbHelper.setLogFile): mirror every std::cout line
    // into a file so omri's native logs can be read off the USB stick without adb/logcat. Bounded so
    // a chatty scan can never fill the volume. Definitions live in native-lib.cpp (single TU).
    static FILE* s_file;
    static long s_written;
    // Generous runaway-safety cap only (a full drive's logs fit easily) — the log is APPENDED across
    // launches and never truncated, so a session's data always stays. Delete the file to reset.
    static constexpr long s_cap{8 * 1024 * 1024};
    static void setLogFile(const char* path);

private:
    int overflow(int c) {
        if (c == traits_type::eof()) {
            *this->pptr() = traits_type::to_char_type(c);
            this->sbumpc();
        }
        return this->sync()? traits_type::eof(): traits_type::not_eof(c);
    }

    int sync() {
        int rc = 0;
        if (this->pbase() != this->pptr()) {
            char writebuf[bufsize+1];
            memcpy(writebuf, this->pbase(), this->pptr() - this->pbase());
            writebuf[this->pptr() - this->pbase()] = '\0';

            rc = __android_log_write(ANDROID_LOG_INFO, "std", writebuf) > 0;
            if (s_file != nullptr && s_written < s_cap) {
                long n = static_cast<long>(fwrite(writebuf, 1, strlen(writebuf), s_file));
                fputc('\n', s_file);
                fflush(s_file);
                s_written += n + 1;
            }
            this->setp(buffer, buffer + bufsize - 1);
        }
        return rc;
    }

    char buffer[bufsize];
};


#endif //CMAKETEST_ANDROIDLOGBUF_H
