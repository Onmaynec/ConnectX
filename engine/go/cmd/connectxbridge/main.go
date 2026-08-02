package main

/*
#include <jni.h>
#include <stdlib.h>

static jstring connectxNewString(JNIEnv* env, const char* value) {
    return (*env)->NewStringUTF(env, value);
}

static const char* connectxGetString(JNIEnv* env, jstring value) {
    return (*env)->GetStringUTFChars(env, value, NULL);
}

static void connectxReleaseString(JNIEnv* env, jstring value, const char* chars) {
    (*env)->ReleaseStringUTFChars(env, value, chars);
}
*/
import "C"

import (
	"sync"
	"unsafe"

	"github.com/Onmaynec/ConnectX/engine/go/bridge"
)

var (
	errorMu   sync.Mutex
	lastError string
)

func setLastError(err error) {
	errorMu.Lock()
	defer errorMu.Unlock()
	if err == nil {
		lastError = ""
		return
	}
	lastError = err.Error()
}

func getLastError() string {
	errorMu.Lock()
	defer errorMu.Unlock()
	return lastError
}

func newJavaString(env *C.JNIEnv, value string) C.jstring {
	chars := C.CString(value)
	defer C.free(unsafe.Pointer(chars))
	return C.connectxNewString(env, chars)
}

func readJavaString(env *C.JNIEnv, value C.jstring) string {
	chars := C.connectxGetString(env, value)
	if chars == nil {
		return ""
	}
	defer C.connectxReleaseString(env, value, chars)
	return C.GoString(chars)
}

//export Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeVersion
func Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeVersion(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	_ = clazz
	return newJavaString(env, bridge.Version())
}

//export Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeStart
func Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeStart(
	env *C.JNIEnv,
	clazz C.jclass,
	tunFD C.jint,
	mtu C.jint,
	host C.jstring,
	port C.jint,
	username C.jstring,
	password C.jstring,
) C.jint {
	_ = clazz
	code, err := bridge.Start(
		int(tunFD),
		int(mtu),
		readJavaString(env, host),
		int(port),
		readJavaString(env, username),
		readJavaString(env, password),
	)
	setLastError(err)
	return C.jint(code)
}

//export Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeStop
func Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeStop(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jint {
	_ = env
	_ = clazz
	err := bridge.Stop()
	setLastError(err)
	if err != nil {
		return 1
	}
	return 0
}

//export Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeIsRunning
func Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeIsRunning(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jboolean {
	_ = env
	_ = clazz
	if bridge.IsRunning() {
		return C.jboolean(1)
	}
	return C.jboolean(0)
}

//export Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeLastError
func Java_dev_connectx_vpn_nativebridge_NativeTunBridge_nativeLastError(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	_ = clazz
	return newJavaString(env, getLastError())
}

func main() {}
