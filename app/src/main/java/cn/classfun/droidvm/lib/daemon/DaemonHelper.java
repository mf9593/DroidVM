// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.daemon;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGINT;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.isPidExists;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcessFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import java.util.HashMap;
import java.io.File;

import cn.classfun.droidvm.lib.utils.RunUtils;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleepOrKill;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.Daemon;
import cn.classfun.droidvm.lib.ui.UIContext;

public final class DaemonHelper {
    private static final String TAG = "DaemonHelper";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final UIContext ctx;
    private Consumer<Boolean> onRefreshDaemonStatus = null;

    public DaemonHelper(@NonNull UIContext ctx) {
        this.ctx = ctx;
    }

    @NonNull
    public static String getPidFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd.pid");
    }

    @NonNull
    public static String getTokenFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd-token.txt");
    }

    @NonNull
    public static String getPortFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd-port.txt");
    }

    public static int readPort() {
        var portStr = shellReadFile(getPortFile()).trim();
        if (portStr.isEmpty())
            throw new IllegalStateException("Port file is empty");
        var port = parseInt(portStr);
        if (port <= 0 || port > 65535)
            throw new NumberFormatException("Port file has invalid port");
        return port;
    }

    @NonNull
    public String getDaemonZipPath() {
        return ctx.getContext().getApplicationContext().getPackageCodePath();
    }

    public static int readPid() {
        try {
            var pid = parseInt(shellReadFile(getPidFile()).trim());
            if (isPidExists(pid)) {
                try {
                    var comm = shellReadFile(fmt("/proc/%d/comm", pid)).trim();
                    if (comm.equals("droidvmd")) return pid;
                    Log.i(TAG, fmt("proctitle mismatch: %s", comm));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read proctitle", e);
                }
                try {
                    var comm = shellReadFile(fmt("/proc/%d/cmdline", pid));
                    if (comm.contains(Daemon.class.getName())) return pid;
                    Log.i(TAG, fmt("cmdline mismatch: %s", comm.replace('\0', ' ')));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read cmdline", e);
                }
                Log.w(TAG, "pid file maybe stale");
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean isDaemonRunning() {
        return Daemon.isRunningInProcess() || readPid() != -1;
    }

    @Nullable
    public static String readToken() {
        try {
            var token = shellReadFile(getTokenFile()).trim();
            if (token.length() == 32) return token;
            Log.w(TAG, "Token file has invalid length");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read token file", e);
            return null;
        }
    }

    public boolean startDaemon() {
        return startDaemon(false);
    }

    /**
     * Launches the daemon. The daemon takes a single-instance lock at startup,
     * so a normal ({@code force == false}) launch is a no-op when one is
     * already running. With {@code force}, the new daemon terminates the
     * running one and takes over -- used for restart.
     */
    public boolean startDaemon(boolean force) {
        Log.i(TAG, fmt("Starting daemon (force=%b)", force));
        var runDir = pathJoin(DATA_DIR, "run");
        var uid = Process.myUid();
        run("mkdir -p %s", runDir);
        // Best-effort ownership fix; chown may be denied on some contexts (e.g. Android).
        run("chown %d:%d %s || true", uid, uid, runDir);

        // Always start daemon in-process. The su -c app_process64 approach
        // does not work on Android 17 - app_process64 exits without running
        // the Java main() method. The daemon runs as the app's UID but can
        // execute root commands via RunUtils.run() (libsu) for privileged
        // operations like TAP creation, iptables, and kernel module loading.
        // /dev/gunyah has 666 permissions so crosvm can access it without root.
        if (Daemon.isRunningInProcess()) {
            Log.i(TAG, "Daemon already running in-process, skipping start");
            return true;
        }
        Log.i(TAG, "Starting daemon in-process (root mode: " + !RunUtils.isNoRootMode() + ")");
        cleanupStaleFiles();
        var ok = Daemon.startInProcess(force);
        if (ok) DaemonConnection.getInstance().connect();
        return ok;
    }

    public boolean stopDaemon() {
        Log.i(TAG, "Stopping daemon");
        // Always use stopInProcess since the daemon runs in-process
        // (app_process64 approach doesn't work on Android 17)
        if (Daemon.isRunningInProcess()) {
            Daemon.debugLog("stopDaemon: calling stopInProcess");
            Daemon.stopInProcess();
        } else {
            Daemon.debugLog("stopDaemon: not running in-process, cleaning stale files");
            cleanupStaleFiles();
        }
        DaemonConnection.getInstance().disconnect();
        return true;
    }

    private void cleanupStaleFiles() {
        var runDir = pathJoin(DATA_DIR, "run");
        var pidFile = new File(runDir, "droidvmd.pid");
        var tokenFile = new File(runDir, "droidvmd-token.txt");
        var portFile = new File(runDir, "droidvmd-port.txt");
        var lockFile = new File(runDir, "droidvmd.lock");
        if (pidFile.exists()) { Log.i(TAG, "Cleaning stale PID file"); pidFile.delete(); }
        if (tokenFile.exists()) tokenFile.delete();
        if (portFile.exists()) portFile.delete();
        if (lockFile.exists()) lockFile.delete();
    }

    public boolean restartDaemon() {
        Log.i(TAG, "Restarting daemon");
        Daemon.debugLog("restartDaemon: called");
        stopDaemon();
        // Wait for daemon to stop (max 5 seconds)
        for (int i = 0; i < 5 && isDaemonRunning(); i++) {
            Daemon.debugLog("restartDaemon: waiting for daemon to stop (attempt " + (i + 1) + ")");
            threadSleepOrKill(1000);
        }
        Daemon.debugLog("restartDaemon: calling startDaemon");
        return startDaemon();
    }


    public void asyncStartDaemon() {
        Daemon.debugLog("asyncStartDaemon called from:\n" + android.util.Log.getStackTraceString(new Exception()));
        runOnPool(() -> {
            boolean ok = startDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (!ok) Toast.makeText(ctx, R.string.vm_daemon_error, LENGTH_LONG).show();
                asyncRefreshDaemonStatus();
            });
        });
    }

    public void asyncStopDaemon() {
        Daemon.debugLog("asyncStopDaemon called from:\n" + android.util.Log.getStackTraceString(new Exception()));
        runOnPool(() -> {
            boolean ok = stopDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (ok) {
                    Toast.makeText(ctx,
                        R.string.settings_daemon_stopped,
                        LENGTH_SHORT
                    ).show();
                } else {
                    Toast.makeText(ctx,
                        R.string.vm_daemon_error,
                        LENGTH_LONG
                    ).show();
                }
                asyncRefreshDaemonStatus();
            });
        });
    }

    public void asyncRestartDaemon() {
        Daemon.debugLog("asyncRestartDaemon called from:\n" + android.util.Log.getStackTraceString(new Exception()));
        runOnPool(() -> {
            boolean ok = restartDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (ok) {
                    Toast.makeText(ctx, R.string.settings_daemon_running, LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, R.string.vm_daemon_error, LENGTH_LONG).show();
                }
                asyncRefreshDaemonStatus();
            });
        });
    }


    public void asyncRefreshDaemonStatus() {
        if (onRefreshDaemonStatus == null) return;
        runOnPool(() -> {
            boolean running;
            try {
                running = DaemonHelper.isDaemonRunning();
            } catch (Exception e) {
                running = false;
            }
            boolean finalRunning = running;
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                if (onRefreshDaemonStatus != null)
                    onRefreshDaemonStatus.accept(finalRunning);
            });
        });
    }

    public void setOnRefreshDaemonStatus(Consumer<Boolean> onRefreshDaemonStatus) {
        this.onRefreshDaemonStatus = onRefreshDaemonStatus;
    }
}
