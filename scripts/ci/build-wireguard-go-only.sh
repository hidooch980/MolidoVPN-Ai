#!/usr/bin/env bash
set -euo pipefail

readonly UPSTREAM_URL="https://github.com/WireGuard/wireguard-android.git"
readonly UPSTREAM_TAG="1.0.20260102"
readonly EXPECTED_TAG_OBJECT="3831cab2da844319291459308a6e535d36dde4b3"
readonly EXPECTED_COMMIT="09b75c2bd37f749e2a8c85876394854113c74be7"
readonly WORK_DIR="${RUNNER_TEMP:-/tmp}/molido-wireguard-upstream"

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

# Build only the userspace Go engine used by Molido's GoBackend adapter.
# The upstream wireguard-tools (GPL-2) and elf-cleaner (GPL-3.0-or-later)
# submodules are intentionally not initialized or packaged by this smoke test.
cat > "$WORK_DIR/tunnel/tools/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.4.1)
project("WireGuardGoOnly")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_custom_target(libwg-go.so
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go"
    COMMENT "Building pinned wireguard-go userspace library"
    VERBATIM
    COMMAND "make"
        ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
        ANDROID_PACKAGE_NAME=${ANDROID_PACKAGE_NAME}
        GRADLE_USER_HOME=${GRADLE_USER_HOME}
        CC=${CMAKE_C_COMPILER}
        CFLAGS=${CMAKE_C_FLAGS}
        LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
        SYSROOT=${CMAKE_SYSROOT}
        TARGET=${CMAKE_C_COMPILER_TARGET}
        DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        BUILDDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/../generated-src
)
CMAKE

python3 - "$WORK_DIR/tunnel/build.gradle.kts" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = 'targets("libwg-go.so", "libwg.so", "libwg-quick.so")'
new = 'targets("libwg-go.so")'
if text.count(old) != 1:
    raise SystemExit("Expected exactly one upstream native target declaration")
path.write_text(text.replace(old, new))
PY

cd "$WORK_DIR"
build_log="$WORK_DIR/molido-go-only-build.log"
build_succeeded=0
for attempt in 1 2 3; do
  echo "WireGuard source build attempt $attempt/3"
  if ./gradlew --no-daemon :tunnel:assembleRelease 2>&1 | tee "$build_log"; then
    build_succeeded=1
    break
  else
    build_status=$?
  fi

  # Retry only transient dependency/repository transport failures. Never retry
  # compilation, CMake, source, assertion, or packaging failures.
  if grep -Eqi 'HTTP 429|status code: 429|Received status code 429|HTTP 5[0-9][0-9]|status code: 5[0-9][0-9]|Temporary failure in name resolution|Connection reset|Read timed out|Connection timed out' "$build_log"; then
    if [ "$attempt" -lt 3 ]; then
      delay=$((attempt * 20))
      echo "Transient repository/network failure detected; retrying after ${delay}s."
      sleep "$delay"
      continue
    fi
  fi
  exit "$build_status"
done
if [ "$build_succeeded" -ne 1 ]; then
  echo "WireGuard Go-only source build did not succeed after bounded retries."
  exit 1
fi

aar="$WORK_DIR/tunnel/build/outputs/aar/tunnel-release.aar"
test -s "$aar"
entries="$(unzip -Z1 "$aar")"
printf '%s\n' "$entries" | grep -Eq '^jni/[^/]+/libwg-go\.so$' || {
  echo "The built AAR does not contain the expected libwg-go.so native library."
  exit 1
}
if printf '%s\n' "$entries" | grep -Eq '^jni/[^/]+/libwg(-quick)?\.so$'; then
  echo "The Go-only AAR unexpectedly contains GPL command-line native libraries."
  exit 1
fi
echo "PASS: pinned upstream tunnel module builds and packages libwg-go.so only."
