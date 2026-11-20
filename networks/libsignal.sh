#!/usr/bin/env bash
# Builds libsignal-ffi, Signal's Rust library with its C interface, which signalmeow links (networks/signalbridge).
# The version is the one mautrix-signal's libsignalgo was generated against (signalbridge/libsignal.version, checked
# by signalbridge/version_test.go). Each target's static library lands in .libsignal/<target>/, where
# signalbridge/link.go finds it.
#
#   ./libsignal.sh aarch64-linux-android [x86_64-linux-android] [host]
#
# Needs rustup (the checkout's rust-toolchain picks the compiler), protoc on PATH or in PROTOC, cmake and ninja on PATH,
# libclang for bindgen, a host C and C++ compiler, and ANDROID_NDK_HOME (its clang for Android, its llvm-objcopy for
# every target). The first build takes a while; later ones reuse the checkout and its build, in .libsignal/src or
# LIBSIGNAL_SRC, which should be on a local disk: Cargo on a network share is slow.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
version=$(tr -d '[:space:]' < "$here/signalbridge/libsignal.version")
src=${LIBSIGNAL_SRC:-$here/.libsignal/src}

if [ "$(git -C "$src" describe --tags --exact-match 2>/dev/null || true)" != "$version" ]; then
    rm -rf "$src"
    git clone --quiet --depth 1 --branch "$version" https://github.com/signalapp/libsignal.git "$src"
fi
cd "$src"

# Size over speed, as Signal's Android build does. OPENSSL_SMALL (smaller curve tables) is for Android only: it also
# compiles out ring code that the host's x86_64 build calls.
export CARGO_PROFILE_RELEASE_OPT_LEVEL=s CARGO_PROFILE_RELEASE_LTO=fat CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
export RUSTFLAGS="--cfg curve25519_dalek_bits=\"64\" -Ctarget-feature=-crt-static ${RUSTFLAGS:-}"
export RUSTC_WRAPPER="" CMAKE_GENERATOR=Ninja

objcopy=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-objcopy)

for target in "$@"; do
    if [ "$target" = host ]; then
        cargo build -p libsignal-ffi --release --locked
        built=target/release
    else
        rustup target add "$target" >/dev/null
        # The NDK's clang builds BoringSSL and links; static libc++ is left for the final link (Go's), as Signal does.
        tc=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
        t=${target//-/_}
        env "CC_$t=$tc/${target}29-clang" "CXX_$t=$tc/${target}29-clang++" "AR_$t=$tc/llvm-ar" \
            "CFLAGS=-DOPENSSL_SMALL ${CFLAGS:-}" "CXXFLAGS=-DOPENSSL_SMALL ${CXXFLAGS:-}" \
            "CARGO_TARGET_${t^^}_LINKER=$tc/${target}29-clang" BORING_BSSL_RUST_CPPLIB="static:-bundle=c++" \
            cargo build -p libsignal-ffi --release --locked --target "$target"
        built=target/$target/release
    fi
    # Rust's prebuilt compiler_builtins carries embedded LLVM bitcode (.llvmbc), which Go's cgo refuses in a library it
    # links. Nothing reads it in a normal link, so it goes.
    mkdir -p "$here/.libsignal/$target"
    "$objcopy" --remove-section=.llvmbc --remove-section=.llvmcmd "$built/libsignal_ffi.a" "$here/.libsignal/$target/libsignal_ffi.a"
done
