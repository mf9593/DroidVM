// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Scans the shared DroidVM directory ({@code /storage/emulated/0/DroidVM/}) for disk
 * images that are not yet referenced by any VM in the store, and creates VMConfig
 * entries pointing at them.
 *
 * <p>This lets users drop a pre-built qcow2/raw image into the shared directory and
 * see it appear in the VM list without manually creating a VM.</p>
 */
public final class VMAutoDetect {
    private static final String TAG = "VMAutoDetect";
    public static final String SHARED_DIR =
        fmt("/storage/emulated/0/DroidVM");
    private static final String[] DISK_EXTS = {
        ".qcow2", ".qcow", ".qcow3", ".vmdk", ".img", ".raw", ".vdi", ".cow",
        ".vhd", ".vhdx", ".hdp", ".vpc", ".vbox-hd"
    };

    private VMAutoDetect() {
    }

    /**
     * Scans the shared directory for disk images and adds new VM configs for any
     * that are not already referenced. Returns the list of newly added configs.
     *
     * <p>The store is saved if any new VMs were added.</p>
     */
    @NonNull
    public static List<VMConfig> scanAndImport(
        @NonNull Context context,
        @NonNull VMStore store
    ) {
        var result = new ArrayList<VMConfig>();
        try {
            var sharedDir = new File(SHARED_DIR);
            if (!sharedDir.isDirectory()) {
                Log.i(TAG, "Shared directory does not exist: " + SHARED_DIR);
                return result;
            }

            // Collect disk image files (non-recursive, top-level only)
            var diskFiles = findDiskImages(sharedDir);
            if (diskFiles.isEmpty()) {
                Log.i(TAG, "No disk images found in " + SHARED_DIR);
                return result;
            }

            Log.i(TAG, "Found " + diskFiles.size() + " disk image(s) in " + SHARED_DIR);

            // Collect every disk path already owned by an existing VM. Checking only the
            // boot image is not enough: a VM can boot from an ISO while its operating-system
            // disk sits on another bus, and that second disk would then look "new" and get an
            // auto-created 512 MB duplicate sharing the same image on every list refresh.
            var referencedPaths = new HashSet<String>();
            for (int i = 0; i < store.size(); i++)
                addReferencedPaths(referencedPaths, store.get(i));

            boolean modified = false;
            for (var diskFile : diskFiles) {
                var absPath = diskFile.getAbsolutePath();
                if (referencedPaths.contains(canonicalPath(absPath))) {
                    Log.d(TAG, "Disk already referenced by existing VM: " + absPath);
                    continue;
                }

                Log.i(TAG, "Importing disk image as new VM: " + absPath);
                var config = createVMForDisk(context, store, diskFile, absPath);
                store.add(config);
                result.add(config);
                modified = true;
            }

            if (modified) {
                store.save(context);
                Log.i(TAG, "Saved store with " + result.size() + " newly imported VM(s)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to auto-detect VM disks", e);
        }
        return result;
    }

    /** Adds the boot image plus every attached disk, canonicalized, to {@code out}. */
    private static void addReferencedPaths(@NonNull Set<String> out, @NonNull VMConfig vm) {
        var bootPath = BootConfig.imagePath(vm, 0);
        if (bootPath != null && !bootPath.isEmpty()) out.add(canonicalPath(bootPath));
        var disks = vm.item.opt("disks", (DataItem) null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return;
        for (var iter : disks) {
            var path = iter.getValue().optString("path", "");
            if (!path.isEmpty()) out.add(canonicalPath(path));
        }
    }

    @NonNull
    private static String canonicalPath(@NonNull String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception ignored) {
            return path;
        }
    }

    private static List<File> findDiskImages(@NonNull File dir) {
        var result = new ArrayList<File>();
        var files = dir.listFiles();
        if (files == null) return result;
        for (var f : files) {
            if (f.isDirectory()) {
                // Recursively scan subdirectories for disk images
                result.addAll(findDiskImages(f));
            } else if (f.isFile() && isDiskImage(f.getName())) {
                result.add(f);
            }
        }
        return result;
    }

    private static boolean isDiskImage(@NonNull String name) {
        var lower = name.toLowerCase();
        for (var ext : DISK_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static VMConfig createVMForDisk(
        @NonNull Context context,
        @NonNull VMStore store,
        @NonNull File diskFile,
        @NonNull String absPath
    ) {
        var config = VMConfig.createWithCustomizeDefaults(context);
        // Use parent directory name as VM name (e.g., "Debian-trixie")
        var parentDir = diskFile.getParentFile();
        var vmName = parentDir != null ? parentDir.getName() : diskFile.getName();
        // If multiple disks in same dir, append index to avoid name collision
        var diskName = diskFile.getName();
        var baseName = vmName;
        var idx = 2;
        while (store.findByName(vmName) != null) {
            vmName = fmt("%s (%d)", baseName, idx++);
        }
        config.setName(vmName);
        Log.i(TAG, "Created VM '" + vmName + "' for disk: " + diskName);

        // Set the disk path in the disks array
        var disks = config.item.opt("disks", (DataItem) null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) {
            disks = DataItem.newArray();
            config.item.set("disks", disks);
        }
        var disk0 = DataItem.newObject();
        disk0.set("path", absPath);
        disk0.set("readonly", false);
        disk0.set("cache", "writeback");
        disks.asArray().add(disk0);

        // Set the boot image disk index to 0
        var boot = BootConfig.of(config);
        boot.setImageDisk(0);
        boot.setProtocol(BootConfig.Protocol.UEFI);

        // Disable hugepages for imported VMs (no-root mode)
        config.item.set("hugepages", false);

        Log.i(TAG, "Created VM config for disk: " + absPath);
        return config;
    }
}
