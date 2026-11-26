// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import java.io.File;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.ImeInsetsApplier;
import cn.classfun.droidvm.lib.utils.AssetUtils;
import cn.classfun.droidvm.lib.utils.FileUtils;
import cn.classfun.droidvm.lib.utils.RunUtils;
import cn.classfun.droidvm.lib.utils.ThreadUtils;
import cn.classfun.droidvm.ui.main.settings.KernelModuleManager;

public final class DroidVMApp extends Application {
    private static final String TAG = "DroidVMApp";
    private VMEventHandler vmEventHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ImeInsetsApplier());
        // Auto-detect root availability: use full-featured root mode when
        // su is available (enables kernel modules, hugepages, TAP networking,
        // iptables), otherwise fall back to no-root mode (slirp networking,
        // no kernel module dependencies).
        var hasRoot = checkRootAvailable();
        RunUtils.setNoRootMode(!hasRoot);
        Log.i(TAG, hasRoot ? "Root mode enabled (full features)" : "No-root mode enabled (fallback)");
        vmEventHandler = new VMEventHandler(this);
        registerActivityLifecycleCallbacks(vmEventHandler);
        DaemonConnection.getInstance().addListener(vmEventHandler);
        ThreadUtils.runOnPool(() -> {
            var vmStore = new VMStore();
            initializeStore(vmStore);
            vmStore.removeMalformedSharedCopies(this);
            initializeStore(new DiskStore());
            initializeStore(new NetworkStore());
            try {
                // Extract CMake-built binaries (droidvm, daemon) from APK assets.
                // This is also done in SplashActivity, but doing it here ensures
                // the binaries are always extracted regardless of splash screen.
                AssetUtils.extractBinaries(this);
                AssetUtils.extractLibraries(this);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract binaries", e);
            }
            try {
                if (AssetUtils.needsExtractPrebuilt(this)) {
                    Log.i(TAG, "Extracting prebuilt binaries...");
                    AssetUtils.extractPrebuilt(this);
                    Log.i(TAG, "Prebuilt extraction complete");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract prebuilt", e);
            }
            try {
                KernelModuleManager.applyAutostart(this);
            } catch (Exception e) {
                Log.w(TAG, "kernel module autostart failed", e);
            }
        });
    }

    private void initializeStore(DataStore<?> store) {
        try {
            if (!store.getStoreFile(this).exists())
                store.save(this);
        } catch (Exception e) {
            Log.w(TAG, String.format(
                "Failed to initialize store: %s",
                store.getClass().getSimpleName()
            ), e);
        }
    }

    public VMEventHandler getVMEventHandler() {
        return vmEventHandler;
    }

    /**
     * Checks if root (su) is available on this device.
     * Checks for su binary existence in common locations.
     * Returns false if no root or libsu is unavailable.
     */
    private boolean checkRootAvailable() {
        try {
            // Check if su binary exists in common locations (non-blocking)
            // Use File.exists() instead of canExecute() - SELinux may prevent
            // canExecute() from returning true even for executable files
            var suPaths = new String[]{
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
                "/data/local/tmp/su", "/system/bin/.ext/su", "/su/bin/su", "/system/sbin/su"
            };
            for (var path : suPaths) {
                var file = new File(path);
                if (file.exists()) {
                    Log.i(TAG, "Root available at: " + path);
                    return true;
                }
            }
            // Also try findExecute as fallback
            var su = FileUtils.findExecute("su", null);
            if (su != null) {
                Log.i(TAG, "Root available at: " + su);
                return true;
            }
        } catch (Throwable e) {
            Log.i(TAG, "Root check failed: " + e.getMessage());
        }
        return false;
    }

    @Override
    public void onTerminate() {
        DaemonConnection.getInstance().removeListener(vmEventHandler);
        unregisterActivityLifecycleCallbacks(vmEventHandler);
        DaemonConnection.getInstance().shutdown();
        super.onTerminate();
    }
}
