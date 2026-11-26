// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.run.root;

import androidx.annotation.NonNull;

import java.util.Arrays;

import cn.classfun.droidvm.lib.run.RunResult;

/**
 * RunResult for commands executed as root through {@code su -c} (see {@link RootRunContext}).
 */
public final class ProcessRunResult extends RunResult {
    private final int code;
    private final String out;
    private final String err;

    ProcessRunResult(int code, @NonNull String out, @NonNull String err) {
        this.code = code;
        this.out = out;
        this.err = err;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public Iterable<String> getOut() {
        return Arrays.asList(out.split("\n", -1));
    }

    @Override
    public Iterable<String> getErr() {
        return Arrays.asList(err.split("\n", -1));
    }
}
