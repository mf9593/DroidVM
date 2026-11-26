// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import cn.classfun.droidvm.lib.store.base.DataStore;

public final class VMStore extends DataStore<VMConfig> {
    public VMStore() {
        super();
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull JSONObject obj) {
        super(obj);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull File file) {
        super(file);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected VMConfig create() {
        return new VMConfig();
    }

    @NonNull
    @Override
    protected VMConfig create(@NonNull JSONObject obj) throws JSONException {
        return new VMConfig(obj);
    }

    @NonNull
    @Override
    protected DataStore<VMConfig> createEmpty() {
        return new VMStore();
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "vms";
    }

    /** A VM name the importer had to de-duplicate, e.g. "Win11 (2)". */
    private static final Pattern IMPORT_COPY_NAME = Pattern.compile(".* \\(\\d+\\)$");

    /**
     * Removes the malformed "second copy" a partial VM-package import leaves behind: default
     * 512 MB, no disk bus on a disk path already owned by another VM, and a "(N)" name. That
     * is not a usable VM (it points at another VM's image and cannot boot); it appears in the
     * list only because the import registered the manifest before its disk payload was copied.
     *
     * <p>Loads the store, removes matching entries and saves. Called after the app's normal
     * store load; a package import that still creates one will be cleaned on the next load.</p>
     */
    public void removeMalformedSharedCopies(@NonNull Context context) {
        if (!load(context)) return;
        var seenPaths = new HashSet<String>();
        var remove = new ArrayList<java.util.UUID>();
        for (int i = 0; i < size(); i++) {
            var config = get(i);
            var name = config.getName();
            var disks = config.item.opt("disks", null);
            boolean shared = false;
            boolean missingBus = false;
            if (disks != null) {
                for (var iter : disks) {
                    var disk = iter.getValue();
                    var path = disk.optString("path", "");
                    if (path.isEmpty()) continue;
                    if (!seenPaths.add(path)) shared = true;
                    if (disk.optString("bus", "").isEmpty()) missingBus = true;
                }
            }
            if (shared && missingBus && config.item.optLong("memory_mb", 0) <= 512
                && name != null && IMPORT_COPY_NAME.matcher(name).matches()) {
                remove.add(config.getId());
            }
        }
        if (remove.isEmpty()) return;
        for (var id : remove) removeById(id);
        save(context);
    }
}
