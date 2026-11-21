#!/usr/bin/env bash
set -euo pipefail

readonly UPSTREAM_URL="https://github.com/WireGuard/wireguard-android.git"
readonly UPSTREAM_TAG="1.0.20260102"
readonly EXPECTED_TAG_OBJECT="3831cab2da844319291459308a6e535d36dde4b3"
readonly EXPECTED_COMMIT="09b75c2bd37f749e2a8c85876394854113c74be7"
readonly WORK_DIR="${RUNNER_TEMP:-/tmp}/molido-wireguard-upstream"
readonly NDK_VERSION="27.2.12479018"
readonly ANDROID_API="26"
readonly SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/ndk/$NDK_VERSION" ]; then
  echo "Pinned Android NDK $NDK_VERSION is not installed."
  exit 1
fi

rm -rf "$WORK_DIR"
git clone --filter=blob:none --no-checkout --no-recurse-submodules "$UPSTREAM_URL" "$WORK_DIR"
git -C "$WORK_DIR" fetch --no-recurse-submodules origin "refs/tags/$UPSTREAM_TAG:refs/tags/$UPSTREAM_TAG"

actual_tag="$(git -C "$WORK_DIR" rev-parse "refs/tags/$UPSTREAM_TAG")"
actual_commit="$(git -C "$WORK_DIR" rev-parse "refs/tags/$UPSTREAM_TAG^{commit}")"
if [ "$actual_tag" != "$EXPECTED_TAG_OBJECT" ] || [ "$actual_commit" != "$EXPECTED_COMMIT" ]; then
  echo "Upstream WireGuard tag object or peeled commit does not match the reviewed pin."
  echo "tag=$actual_tag commit=$actual_commit"
  exit 1
fi
git -C "$WORK_DIR" checkout --detach "$EXPECTED_COMMIT"

# Build the exact upstream Go userspace library directly with its own Makefile.
# This deliberately avoids configuring the upstream Android app/UI Gradle build.
# The GPL wireguard-tools and elf-cleaner submodules are not initialized or built.
readonly NDK_ROOT="$SDK_ROOT/ndk/$NDK_VERSION"
readonly TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
readonly SYSROOT="$TOOLCHAIN/sysroot"
readonly GRADLE_USER_HOME="${RUNNER_TEMP:-/tmp}/molido-gradle-cache"
readonly OUT="$WORK_DIR/native-output"
mkdir -p "$GRADLE_USER_HOME" "$OUT"

build_abi() {
  local abi="$1"
  local arch="$2"
  local target="$3"
  local dest="$OUT/$abi"
  local build="$WORK_DIR/build/$abi"
  mkdir -p "$dest" "$build"

  echo "Building pinned wireguard-go for $abi ($target)"
  make -C "$WORK_DIR/tunnel/tools/libwg-go" \
    ANDROID_ARCH_NAME="$arch" \
    ANDROID_PACKAGE_NAME="com.wireguard.android" \
    GRADLE_USER_HOME="$GRADLE_USER_HOME" \
    CC="$TOOLCHAIN/bin/$target-clang" \
    CFLAGS="-O2 -fPIC" \
    LDFLAGS="-Wl,--build-id=none" \
    SYSROOT="$SYSROOT" \
    TARGET="$target" \
    DESTDIR="$dest" \
    BUILDDIR="$build"

  test -s "$dest/libwg-go.so"
  "$TOOLCHAIN/bin/llvm-readelf" -h "$dest/libwg-go.so" > "$dest/elf-header.txt"
}

build_abi "arm64-v8a" "arm64" "aarch64-linux-android$ANDROID_API"
build_abi "armeabi-v7a" "arm" "armv7a-linux-androideabi$ANDROID_API"
build_abi "x86" "x86" "i686-linux-android$ANDROID_API"
build_abi "x86_64" "x86_64" "x86_64-linux-android$ANDROID_API"

check_machine() {
  local abi="$1"
  local expected="$2"
  if ! grep -Eq "Machine:[[:space:]]+$expected$" "$OUT/$abi/elf-header.txt"; then
    echo "Unexpected ELF machine for $abi"
    cat "$OUT/$abi/elf-header.txt"
    exit 1
  fi
}
check_machine "arm64-v8a" "AArch64"
check_machine "armeabi-v7a" "ARM"
check_machine "x86" "Intel 80386"
check_machine "x86_64" "Advanced Micro Devices X86-64"

mkdir -p "$OUT/package/jni"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  mkdir -p "$OUT/package/jni/$abi"
  cp "$OUT/$abi/libwg-go.so" "$OUT/package/jni/$abi/"
done
(
  cd "$OUT/package"
  zip -qr "$WORK_DIR/wireguard-go-only-native.zip" jni
)
entries="$(unzip -Z1 "$WORK_DIR/wireguard-go-only-native.zip")"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  printf '%s\n' "$entries" | grep -Fxq "jni/$abi/libwg-go.so"
done
if printf '%s\n' "$entries" | grep -Eq 'libwg(-quick)?\.so$'; then
  echo "The Go-only native package unexpectedly contains GPL command-line libraries."
  exit 1
fi

echo "PASS: pinned upstream wireguard-go built for all four Android ABIs."
echo "PASS: ELF machine headers match the requested Android ABIs."
echo "PASS: native package contains only libwg-go.so; no wireguard-tools binaries."
