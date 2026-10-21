# SPDX-License-Identifier: Apache-2.0
# Build only a disposable test server; it is never included in a NAR.
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl cmake gcc libc6-dev make python3 libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN curl -fsSL https://codeload.github.com/open62541/open62541/tar.gz/refs/tags/v1.5.9 -o source.tar.gz \
    && echo '610aa4db6d4d1a818be128b2889369df3acb9e769aaa18dcc6f4f97e6fbdc9a7  source.tar.gz' | sha256sum -c - \
    && tar -xf source.tar.gz \
    && cmake -S open62541-1.5.9 -B build -DUA_ENABLE_ENCRYPTION=OPENSSL \
       -DUA_NAMESPACE_ZERO=REDUCED -DUA_ENABLE_PUBSUB=OFF -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j 2
COPY open62541.c /build/server.c
RUN gcc -std=c99 -D_DEFAULT_SOURCE -O2 -flto=2 \
    -I open62541-1.5.9/include -I open62541-1.5.9/plugins/include -I build/src_generated \
    server.c build/bin/libopen62541.a -lssl -lcrypto -lpthread -lm -o /build/open62541
CMD ["cp", "/build/open62541", "/output/open62541"]
