#!/usr/bin/env bash
# Builds the OpenBill Server release files from server/build/libs (run `./gradlew :server:fatJar` first):
#   <out>/openbill-server-<version>.jar                      runs on any Java 17+
#   <out>/openbill-server-<version>-linux-<arch>.tar.gz      with its own jlink runtime (no Java needed)
# The runtime is for the machine this runs on: run once per architecture (CI uses x64 and arm64 runners).
# Usage: scripts/package-server.sh <version> <x64|arm64> [out-dir]
set -euo pipefail

version=${1:?version}
arch=${2:?arch: x64 or arm64}
out=${3:-server/build/release}
# From jdeps on the JAR, plus EC crypto for TLS.
modules=java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,jdk.jfr,jdk.unsupported,jdk.crypto.ec

jars=(server/build/libs/openbill-server-*-all.jar)
[ "${#jars[@]}" -eq 1 ] && [ -f "${jars[0]}" ] || { echo "Expected one server/build/libs/openbill-server-*-all.jar" >&2; exit 1; }
name="openbill-server-${version}-linux-${arch}"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

mkdir -p "$stage/$name/bin" "$stage/$name/lib" "$stage/$name/deploy" "$out"
jlink --add-modules "$modules" --strip-debug --no-man-pages --no-header-files --compress=2 --output "$stage/$name/runtime"
cp "${jars[0]}" "$stage/$name/lib/openbill-server.jar"
install -m 0755 server/deploy/openbill-server "$stage/$name/bin/openbill-server"
cp server/deploy/openbill-server.service server/deploy/docker-compose.yml server/deploy/Caddyfile "$stage/$name/deploy/"
cp LICENSE NOTICE "$stage/$name/"
"$stage/$name/bin/openbill-server" version | grep -F "openbill-server ${version#v}"
tar -C "$stage" -czf "$out/$name.tar.gz" "$name"
cp "${jars[0]}" "$out/openbill-server-${version}.jar"
ls -l "$out"
