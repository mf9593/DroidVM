// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.enums;

import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import cn.classfun.droidvm.lib.store.base.DataItem;

public final class Enums {
    private Enums() {
    }

    public static <E extends Enum<E> & StringEnum & ColorEnum> void applyText(
        @NonNull TextView tv, @NonNull E val
    ) {
        var ctx = tv.getContext();
        tv.setText(val.getStringId());
        tv.setTextColor(val.getDisplayColor(ctx).toArgb());
    }

    /**
     * Upper-cases a stored value into an enum constant name.
     *
     * <p>Persisted values come from {@link StringEnum#getName()} for some enums
     * ({@code vulkan-system}) and from {@code name()} for others ({@code VULKAN_SYSTEM}); the
     * former are hyphenated. {@code Enum.valueOf} only knows underscores, so without this the
     * hyphenated spellings silently fell back to the default -- which is how a stored
     * {@code gpu_api=vulkan-system} turned into {@code NONE} and the daemon went back to the
     * bundled turnip, aborting the VM in gfxstream when the host GPU has no turnip support.</p>
     */
    @NonNull
    private static String enumKey(@NonNull String str) {
        return str.trim().replace('-', '_').toUpperCase();
    }

    public static <E extends Enum<E>> E optEnum(
        @NonNull JSONObject jo,
        @NonNull String key,
        @NonNull E def
    ) {
        var str = jo.optString(key, def.name());
        try {
            return Enum.valueOf(
                def.getDeclaringClass(),
                enumKey(str)
            );
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public static <E extends Enum<E>> E optEnum(
        @NonNull DataItem item,
        @NonNull String key,
        @NonNull E def
    ) {
        var str = item.optString(key, def.name());
        try {
            return Enum.valueOf(
                def.getDeclaringClass(),
                enumKey(str)
            );
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
