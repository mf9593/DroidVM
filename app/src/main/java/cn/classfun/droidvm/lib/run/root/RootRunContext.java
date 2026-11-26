// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.run.root;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;

/**
 * Runs one command as root through {@code /system/bin/su -c}.
 *
 * <p>This used to go through libsu's {@code Shell}, which keeps a long-lived root shell and
 * is the right tool when a caller wants a session. The in-process daemon does not: its calls
 * are independent one-shots (insmod, netbox, sysctl, iptables), and on this ROM libsu's shell
 * reported a plain {@code exit=1} with no output for commands that succeed through a fresh
 * {@code su -c} (netbox's bridge creation is the concrete one). The daemon's other privileged
 * paths -- crosvm and the network helpers -- already spawn {@code su} directly and work, so
 * this does the same and keeps every root command on one mechanism.</p>
 *
 * <p>One process per call costs a fork, which is what every call already paid for the helper
 * it was about to run.</p>
 */
public final class RootRunContext extends RunContext {
    private static final String SU = "/system/bin/su";
    private static RootRunContext instance = null;

    private RootRunContext() {
    }

    @NonNull
    public static RunContext getContext() {
        synchronized (RootRunContext.class) {
            if (instance == null)
                instance = new RootRunContext();
        }
        return instance;
    }

    @NonNull
    @Override
    protected RunResult runInternal(@NonNull String command) {
        int code = -1;
        String out = "";
        String err;
        try {
            var process = new ProcessBuilder(SU, "-c", command).start();
            // Both streams must be drained while the child runs: a command that writes more
            // than a pipe buffer to the stream nobody is reading would block forever.
            var stdout = CompletableFuture.supplyAsync(
                () -> readAll(process.getInputStream()));
            var stderr = CompletableFuture.supplyAsync(
                () -> readAll(process.getErrorStream()));
            code = process.waitFor();
            out = stdout.join();
            err = stderr.join();
        } catch (Exception e) {
            err = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return new ProcessRunResult(code, out, err);
    }

    @NonNull
    private static String readAll(@NonNull InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
