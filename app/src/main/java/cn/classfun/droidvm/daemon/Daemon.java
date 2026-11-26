// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon;

import static android.os.Process.myPid;
import static cn.classfun.droidvm.BuildConfig.APPLICATION_ID;
import static cn.classfun.droidvm.BuildConfig.VERSION_CODE;
import static cn.classfun.droidvm.BuildConfig.VERSION_NAME;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPidFile;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPortFile;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getTokenFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.FileUtils.writeFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.daemon.audio.HostAudioTable;
import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.daemon.server.Server;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.utils.FileUtils;
import cn.classfun.droidvm.lib.utils.ProcessUtils;

public final class Daemon {
    public static final String TAG = "Daemon";
    public static final String LOG_PATH = pathJoin(DATA_DIR, "cache", "daemon.log");
    private static final String RUN_DIR = pathJoin(DATA_DIR, "run");
    private static final String LOCK_FILE = pathJoin(RUN_DIR, "droidvmd.lock");
    private static final AtomicBoolean cleaned = new AtomicBoolean(false);
    public static String daemonHash = null;
    private static Process logcatProcess = null;
    public static volatile boolean runningInProcess = false;
    private static Server inProcessServer = null;
    /** Cross-adb debug log; the in-process daemon writes here, not to {@link #LOG_PATH}. */
    public static final String DEBUG_LOG = "/storage/emulated/0/DroidVM/daemon-debug.log";

    public static void debugLog(String msg) {
        try {
            var dir = new File("/storage/emulated/0/DroidVM");
            if (!dir.exists()) dir.mkdirs();
            try (var w = new FileWriter(DEBUG_LOG, true)) {
                w.write(fmt("[%s] %s\n", java.time.LocalDateTime.now(), msg));
            }
        } catch (Exception e) {
            Log.w(TAG, "debugLog failed: " + e.getMessage());
        }
    }

    /**
     * Global uncaught exception pre-handler installed at daemon start: it writes every
     * uncaught exception's stack trace to daemon-debug.log (where cross-adb reads are
     * reliable) before the default handler runs and the process dies. Covers both the
     * daemon thread pool and the UI thread, because a VM start crash may surface on any
     * thread of the in-process daemon host.
     */
    private static void installUncaughtExceptionHandler() {
        try {
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    debugLog("UNCAUGHT in thread " + t.getName() + ": " + e);
                    debugLog(android.util.Log.getStackTraceString(e));
                } catch (Exception ignored) {
                }
                if (prev != null) {
                    try {
                        prev.uncaughtException(t, e);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // Held for the whole process lifetime so the advisory lock stays taken; the
    // kernel drops it automatically when this process dies (including SIGKILL),
    // so a stale lock can never block the next daemon.
    @SuppressWarnings("FieldCanBeLocal")
    private static FileChannel lockChannel;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileLock daemonLock;

    /**
     * Single-instance gate. Tries to take an exclusive lock on
     * {@code droidvmd.lock}. Returns true when held by this process. When the
     * lock is already taken by a live daemon: with {@code force}, that daemon
     * is terminated (SIGINT, then SIGKILL) and the lock is taken over;
     * without it, this returns false so the caller exits instead of running a
     * second daemon.
     */
    private static boolean acquireLock(boolean force) {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        try {
            lockChannel = FileChannel.open(Paths.get(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to open lock file: %s", LOCK_FILE), e);
            return false;
        }
        if (tryLock()) {
            writeLockOwner();
            return true;
        }
        int holder = readLockOwner();
        if (!force) {
            Log.e(TAG, fmt(
                "Another daemon (pid=%d) is already running; exiting "
                    + "(start with --force to take over)", holder));
            return false;
        }
        Log.i(TAG, fmt("--force: terminating existing daemon (pid=%d)", holder));
        if (holder > 1) ProcessUtils.shellKillProcess(holder, ProcessUtils.SIGINT);
        if (waitForLock(10_000)) {
            writeLockOwner();
            return true;
        }
        if (holder > 1) {
            Log.w(TAG, fmt("Existing daemon (pid=%d) did not exit; sending SIGKILL", holder));
            ProcessUtils.shellKillProcess(holder, ProcessUtils.SIGKILL);
        }
        if (waitForLock(3_000)) {
            writeLockOwner();
            return true;
        }
        Log.e(TAG, "Failed to take over the daemon lock");
        return false;
    }

    private static boolean tryLock() {
        try {
            daemonLock = lockChannel.tryLock();
            return daemonLock != null;
        } catch (Exception e) {
            // OverlappingFileLockException can't happen (single attempt); any
            // other failure is treated as "not acquired".
            Log.w(TAG, "tryLock failed", e);
            return false;
        }
    }

    private static boolean waitForLock(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryLock()) return true;
            try {
                //noinspection BusyWait
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** Records this daemon's pid in the lock file so a later --force can find it. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void writeLockOwner() {
        try {
            lockChannel.truncate(0);
            lockChannel.position(0);
            lockChannel.write(ByteBuffer.wrap(String.valueOf(myPid()).getBytes()));
            lockChannel.force(false);
        } catch (IOException e) {
            Log.w(TAG, "Failed to write lock owner pid", e);
        }
    }

    /** Pid of the current lock holder: from the lock file, else the pid file. */
    private static int readLockOwner() {
        for (var path : new String[]{LOCK_FILE, getPidFile()}) {
            try {
                var s = FileUtils.readFile(path).trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static void writePidFile() {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        var pidFile = new File(runDir, "droidvmd.pid");
        try (var writer = new FileWriter(pidFile)) {
            writer.write(String.valueOf(myPid()));
            writer.flush();
            Log.i(TAG, fmt("PID file written: %s (pid=%d)", pidFile, myPid()));
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to write PID file: %s", pidFile), e);
        }
    }

    private static void writeTokenFile() {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        var tokenFile = new File(runDir, "droidvmd-token.txt");
        var token = UUID.randomUUID().toString().replace("-", "");
        try (var writer = new FileWriter(tokenFile)) {
            writer.write(token);
            writer.flush();
            Log.i(TAG, fmt("Token file written: %s", tokenFile));
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to write token file: %s", tokenFile), e);
        }
    }

    private static void cleanup(Server server) {
        if (!cleaned.compareAndSet(false, true)) return;
        Log.i(TAG, "Stopping all VMs and networks...");
        var ctx = server.getContext();
        try {
            ctx.getVMs().stopAll();
        } finally {
            // After the VMs, not before: stopping them only queues the USB releases, and the
            // interfaces are handed back to the host from that queue. In a finally, because the
            // first thing that shutdown does is put /sys/bus/usb/drivers_autoprobe back to the
            // kernel's default: that flag is global and outlives this process, so a stop that
            // throws on its way past here would leave every device plugged into the phone
            // afterwards with no driver bound -- no keyboard, no storage -- and no daemon left
            // to write it back.
            ctx.getUsb().shutdown();
        }
        ctx.getNetworks().stopAll();
        ctx.getNetworks().firewall.shutdown();
        ctx.getRouterWatcher().stop();
        FileUtils.deleteFile(getPidFile());
        FileUtils.deleteFile(getTokenFile());
        FileUtils.deleteFile(getPortFile());
        Log.i(TAG, "DroidVM Daemon shutdown complete");
    }

    @NonNull
    private static String getMyHash() {
        var pmResult = runList("pm", "path", APPLICATION_ID);
        if (!pmResult.isSuccess()) {
            pmResult.printLog(TAG);
            throw new RuntimeException(fmt("Failed to get package path"));
        }
        var pkgPath = pmResult.getOutString().trim();
        if (!pkgPath.startsWith("package:"))
            throw new RuntimeException(fmt("Unexpected pm output: %s", pkgPath));
        pkgPath = pkgPath.substring("package:".length()).trim();
        if (!new File(pkgPath).exists())
            throw new RuntimeException(fmt("Package path does not exist: %s", pkgPath));
        try {
            var hash = FileUtils.calcHashForFile(pkgPath, "SHA-256");
            if (hash.length() != 64)
                throw new RuntimeException(fmt("Unexpected hash length: %d", hash.length()));
            return hash;
        } catch (Exception e) {
            throw new RuntimeException(fmt("Failed to calculate hash for package: %s", pkgPath), e);
        }
    }

    private static void setProcTitle() {
        try {
            FileUtils.writeFile("/proc/self/comm", "droidvmd");
        } catch (IOException e) {
            Log.w(TAG, "Failed to set process title", e);
        }
    }

    @SuppressWarnings("OctalInteger")
    private static void startLogcat() {
        try {
            var builder = new ProcessBuilder();
            var file = new File(LOG_PATH);
            if (file.exists() && !file.delete())
                Log.w(TAG, "failed to remove old log");
            writeFile(file, "");
            var uid = Server.getDroidVMUid();
            try {
                Os.chmod(LOG_PATH, 0600);
                Os.chown(LOG_PATH, uid, uid);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set log file permissions (no-root mode?)");
            }
            builder.command(
                findExecute("logcat", "/system/bin/logcat"),
                fmt("--pid=%d", myPid())
            );
            builder.redirectOutput(new File(LOG_PATH));
            logcatProcess = builder.start();
        } catch (Exception e) {
            Log.w(TAG, "failed to start logcat", e);
            logcatProcess = null;
        }
    }

    private static void stopLogcat() {
        if (logcatProcess == null) return;
        logcatProcess.destroy();
        logcatProcess = null;
    }

    public static boolean startInProcess(boolean force) {
        installUncaughtExceptionHandler();
        // The in-process daemon runs in the app's JVM, where nothing else loads
        // libunixhelper.so (only Daemon.main() does, on the standalone path). VM
        // start uses nativePipe()/nativePollIn() etc., so loading here is required
        // for any VM to boot. load() is idempotent and safe to call again.
        UnixHelper.load();
        debugLog("startInProcess(force=" + force + ")");
        if (runningInProcess) {
            debugLog("Daemon already running in-process, skipping");
            return false;
        }
        if (!acquireLock(force)) {
            debugLog("acquireLock failed");
            return false;
        }
        debugLog("acquireLock succeeded");
        runningInProcess = true;
        // daemonHash must be computed here even though startInProcess() bypasses main():
        // it runs the Server without invoking main(), so the daemon's reported hash would
        // otherwise remain null. VersionHandler needs it to answer "version" to clients.
        try {
            daemonHash = getMyHash();
            Log.i(TAG, fmt("DroidVM Daemon hash: %s", daemonHash));
            debugLog("Daemon started in-process pid=" + myPid() + " hash=" + daemonHash + " vcode=" + VERSION_CODE + " name=" + VERSION_NAME);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute daemon hash", e);
            debugLog("Daemon hash failed: " + e);
        }
        writePidFile();
        debugLog("PID file written, pid=" + myPid());
        writeTokenFile();
        debugLog("Token file written");
        try {
            inProcessServer = new Server();
            debugLog("Server created");
        } catch (Exception e) {
            debugLog("Server creation failed: " + e);
            Log.e(TAG, "Failed to create Server, cleaning up", e);
            runningInProcess = false;
            try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
            var pidFile = new File(RUN_DIR, "droidvmd.pid");
            var tokenFile = new File(RUN_DIR, "droidvmd-token.txt");
            var lockFile = new File(LOCK_FILE);
            if (pidFile.exists()) pidFile.delete();
            if (tokenFile.exists()) tokenFile.delete();
            if (lockFile.exists()) lockFile.delete();
            return false;
        }
        Thread serverThread = new Thread(inProcessServer::run, "DaemonServer");
        serverThread.setDaemon(true);
        serverThread.start();
        debugLog("Server thread started");
        // libsu is the daemon's only path to root (RootRunContext). If it silently falls back
        // to an app-uid shell, every netbox/insmod/sysctl call fails with EPERM and the user
        // sees only "setupTaps failed"; record which identity it actually got.
        try {
            var probe = cn.classfun.droidvm.lib.utils.RunUtils.runQuiet("id");
            debugLog("root probe: exit=" + probe.getCode()
                + " out=" + probe.getOutString().trim()
                + " err=" + probe.getErrString().trim());
        } catch (Exception e) {
            debugLog("root probe failed: " + e);
        }
        Log.i(TAG, "Daemon started in-process (no-root mode)");
        return true;
    }

    public static void stopInProcess() {
        debugLog("stopInProcess: called, runningInProcess=" + runningInProcess);
        if (!runningInProcess) return;
        runningInProcess = false;
        if (inProcessServer != null) {
            debugLog("stopInProcess: stopping server");
            inProcessServer.stop();
            inProcessServer = null;
        }
        // Clean up lock and files
        try {
            if (lockChannel != null) lockChannel.close();
        } catch (Exception ignored) {
        }
        var pidFile = new File(RUN_DIR, "droidvmd.pid");
        var tokenFile = new File(RUN_DIR, "droidvmd-token.txt");
        var portFile = new File(RUN_DIR, "droidvmd-port.txt");
        var lockFile = new File(LOCK_FILE);
        if (pidFile.exists()) pidFile.delete();
        if (tokenFile.exists()) tokenFile.delete();
        if (portFile.exists()) debugLog("stopInProcess: deleting stale port file");
        if (portFile.exists()) portFile.delete();
        if (lockFile.exists()) lockFile.delete();
        debugLog("stopInProcess: done, all files cleaned up");
        Log.i(TAG, "Daemon stopped in-process");
    }

    public static boolean isRunningInProcess() {
        return runningInProcess;
    }

    public static void main(String... args) {
        boolean force = Arrays.asList(args).contains("--force");
        System.out.print("Starting DroidVM Daemon...\n");
        UnixHelper.load();
        // Build a system Context on the main thread (it prepares the main looper) so the daemon can
        // broadcast the native-display binder to the UI. Must happen before server.run() blocks here.
        DaemonSystemContext.init();
        setProcTitle();
        startLogcat();
        System.out.printf("Current pid: %d\n", myPid());
        Log.d(TAG, "DroidVM Daemon is starting...");
        if (!acquireLock(force)) {
            System.out.print("Another DroidVM Daemon is already running.\n");
            System.exit(1);
        }
        // Publish the host's audio endpoints before any VM starts, and keep them current: a VM
        // pinned to a headset needs to find it again by name after it is reconnected, and the
        // number it had before will not be the number it has after.
        HostAudioTable.start(DaemonSystemContext.get());
        daemonHash = getMyHash();
        Log.i(TAG, fmt("DroidVM Daemon hash: %s", daemonHash));
        try {
            debugLog("Daemon started pid=" + myPid() + " hash=" + daemonHash + " vcode=" + VERSION_CODE + " name=" + VERSION_NAME);
        } catch (Exception ignored) {
        }
        writePidFile();
        writeTokenFile();
        // Install a process-wide uncaught exception handler that logs to daemon-debug.log
        // before re-throwing to the default handler. Android's default handler also writes
        // the crash, but its output lands in logcat buffers we can't always read from
        // across adb; the file is reliable and persistent.
        installUncaughtExceptionHandler();
        var server = new Server();
        UnixHelper.installSignalHandler("INT", sig -> {
            Log.i(TAG, fmt("Received signal %d (INT), shutting down...", sig));
            System.exit(128 + sig);
        });
        UnixHelper.installSignalHandler("TERM", sig -> {
            Log.i(TAG, fmt("Received signal %d (TERM), shutting down...", sig));
            System.exit(128 + sig);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            cleanup(server);
            stopLogcat();
        }));
        server.run();
        cleanup(server);
        stopLogcat();
    }
}
