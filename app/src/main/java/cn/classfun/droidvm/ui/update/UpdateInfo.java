// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.update;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class UpdateInfo implements JSONSerialize {
    private final Map<String, String> changelog;
    private final String packageName;
    private final String version;
    private final int versionCode;
    private final String downloadUrl;
    private final String pageUrl;

    public UpdateInfo(@NonNull JSONObject obj) throws JSONException {
        if (!obj.optBoolean("success", false)) {
            var msg = obj.optString("message", "unknown");
            throw new RuntimeException(fmt("Update check failed: %s", msg));
        }
        this.packageName = obj.getString("package");
        this.version = obj.getString("version");
        this.versionCode = obj.getInt("version_code");
        this.changelog = JsonUtils.objectToStringMap(obj, "changelog");
        this.downloadUrl = obj.optString("download_url", "");
        this.pageUrl = obj.optString("page_url", "");
    }

    /**
     * Builds the same shape as the old update API from one GitHub release object.
     *
     * <p>GitHub has no {@code version_code} and no multi-language changelog, so the tag is
     * parsed as {@code major.minor.patch} into the same 10000/100/1 code the build uses, and the
     * release body becomes the {@code default} changelog entry. That keeps the dialog and the
     * comparison in {@link VersionCheck} unchanged; only where the JSON comes from moved.</p>
     *
     * @param release one element of {@code GET /repos/{owner}/{repo}/releases/latest}
     * @throws JSONException when the release has no usable tag
     */
    @NonNull
    public static UpdateInfo fromGitHub(@NonNull JSONObject release) throws JSONException {
        var tag = release.isNull("tag_name") ? "" : release.optString("tag_name", "").trim();
        if (tag.isEmpty()) throw new JSONException("GitHub release has no tag_name");
        var version = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
        var pageUrl = release.isNull("html_url") ? "" : release.optString("html_url", "");
        var downloadUrl = "";
        var assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                var asset = assets.optJSONObject(i);
                if (asset == null) continue;
                var name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                downloadUrl = asset.optString("browser_download_url", "");
                if (!downloadUrl.isEmpty()) break;
            }
        }
        var obj = new JSONObject();
        obj.put("success", true);
        obj.put("package", BuildConfig.APPLICATION_ID);
        obj.put("version", version);
        obj.put("version_code", versionCodeOf(version));
        var changelog = new JSONObject();
        changelog.put("default", release.isNull("body") ? "" : release.optString("body", ""));
        obj.put("changelog", changelog);
        obj.put("download_url", downloadUrl);
        obj.put("page_url", pageUrl);
        return new UpdateInfo(obj);
    }

    /** {@code 1.2.3} -> 10203, matching the build's 10000-based versionCode scheme. */
    private static int versionCodeOf(@NonNull String version) {
        var matcher = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matcher(version);
        if (!matcher.find()) return 0;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return major * 10000 + minor * 100 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    @SuppressWarnings("unused")
    public String getPackageName() {
        return packageName;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getVersion() {
        return version;
    }

    @SuppressWarnings("unused")
    public int getVersionCode() {
        return versionCode;
    }

    @NonNull
    @SuppressWarnings("unused")
    public Map<String, String> getChangelogs() {
        return changelog;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getChangelog() {
        return JsonUtils.getMultiLanguageString(changelog);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getDownloadUrl() {
        return downloadUrl;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getPageUrl() {
        return pageUrl;
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("success", true);
        obj.put("package", packageName);
        obj.put("version", version);
        obj.put("version_code", versionCode);
        var cl = new JSONObject();
        for (var entry : changelog.entrySet())
            cl.put(entry.getKey(), entry.getValue());
        obj.put("changelog", cl);
        obj.put("download_url", downloadUrl);
        obj.put("page_url", pageUrl);
        return obj;
    }

    @NonNull
    @Override
    public String toString() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return super.toString();
        }
    }
}
