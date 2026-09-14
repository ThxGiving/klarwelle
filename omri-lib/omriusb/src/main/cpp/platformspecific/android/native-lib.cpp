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

#include <jni.h>

#include <string>
#include <vector>
#include <memory>
#include <set>

#include <android/log.h>

#include "androidlogbuf.h"
#include "nativecrashlog.h"

#include "jtunerusbdevice.h"
#include "jusbdevice.h"
#include "raontunerinput.h"
#include "ediinput.h"

extern "C" {

const std::string LOG_TAG{"UsbHelperNative"};

std::vector<std::shared_ptr<JUsbDevice>> m_usbDevices;

std::vector<std::unique_ptr<DabUsbTunerInput>> m_dabInputs;

// Devices whose USB permission was granted (opened). Native start ops on a not-yet-opened
// device dereference an uninitialised tuner -> SIGSEGV. Instead we gate on this set and, on a
// precondition failure, throw a catchable Java exception with a message we can show in the UI.
std::set<std::string> m_readyDevices;

// Fault tolerance: turn a would-be native crash into a Java exception instead of aborting.
static void throwJavaError(JNIEnv* env, const std::string& msg) {
    __android_log_print(ANDROID_LOG_ERROR, "UsbHelperNative", "%s", msg.c_str());
    if (env->ExceptionCheck()) return;
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg.c_str());
        env->DeleteLocalRef(exClass);
    }
}

JavaVM* m_javaVm;

jclass m_usbTunerClass = nullptr;
jclass m_dabServiceClass = nullptr;
jclass m_dabServiceComponentClass = nullptr;
jclass m_dabServiceUserApplicationClass = nullptr;
jclass m_termIdClass = nullptr;
jclass m_dynamicLabelClass = nullptr;
jclass m_dynamicLabelPlusItemClass = nullptr;
jclass m_slideshowClass = nullptr;

jclass m_ediTunerClass = nullptr;
jclass m_dabTimeClass = nullptr;

void cacheClassDefinitions(JavaVM *vm) {
    JNIEnv* env;
    vm->GetEnv ((void **) &env, JNI_VERSION_1_6);

    m_usbTunerClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/TunerUsb"));
    m_dabServiceClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/RadioServiceDabImpl"));
    m_dabServiceComponentClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/RadioServiceDabComponentImpl"));
    m_dabServiceUserApplicationClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/RadioServiceDabUserApplicationImpl"));

    //Metadata classes
    m_termIdClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/TermIdImpl"));
    m_dynamicLabelClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/TextualDabDynamicLabelImpl"));
    m_dynamicLabelPlusItemClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/TextualDabDynamicLabelPlusItemImpl"));
    m_slideshowClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/VisualDabSlideShowImpl"));

    // EDI (DAB-over-IP) stripped from the Java side (DAB-only USB build) — do not FindClass
    // the removed TunerEdistream class; an unhandled ClassNotFound here aborts the whole VM.
    m_ediTunerClass = nullptr;
    //DABtime class
    m_dabTimeClass = (jclass)env->NewGlobalRef(env->FindClass("org/omri/radio/impl/DabTime"));
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    //sets the redirect stream for logcat logging...should be deleted at cleanup with
    //delete std::cout.rdbuf(0);

    //TODO set to enable debug output
#ifdef DEBUGOUTPUT
    std::cout.rdbuf(new androidlogbuf);
#endif

    JNIEnv* env;
    vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    env->GetJavaVM(&m_javaVm);

    cacheClassDefinitions(m_javaVm);

    return JNI_VERSION_1_6;
}


// std::cout->file tee state (declared in androidlogbuf.h) — single definition here.
FILE* androidlogbuf::s_file = nullptr;
long androidlogbuf::s_written = 0;
void androidlogbuf::setLogFile(const char* path) {
    if (s_file != nullptr) { fclose(s_file); s_file = nullptr; }
    s_written = 0;
    if (path != nullptr) {
        // Append across app launches — a later cancelled/aborted start must not truncate away the FIG
        // data a working start captured. The size cap is total (continue from the existing length).
        s_file = fopen(path, "a");
        if (s_file != nullptr) {
            fseek(s_file, 0, SEEK_END);
            long existing = ftell(s_file);
            s_written = existing > 0 ? existing : 0;
            // Guaranteed first line (direct, not via cout) — proves the path is writable.
            fputs("\n=== omri native log start ===\n", s_file);
            fflush(s_file);
            s_written += 31;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "UsbHelperNative", "setLogFile fopen failed: %s", path);
        }
    }
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_installNativeCrashLogger(JNIEnv* env, jobject thiz, jstring path) {
    if (path == nullptr) return;
    const char* p = env->GetStringUTFChars(path, JNI_FALSE);
    installNativeCrashLogger(p);
    __android_log_print(ANDROID_LOG_INFO, "UsbHelperNative", "native crash logger -> %s", p ? p : "(null)");
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_setLogFile(JNIEnv* env, jobject thiz, jstring path) {
    if (path == nullptr) { androidlogbuf::setLogFile(nullptr); return; }
    const char* p = env->GetStringUTFChars(path, JNI_FALSE);
    androidlogbuf::setLogFile(p);
    std::cout << LOG_TAG << " native log tee -> " << (p ? p : "(null)") << std::endl;
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_created(JNIEnv* env, jobject thiz) {
    std::cout << LOG_TAG << " created!" << std::endl;
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_deviceDetached(JNIEnv* env, jobject thiz, jstring deviceName) {
    const char* detachedDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);
    std::cout << LOG_TAG << " device detached: " << detachedDeviceName << std::endl;

    std::string removedDev(detachedDeviceName);

    auto bla = m_dabInputs.begin();
    while(bla < m_dabInputs.end()) {
        if(bla->get()->getDeviceName() == removedDev) {
            std::cout << LOG_TAG << " Removing UsbTunerInput: " << removedDev << " : " << bla->get()->getDeviceName() << std::endl;
            m_dabInputs.erase(bla);
            break;
        }
        ++bla;
    }

    auto devIter = m_usbDevices.cbegin();
    while(devIter != m_usbDevices.cend()) {
        if(devIter->get()->getDeviceName() == removedDev) {
            std::cout << LOG_TAG << " Removing device: " << removedDev << std::endl;
            m_usbDevices.erase(devIter);
            break;
        }
        ++devIter;
    }

    env->ReleaseStringUTFChars(deviceName, detachedDeviceName);
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_deviceAttached(JNIEnv* env, jobject thiz, jobject usbDevice) {
    std::cout << LOG_TAG << " Device attached!" << std::endl;

    std::shared_ptr<JTunerUsbDevice> jusbDevice = std::shared_ptr<JTunerUsbDevice>(new JTunerUsbDevice(m_javaVm, env, usbDevice));

    jusbDevice->setJavaClassUsbTuner(env, m_usbTunerClass);
    jusbDevice->setJavaClassDabService(env, m_dabServiceClass);
    jusbDevice->setJavaClassDabServiceComponent(env, m_dabServiceComponentClass);
    jusbDevice->setJavaClassDabServiceUserApplication(env, m_dabServiceUserApplicationClass);
    jusbDevice->setJavaClassTermId(env, m_termIdClass);

    m_usbDevices.push_back(jusbDevice);

    uint16_t prodId = jusbDevice->getProductId();
    uint16_t vendId = jusbDevice->getVendorId();

    if(vendId == 0x16C0 && prodId == 0x05DC) {
        m_dabInputs.push_back(std::unique_ptr<RaonTunerInput>(new RaonTunerInput(jusbDevice)));
    };
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_devicePermission(JNIEnv* env, jobject thiz, jstring deviceName, jboolean permissionGranted) {
    const char* permissionDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);

    std::string permitDev(permissionDeviceName);
    env->ReleaseStringUTFChars(deviceName, permissionDeviceName);

    std::cout << LOG_TAG << " device permission granted for: " << permitDev << " : " <<  std::boolalpha << static_cast<bool>(permissionGranted) << std::noboolalpha << std::endl;

    if(static_cast<bool>(permissionGranted)) {
        m_readyDevices.insert(permitDev);
    } else {
        m_readyDevices.erase(permitDev);
    }

    auto devIter = m_usbDevices.cbegin();
    while(devIter != m_usbDevices.cend()) {
        std::cout << LOG_TAG << " searching device: " << devIter->get()->getDeviceName() << std::endl;
        if(devIter->get()->getDeviceName() == permitDev) {
            std::cout << LOG_TAG << " Permission device: " << permitDev << std::endl;

            devIter->get()->permissionGranted(env, static_cast<bool>(permissionGranted));
            break;
        }
        ++devIter;
    }
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_startSrv(JNIEnv* env, jobject thiz, jstring deviceName, jobject dabService) {
    const char *cDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);

    std::string devName(cDeviceName);
    env->ReleaseStringUTFChars(deviceName, cDeviceName);

    std::cout << LOG_TAG << " UsbHelper starting service for Device: " << devName << std::endl;

    if(m_readyDevices.find(devName) == m_readyDevices.end()) {
        throwJavaError(env, "DAB-Wiedergabe abgelehnt: Gerät nicht bereit (USB-Berechtigung ausstehend/verweigert): " + devName);
        return;
    }

    auto devIter = m_dabInputs.cbegin();
    while(devIter != m_dabInputs.cend()) {
        if(devIter->get()->getDeviceName() == devName) {
            try {
                (*devIter).get()->startService(std::move(std::shared_ptr<JDabService>(new JDabService(m_javaVm, env, m_dabServiceClass, m_dynamicLabelClass, m_dynamicLabelPlusItemClass, m_slideshowClass, dabService))));
            } catch(const std::exception& e) {
                throwJavaError(env, std::string("DAB-Wiedergabe fehlgeschlagen: ") + e.what());
            } catch(...) {
                throwJavaError(env, "DAB-Wiedergabe fehlgeschlagen (unbekannter nativer Fehler)");
            }
            break;
        }
        devIter++;
    }
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_stopSrv(JNIEnv* env, jobject thiz, jstring deviceName) {
    std::cout << LOG_TAG << " UsbHelper stopping service!" << std::endl;

    const char *cDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);
    std::string devName(cDeviceName);
    env->ReleaseStringUTFChars(deviceName, cDeviceName);

    std::cout << LOG_TAG << " UsbHelper stopping service on device: " << devName << std::endl;

    auto devIter = m_dabInputs.cbegin();
    while(devIter != m_dabInputs.cend()) {
        if(devIter->get()->getDeviceName() == devName) {
            (*devIter).get()->stopAllRunningServices();
            break;
        }
        devIter++;
    }
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_tuneFreq(JNIEnv* env, jobject thiz, jstring deviceName, jlong freq) {
    std::cout << LOG_TAG << " UsbHelper starting service!" << std::endl;

    //TODO
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_startServiceScan(JNIEnv* env, jobject thiz, jstring deviceName) {
    const char *cDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);

    std::string devName(cDeviceName);
    env->ReleaseStringUTFChars(deviceName, cDeviceName);

    std::cout << LOG_TAG << " UsbHelper starting serviceScan on device: " << devName << std::endl;

    if(m_readyDevices.find(devName) == m_readyDevices.end()) {
        throwJavaError(env, "DAB-Suchlauf abgelehnt: Gerät nicht bereit (USB-Berechtigung ausstehend/verweigert): " + devName);
        return;
    }

    auto devIter = m_dabInputs.cbegin();
    while(devIter != m_dabInputs.cend()) {
        if(devIter->get()->getDeviceName() == devName) {
            try {
                (*devIter).get()->startServiceScan();
            } catch(const std::exception& e) {
                throwJavaError(env, std::string("DAB-Suchlauf fehlgeschlagen: ") + e.what());
            } catch(...) {
                throwJavaError(env, "DAB-Suchlauf fehlgeschlagen (unbekannter nativer Fehler)");
            }
            break;
        }
        devIter++;
    }
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_stopServiceScan(JNIEnv* env, jobject thiz, jstring deviceName) {
    const char *cDeviceName = env->GetStringUTFChars(deviceName, JNI_FALSE);

    std::string devName(cDeviceName);
    env->ReleaseStringUTFChars(deviceName, cDeviceName);

    std::cout << LOG_TAG << " UsbHelper stopping serviceScan on device: " << devName << std::endl;

    auto devIter = m_dabInputs.cbegin();
    while(devIter != m_dabInputs.cend()) {
        if(devIter->get()->getDeviceName() == devName) {
            (*devIter).get()->stopServiceScan();
            break;
        }
        devIter++;
    }
}

/* EdiStream -> highly experimental */
std::vector<std::shared_ptr<EdiInput>> m_ediInputs;

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_ediTunerAttached(JNIEnv* env, jobject thiz, jobject ediTuner) {
    std::cout << LOG_TAG << "EdiTuner attached" << std::endl;

    std::shared_ptr<EdiInput> jediTuner = std::shared_ptr<EdiInput>(new EdiInput(m_javaVm, env, ediTuner));
    jediTuner->setJavaClassEdiTuner(env, m_ediTunerClass);
    jediTuner->setJavaClassDabTime(env, m_dabTimeClass);
    jediTuner->setJavaClassDabService(env, m_dabServiceClass);
    jediTuner->setJavaClassDabServiceComponent(env, m_dabServiceComponentClass);
    jediTuner->setJavaClassDabServiceUserApplication(env, m_dabServiceUserApplicationClass);
    jediTuner->setJavaClassTermId(env, m_termIdClass);

    m_ediInputs.push_back(jediTuner);
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_ediTunerDetached(JNIEnv* env, jobject thiz, jobject ediTuner) {
    std::cout << LOG_TAG << "EdiTuner detached" << std::endl;

    //TODO only erase specific tuner instance instead of clear
    m_ediInputs.clear();
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_startEdiStream(JNIEnv* env, jobject thiz, jobject ediTuner, jobject dabService) {
    m_ediInputs[0]->startService(std::move(std::shared_ptr<JDabService>(new JDabService(m_javaVm, env, m_dabServiceClass, m_dynamicLabelClass, m_dynamicLabelPlusItemClass, m_slideshowClass, dabService))));
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_ediStreamData(JNIEnv* env, jobject thiz, jbyteArray dabEdiData, jint size) {
    //std::cout << LOG_TAG << " UsbHelper starting EdistreamService: " << std::endl;
    std::vector<uint8_t> dataArr(size);
    env->GetByteArrayRegion (dabEdiData, 0, size, reinterpret_cast<jbyte*>(dataArr.data()));

    m_ediInputs[0]->ediDataInput(dataArr, size);
}

JNIEXPORT void JNICALL Java_org_omri_radio_impl_UsbHelper_ediFlushBuffer(JNIEnv* env, jobject thiz) {
    std::cout << LOG_TAG << " UsbHelper flushing component data" << std::endl;

    m_ediInputs[0]->flushComponentBuffer();
}

}