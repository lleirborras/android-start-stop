#!/bin/sh
# Runs the plain-Java tests. Needs only a JDK, no Android SDK.
set -eu
cd "$(dirname "$0")"

out=build/test
rm -rf "$out" && mkdir -p "$out"
find test -name '*.java' -exec javac -Xlint:all -Werror -d "$out" src/com/furgo/nostartstop/Protocol.java {} +
java -cp "$out" com.furgo.nostartstop.ProtocolTest
