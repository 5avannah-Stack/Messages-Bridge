package signalbridge

// libsignalgo links -lsignal_ffi; these say where libsignal.sh put it for each target, so one gomobile bind finds the
// right library for every ABI. C++ comes from the NDK's static libc++, which libsignal's BoringSSL leaves to this link.
// libsignalgo also asks for -lstdc++, which the NDK's clang turns into its shared libc++, a library the app doesn't
// carry and nothing here uses; --as-needed keeps it out of libgojni.so's dependencies.

// #cgo android,arm64 LDFLAGS: -L${SRCDIR}/../.libsignal/aarch64-linux-android
// #cgo android,amd64 LDFLAGS: -L${SRCDIR}/../.libsignal/x86_64-linux-android
// #cgo android LDFLAGS: -lc++_static -lc++abi -Wl,--as-needed
// #cgo linux,!android LDFLAGS: -L${SRCDIR}/../.libsignal/host
import "C"
