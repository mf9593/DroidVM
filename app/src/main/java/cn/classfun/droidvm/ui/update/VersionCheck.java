// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.update;

import static cn.classfun.droidvm.lib.Constants.GITHUB_OWNER;
import static cn.classfun.droidvm.lib.Constants.GITHUB_REPO;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.lib.utils.NetUtils;

public final class VersionCheck {
    private static final String TAG = "VersionCheck";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        @SuppressWarnings("unused")
        void onUpdateAvailable(@NonNull UpdateInfo info);

        @SuppressWarnings("unused")
        default void onNoUpdate() {
        }

        @SuppressWarnings("unused")
        default void onError(@NonNull Exception e) {
        }
    }

    /**
     * Reads the latest GitHub release of this fork.
     *
     * <p>The upstream build asked {@code api.classfun.cn} for an {@code update_check} record.
     * A fork that hosts its own APKs cannot use that server, so the release itself is the
     * record now: tag = version, body = changelog, first {@code .apk} asset = download, and the
     * HTML release page = page URL. A repository with no releases yet answers 404, which is
     * "nothing to install", not a failure the home card should surface.</p>
     */
    public void check(@NonNull Context ctx, @NonNull Callback callback) {
        runOnPool(() -> {
            try {
                if (!Privacy.isPrivacyAgreed(ctx))
                    throw new IllegalStateException("Privacy policy not agreed or outdated");
                var url = fmt("https://api.github.com/repos/%s/%s/releases/latest",
                    GITHUB_OWNER, GITHUB_REPO);
                var release = NetUtils.fetchJSON(url, NetUtils.USER_AGENT);
                var info = UpdateInfo.fromGitHub(release);
                if (info.getVersionCode() > BuildConfig.VERSION_CODE) {
                    mainHandler.post(() -> callback.onUpdateAvailable(info));
                } else {
                    mainHandler.post(callback::onNoUpdate);
                }
            } catch (NetUtils.HttpException e) {
                if (e.getCode() == 404) {
                    mainHandler.post(callback::onNoUpdate);
                    return;
                }
                Log.e(TAG, "Failed to check GitHub for updates", e);
                mainHandler.post(() -> callback.onError(e));
            } catch (Exception e) {
                Log.e(TAG, "Failed to check GitHub for updates", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }
}
