#!/bin/sh
# Runs the plain-Java tests. Needs only a JDK, no Android SDK.
set -eu
cd "$(dirname "$0")"

out=build/test
rm -rf "$out" && mkdir -p "$out"
find test -name '*.java' -exec javac -Xlint:all -Werror -d "$out" \
    src/com/furgo/nostartstop/Protocol.java src/com/furgo/nostartstop/Version.java \
    src/com/furgo/nostartstop/BootId.java src/com/furgo/nostartstop/UpdateCheck.java \
    src/com/furgo/nostartstop/WakeRules.java src/com/furgo/nostartstop/AutoSwitch.java {} +
java -cp "$out" com.furgo.nostartstop.ProtocolTest
java -cp "$out" com.furgo.nostartstop.BootIdTest
java -cp "$out" com.furgo.nostartstop.WakeRulesTest
java -cp "$out" com.furgo.nostartstop.AutoSwitchTest
java -cp "$out" com.furgo.nostartstop.VersionTest
java -cp "$out" com.furgo.nostartstop.UpdateCheckTest

# next-version.sh: <branch> <latest tag> -> <expected tag, empty for no release>
expect() {
    got=$(sh scripts/next-version.sh "$1" "$2")
    if [ "$got" != "$3" ]; then
        echo "next-version.sh $1 $2: expected '$3' but got '$got'" >&2
        exit 1
    fi
}
expect fix/boot-retry v0.1.1 v0.1.2
expect perf/faster-query v0.1.9 v0.1.10
expect feat/acc-wake v0.1.1 v0.2.0
expect feature/acc-wake v1.4.7 v1.5.0
expect breaking/new-protocol v0.9.3 v1.0.0
expect major/new-protocol v2.3.4 v3.0.0
expect ci/add-lint v0.1.1 ""
expect docs/readme v0.1.1 ""
expect chore/cleanup v0.1.1 ""
expect renovate/actions-checkout-8.x v0.1.1 ""
expect fix v0.1.1 ""
expect feat/first "" v0.1.0
if sh scripts/next-version.sh fix/x not-a-tag 2>/dev/null; then
    echo "next-version.sh accepted a malformed tag" >&2
    exit 1
fi
if sh scripts/next-version.sh fix/x v1.2 2>/dev/null; then
    echo "next-version.sh accepted a two-part tag" >&2
    exit 1
fi
echo "next-version.sh tests passed"
