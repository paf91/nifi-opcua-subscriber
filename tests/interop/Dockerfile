FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl cmake gcc libc6-dev make python3 libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY build.sh open62541.c /build/
RUN bash /build/build.sh /build/open62541
CMD ["cp", "/build/open62541", "/output/open62541"]
