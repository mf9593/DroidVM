// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.run.user;

import androidx.annotation.NonNull;

import java.util.Arrays;

import cn.classfun.droidvm.lib.run.RunResult;

/**
 * RunResult for commands executed as non-root via UserRunContext.
 */
public final class UserRunResult extends RunResult {
    private final int code;
    private final String out;
    private final String err;

    UserRunResult(int code, String out, String err) {
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
        return Arrays.asList(out.split("\n"));
    }

    @Override
    public Iterable<String> getErr() {
        return Arrays.asList(err.split("\n"));
    }
}
