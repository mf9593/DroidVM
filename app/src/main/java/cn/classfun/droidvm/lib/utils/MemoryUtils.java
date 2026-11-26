// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.utils;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import cn.classfun.droidvm.BuildConfig;

/**
 * Host RAM helpers behind the home screen's "Free memory" action.
 *
 * <p>The VM's start is gated by host {@code MemAvailable}: Gunyah's SHARE/MEM_ACCEPT step needs
 * roughly the guest's RAM plus 1.5 GB, and below that it gives up with {@code MEM_ACCEPT EBUSY}
 * while the guest sits at a black screen. Restarting the phone always "fixes" that, which is a bad
 * deal when the memory is held by cached background apps -- Android can drop those on request.</p>
 *
 * <p>Two levels, because they have different costs:</p>
 * <ul>
 *   <li>{@link #quickRelease()} is what a memory cleaner does: kill cached background processes,
 *   run the framework's idle maintenance, then drop page cache and compact. No app's data is
 *   touched and no foreground process is killed.</li>
 *   <li>{@link #forceStop(String)} is the opt-in sledgehammer for apps that survive the first pass
 *   (instant-messenger push services are the usual ones). Only the UI's explicit checkbox list
 *   calls it.</li>
 * </ul>
 *
 * <p>Commands run through {@link RunUtils}, so they go through {@code su} exactly like the rest of
 * the daemon's privileged work; callers must keep this off the UI thread.</p>
 */
public final class MemoryUtils {
    private static final String TAG = "MemoryUtils";

    private MemoryUtils() {
    }

    /**
     * Packages that a deep clean must never force-stop. Stopping one of these takes down part of
     * the platform (System UI, launcher, telephony, the vendor HALs) and the phone looks broken
     * until it reboots -- worse than the memory pressure this screen is trying to relieve. They
     * are still counted by Android's own memory management, which is the right owner for them.
     */
    private static final String[] NEVER_STOP = {
        "android",
        "com.android.",
        "com.google.",
        "com.miui.",
        "com.xiaomi.",
        "com.qualcomm.",
        "com.mediatek.",
        "com.sec.",
        "com.samsung.",
        "com.oplus.",
        "com.oppo.",
        "com.vivo.",
        "com.oneplus.",
        "com.motorola.",
        "com.transsion.",
        "com.qti.",
        "org.codeaurora.",
        "vendor.",
    };

    /** A process name that is a package (at least one dot, no spaces or path separators). */
    private static final Pattern PACKAGE = Pattern.compile(
        "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    /** One app and the RSS its processes add up to. */
    public static final class AppMem {
        @NonNull
        public final String packageName;
        public final long rssMb;

        AppMem(@NonNull String packageName, long rssMb) {
            this.packageName = packageName;
            this.rssMb = rssMb;
        }
    }

    /**
     * {@code MemAvailable} from /proc/meminfo in MB, or -1 when it cannot be read.
     *
     * <p>World-readable, so the home screen can poll it without a root round trip.</p>
     */
    public static long availableMb() {
        try (var reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemAvailable:")) continue;
                var parts = line.trim().split("\\s+");
                return parts.length >= 2 ? Long.parseLong(parts[1]) / 1024 : -1;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * First pass: ask Android to reclaim what it can, then flush and compact the page cache.
     *
     * <p>{@code am kill-all} is the same operation the framework performs under memory pressure:
     * it only touches processes that are already cached/background, so the app the user is looking
     * at and anything with a foreground service stay up. {@code idle-maintenance} lets the job
     * scheduler and app-idle machinery do their part, and {@code compact_memory} improves the
     * chance that Gunyah gets contiguous pages on the next start.</p>
     *
     * @return {@code MemAvailable} after the pass, in MB (-1 when unreadable)
     */
    public static long quickRelease() {
        RunUtils.runQuiet(
            "/system/bin/am kill-all"
                + "; /system/bin/cmd activity idle-maintenance"
                + "; /system/bin/sync"
                + "; echo 3 > /proc/sys/vm/drop_caches"
                + "; echo 1 > /proc/sys/vm/compact_memory");
        return availableMb();
    }

    /** Flush/compact only -- the second half of {@link #quickRelease()}, after force-stops. */
    public static long flushCaches() {
        RunUtils.runQuiet(
            "sync"
                + "; echo 3 > /proc/sys/vm/drop_caches"
                + "; echo 1 > /proc/sys/vm/compact_memory");
        return availableMb();
    }

    /**
     * The biggest third-party apps by summed RSS, largest first.
     *
     * <p>RSS is the number the user can act on, not PSS: this list only decides which packages are
     * worth offering to stop, and the exact accounting does not change that order. Platform
     * packages and this app (its daemon is the reason the screen exists) are excluded; a process
     * name is normalised to its package so {@code pkg:service} adds to {@code pkg}.</p>
     *
     * @param limit    maximum rows to return
     * @param minRssMb ignore anything smaller than this
     */
    @NonNull
    public static List<AppMem> heavyApps(int limit, long minRssMb) {
        var result = RunUtils.runQuiet("/system/bin/ps -A -o pid,rss,args");
        Map<String, Long> totals = new LinkedHashMap<>();
        for (var line : result.getOutString().split("\n")) {
            var parts = line.trim().split("\\s+", 3);
            if (parts.length < 3) continue;
            long kb;
            try {
                kb = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (kb <= 0) continue;
            var name = parts[2];
            int space = name.indexOf(' ');
            if (space > 0) name = name.substring(0, space);
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (!PACKAGE.matcher(name).matches()) continue;
            if (!isStoppable(name)) continue;
            totals.merge(name, kb, Long::sum);
        }
        var out = new ArrayList<AppMem>();
        for (var entry : totals.entrySet()) {
            long mb = entry.getValue() / 1024;
            if (mb >= minRssMb) out.add(new AppMem(entry.getKey(), mb));
        }
        out.sort((a, b) -> Long.compare(b.rssMb, a.rssMb));
        if (out.size() > limit) out.subList(limit, out.size()).clear();
        Log.d(TAG, "heavyApps: ps exit=" + result.getCode()
            + ", candidates=" + out.size() + (out.isEmpty() ? "" : ", top=" + out.get(0).packageName));
        return out;
    }

    /** Whether a package may be offered to the deep clean (see {@link #NEVER_STOP}). */
    public static boolean isStoppable(@NonNull String packageName) {
        if (packageName.equals(BuildConfig.APPLICATION_ID)) return false;
        for (var prefix : NEVER_STOP)
            if (packageName.equals(prefix) || packageName.startsWith(prefix)) return false;
        return true;
    }

    /**
     * Force-stop one package through the activity manager. The name is re-validated here because
     * it comes back from parsing {@code ps}; a malformed value must never reach a root shell.
     */
    public static void forceStop(@NonNull String packageName) {
        if (!PACKAGE.matcher(packageName).matches()) return;
        if (!isStoppable(packageName)) return;
        RunUtils.runQuiet("/system/bin/am force-stop " + RunUtils.escapedString(packageName));
    }

    /** "12.3 GB" -- one decimal is enough for a go/no-go decision. */
    @NonNull
    public static String formatGb(long mb) {
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }
}
