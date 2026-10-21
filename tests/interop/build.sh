#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Requires curl, cmake, gcc, make, Python 3 and OpenSSL development headers.
set -euo pipefail
fixture_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
output="$(realpath -m -- "${1:?Usage: build.sh OUTPUT_BINARY}")"
build_dir="$(mktemp -d)"
trap 'rm -rf -- "$build_dir"' EXIT
cd "$build_dir"
curl --retry 3 -fsSL https://codeload.github.com/open62541/open62541/tar.gz/refs/tags/v1.5.9 -o source.tar.gz
echo '610aa4db6d4d1a818be128b2889369df3acb9e769aaa18dcc6f4f97e6fbdc9a7  source.tar.gz' | sha256sum -c -
tar -xf source.tar.gz
cmake -S open62541-1.5.9 -B build -DUA_ENABLE_ENCRYPTION=OPENSSL \
    -DUA_NAMESPACE_ZERO=REDUCED -DUA_ENABLE_PUBSUB=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build -j2
mkdir -p -- "$(dirname -- "$output")"
gcc -std=c99 -D_DEFAULT_SOURCE -O2 -flto=2 \
    -I open62541-1.5.9/include -I open62541-1.5.9/plugins/include -I build/src_generated \
    "$fixture_dir/open62541.c" build/bin/libopen62541.a -lssl -lcrypto -lpthread -lm -o "$output"
