// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.run.user;

import static java.util.Objects.requireNonNullElse;
import static cn.classfun.droidvm.lib.utils.StringUtils.streamToString;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.utils.FileUtils;

/**
 * Runs shell commands as the current (non-root) user without su.
 * Used in no-root mode where commands that need root privileges
 * (bridge creation, TAP setup, sysctl writes, etc.) will fail
 * silently or are skipped by the caller.
 */
public final class UserRunContext extends RunContext {
    private static UserRunContext instance = null;
    private static String shell = null;

    private UserRunContext() {
    }

    @NonNull
    public static UserRunContext getContext() {
        synchronized (UserRunContext.class) {
            if (instance == null)
                instance = new UserRunContext();
        }
        return instance;
    }

    @NonNull
    private static String getShell() {
        if (shell != null) return shell;
        var sh = requireNonNullElse(System.getenv("SHELL"), "sh");
        if (!sh.contains("/"))
            sh = FileUtils.findExecute(sh);
        shell = sh;
        return shell;
    }

    @NonNull
    @Override
    protected RunResult runInternal(@NonNull String command) {
        return runSimple(command);
    }

    @NonNull
    private static RunResult runSimple(@NonNull String command) {
        int code = -1;
        String out = "", err;
        try {
            var rt = Runtime.getRuntime();
            var cmd = new String[]{getShell(), "-c", command};
            var process = rt.exec(cmd);
            code = process.waitFor();
            out = streamToString(process.getInputStream());
            err = streamToString(process.getErrorStream());
        } catch (Exception e) {
            err = requireNonNullElse(e.getMessage(), e.toString());
        }
        return new UserRunResult(code, out, err);
    }
}
