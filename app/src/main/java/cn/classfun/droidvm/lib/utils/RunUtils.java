// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.utils;

import static java.util.Objects.requireNonNullElse;

import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.run.root.RootRunContext;
import cn.classfun.droidvm.lib.run.system.SystemRunContext;
import cn.classfun.droidvm.lib.run.user.UserRunContext;

public final class RunUtils {
    private RunUtils() {
    }

    /** When true, commands run as the current user without su. */
    private static volatile boolean noRootMode = false;

    /**
     * Enable or disable no-root mode. When enabled, all shell commands
     * run as the current user via UserRunContext instead of routing
     * through su. Commands that need root (bridge creation, TAP setup,
     * sysctl writes, etc.) will fail — callers should check isNoRootMode()
     * and skip privileged operations.
     */
    public static void setNoRootMode(boolean enabled) {
        noRootMode = enabled;
    }

    /** Whether no-root mode is currently active. */
    public static boolean isNoRootMode() {
        return noRootMode;
    }

    @NonNull
    private static RunContext getContext() {
        if (noRootMode)
            return UserRunContext.getContext();
        return (Os.getuid() == 0) ?
            SystemRunContext.getContext() :
            RootRunContext.getContext();
    }

    @NonNull
    public static List<String> outStringToList(@Nullable String s) {
        return Arrays.asList(requireNonNullElse(s, "").split("\n"));
    }

    @NonNull
    public static String escapedString(@NonNull String s) {
        var sb = new StringBuilder();
        sb.append('\'');
        int len = s.length();
        for (int i = 0; i < len; ++i) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
                continue;
            }
            sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult run(@NonNull String command) {
        return getContext().run(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult run(@NonNull String format, Object... args) {
        return getContext().run(format, args);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runList(@NonNull Iterable<String> command) {
        return getContext().runList(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runList(@NonNull String... command) {
        return getContext().runList(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runQuiet(@NonNull String command) {
        return getContext().runQuiet(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runQuiet(@NonNull String format, Object... args) {
        return getContext().runQuiet(format, args);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runListQuiet(@NonNull Iterable<String> command) {
        return getContext().runListQuiet(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runListQuiet(@NonNull String... command) {
        return getContext().runListQuiet(command);
    }
}
