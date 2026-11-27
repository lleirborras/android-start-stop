#!/bin/sh
# Runs the plain-Java tests. Needs only a JDK, no Android SDK.
set -eu
cd "$(dirname "$0")"

out=build/test
rm -rf "$out" && mkdir -p "$out"
find test -name '*.java' -exec javac -Xlint:all -Werror -d "$out" \
    src/com/furgo/nostartstop/Protocol.java src/com/furgo/nostartstop/Version.java \
    src/com/furgo/nostartstop/BootId.java src/com/furgo/nostartstop/UpdateCheck.java \
    src/com/furgo/nostartstop/WakeRules.java src/com/furgo/nostartstop/AutoSwitch.java \
    src/com/furgo/nostartstop/LogBudget.java src/com/furgo/nostartstop/TelemetryStore.java \
    src/com/furgo/nostartstop/PowerWatch.java src/com/furgo/nostartstop/AssumedState.java \
    src/com/furgo/nostartstop/ManualSwitch.java src/com/furgo/nostartstop/Feedback.java \
    src/com/furgo/nostartstop/ShareStatus.java src/com/furgo/nostartstop/WatchDecisions.java \
    src/com/furgo/nostartstop/BootFlow.java src/com/furgo/nostartstop/OtherUnits.java \
    src/com/furgo/nostartstop/CarProfile.java {} +
java -cp "$out" com.furgo.nostartstop.ProtocolTest
java -cp "$out" com.furgo.nostartstop.OtherUnitsTest
java -cp "$out" com.furgo.nostartstop.CarProfileTest
java -cp "$out" com.furgo.nostartstop.BootIdTest
java -cp "$out" com.furgo.nostartstop.WakeRulesTest
java -cp "$out" com.furgo.nostartstop.AutoSwitchTest
java -cp "$out" com.furgo.nostartstop.ManualSwitchTest
java -cp "$out" com.furgo.nostartstop.FeedbackTest
java -cp "$out" com.furgo.nostartstop.ShareStatusTest
java -cp "$out" com.furgo.nostartstop.PowerWatchTest
java -cp "$out" com.furgo.nostartstop.AssumedStateTest
java -cp "$out" com.furgo.nostartstop.WatchDecisionsTest
java -cp "$out" com.furgo.nostartstop.BootFlowTest
java -cp "$out" com.furgo.nostartstop.LogBudgetTest
java -cp "$out" com.furgo.nostartstop.VersionTest
java -cp "$out" com.furgo.nostartstop.UpdateCheckTest
java -cp "$out" com.furgo.nostartstop.TelemetryStoreTest

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

# version-code.sh: codes follow the tag order, a beta below its own release.
code() {
    got=$(sh scripts/version-code.sh "$1")
    if [ "$got" != "$2" ]; then
        echo "version-code.sh $1: expected '$2' but got '$got'" >&2
        exit 1
    fi
}
code v0.20.0 200099
code v0.21.0-beta.3 210003
code v1.0.0 10000099
code v0.20.10 201099
for bad in v0.20 v0.20.0-beta.0 v0.20.0-beta.99 v0.20.0-beta.x v0.1000.0 v0.20.100 v214.0.0; do
    if sh scripts/version-code.sh "$bad" 2>/dev/null; then
        echo "version-code.sh accepted $bad" >&2
        exit 1
    fi
done
last=0
for t in v0.20.0 v0.20.1-beta.1 v0.20.1-beta.2 v0.20.1 v0.21.0-beta.1 v0.21.0-beta.98 v0.21.0 v1.0.0-beta.1 v1.0.0; do
    c=$(sh scripts/version-code.sh "$t")
    if [ "$c" -le "$last" ]; then
        echo "version-code.sh: $t ($c) is not above the tag before it ($last)" >&2
        exit 1
    fi
    last=$c
done

# beta-tag.sh: <branch> <latest stable> <tags> -> <expected beta tag>
beta() {
    got=$(printf '%b' "$3" | sh scripts/beta-tag.sh "$1" "$2")
    if [ "$got" != "$4" ]; then
        echo "beta-tag.sh $1 $2: expected '$4' but got '$got'" >&2
        exit 1
    fi
}
beta feat/x v0.20.0 'v0.20.0\n' v0.21.0-beta.1
beta feat/x v0.20.0 'v0.21.0-beta.1\nv0.21.0-beta.2\nv0.20.1-beta.7\n' v0.21.0-beta.3
beta fix/x v0.20.0 'v0.21.0-beta.4\n' v0.20.1-beta.1
beta refactor/x v0.20.0 '' v0.20.1-beta.1
beta feat/x v0.20.0 'v0.21.0-beta.9\nv0.21.0-beta.10\nv0x21y0-beta.50\n' v0.21.0-beta.11
echo "version-code.sh and beta-tag.sh tests passed"
