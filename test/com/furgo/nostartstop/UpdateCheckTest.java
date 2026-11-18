package com.furgo.nostartstop;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class UpdateCheckTest {
    private static final long HOUR = 60 * 60 * 1000L;

    public static void main(String[] args) {
        long now = 1_700_000_000_000L;
        check(UpdateCheck.due(now, 0), "never checked");
        check(UpdateCheck.due(now, now - 6 * HOUR), "checked 6 h ago");
        check(UpdateCheck.due(now, now - 30 * HOUR), "checked 30 h ago");
        check(!UpdateCheck.due(now, now - 6 * HOUR + 1), "checked just under 6 h ago");
        check(!UpdateCheck.due(now, now - 1), "checked a moment ago");
        check(UpdateCheck.due(now, now + HOUR), "check time in the future");

        check(UpdateCheck.validTag("v0.9.1"), "v0.9.1");
        check(UpdateCheck.validTag("v10.200.3000"), "v10.200.3000");
        check(!UpdateCheck.validTag(null), "null tag");
        check(!UpdateCheck.validTag(""), "empty tag");
        check(!UpdateCheck.validTag("0.9.1"), "tag without v");
        check(!UpdateCheck.validTag("v0.9"), "two-part tag");
        check(!UpdateCheck.validTag("v0.9.1-beta"), "suffixed tag");
        check(!UpdateCheck.validTag("v0.9.1\n"), "tag with newline");
        check(!UpdateCheck.validTag("v0.9.1/../x"), "tag with path");
        check(!UpdateCheck.validTag("v1234567.0.0"), "overlong part");

        check("v0.9.2".equals(UpdateCheck.offerable("v0.9.2", "0.9.1")), "newer tag offered");
        check(UpdateCheck.offerable("v0.9.1", "0.9.1") == null, "same version not offered");
        check(UpdateCheck.offerable("v0.9.2", "0.9.3") == null, "older tag not offered");
        check(UpdateCheck.offerable("0.9.2", "0.9.1") == null, "invalid tag not offered");
        check(UpdateCheck.offerable("v0.9.2", "dev") == null, "dev build not offered");
        check(UpdateCheck.offerable(null, "0.9.1") == null, "nothing stored");

        check(UpdateCheck.shouldNotify("v0.9.2", "0.9.1", null), "first notification");
        check(UpdateCheck.shouldNotify("v0.9.3", "0.9.1", "v0.9.2"), "newer tag notifies again");
        check(!UpdateCheck.shouldNotify("v0.9.2", "0.9.1", "v0.9.2"), "same tag not notified twice");
        check(!UpdateCheck.shouldNotify("v0.9.1", "0.9.1", null), "up to date not notified");

        check("no internet".equals(UpdateCheck.reason(new UnknownHostException("api.github.com"))), "offline");
        check("no internet".equals(UpdateCheck.reason(new ConnectException("refused"))), "refused");
        check("timed out".equals(UpdateCheck.reason(new SocketTimeoutException("x"))), "timeout");
        check("GitHub answered 403".equals(UpdateCheck.reason(new IOException("GitHub answered 403"))), "http status");
        check("IllegalStateException: a b".equals(UpdateCheck.reason(new IllegalStateException("a\nb"))), "control chars");
        check(UpdateCheck.reason(new IOException(new String(new char[200]).replace('\0', 'x'))).length() == 80, "length cap");

        System.out.println("UpdateCheckTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
