// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.changelog;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.R;

/**
 * Settings: the locally maintained changelog.
 *
 * <p>This is the development history of this build, not the remote release feed the update
 * checker speaks to: the entries below are written by hand from
 * {@code codetool/docs/devlog/*.md} and ship inside the APK, so they work offline and stay
 * readable after the release they describe is no longer the latest one.</p>
 *
 * <p>The page is deliberately a list of dated cards rather than one blob of text: each date is
 * one development session, and the footer points at the full record (commands, validation data
 * and dead ends) in the source tree for anyone who needs the details this summary leaves out.</p>
 */
public final class ChangelogActivity extends AppCompatActivity {
    /**
     * Newest first. Each entry is {title, body}; the strings carry the bullets themselves so
     * translators get one paragraph per date instead of a list model that would need its own
     * translation file.
     */
    private static final int[][] ENTRIES = {
        {R.string.changelog_2026_11_25_title, R.string.changelog_2026_11_25_body},
        {R.string.changelog_2026_11_23_title, R.string.changelog_2026_11_23_body},
        {R.string.changelog_2026_11_22_title, R.string.changelog_2026_11_22_body},
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        TextView tvVersion = findViewById(R.id.tv_version);
        TextView tvFooter = findViewById(R.id.tv_footer);
        LinearLayout container = findViewById(R.id.day_container);
        toolbar.setTitle(R.string.changelog_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvVersion.setText(getString(R.string.changelog_version, BuildConfig.VERSION_NAME));
        tvFooter.setText(R.string.changelog_footer);
        Linkify.addLinks(tvFooter, Linkify.WEB_URLS);
        tvFooter.setMovementMethod(LinkMovementMethod.getInstance());
        var inflater = LayoutInflater.from(this);
        for (var entry : ENTRIES) {
            var view = inflater.inflate(R.layout.item_changelog_day, container, false);
            ((TextView) view.findViewById(R.id.tv_day_title)).setText(entry[0]);
            ((TextView) view.findViewById(R.id.tv_day_body)).setText(entry[1]);
            container.addView(view);
        }
    }
}
