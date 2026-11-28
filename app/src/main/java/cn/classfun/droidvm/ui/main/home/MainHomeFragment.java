// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.home;

import static android.content.Intent.ACTION_VIEW;
import static cn.classfun.droidvm.lib.Constants.GITHUB_ISSUE_URL;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.hugepage.PoolPreflight;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.lib.utils.MemoryUtils;
import cn.classfun.droidvm.lib.utils.RunUtils;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;
import cn.classfun.droidvm.ui.update.UpdateDialog;
import cn.classfun.droidvm.ui.update.UpdateInfo;
import cn.classfun.droidvm.ui.update.VersionCheck;
import cn.classfun.droidvm.ui.vm.VMCreateMenu;
import cn.classfun.droidvm.ui.vm.pkg.imports.VMPkgImportActivity;

public final class MainHomeFragment extends MainBaseFragment
    implements DaemonConnection.EventListener {
    private static final long REFRESH_INTERVAL_MS = 3000;
    private TextView tvDaemonStatus;
    private TextView tvDaemonPid;
    private ImageView ivDaemonCheck;
    private MaterialCardView cardDaemon;
    private TextView tvVMSummary;
    private TextView tvDiskSummary;
    private TextView tvNetworkSummary;
    private TextView tvKernelVersion;
    private TextView tvSocModel;
    private ImageView ivRootStatus;
    private ImageView ivVirtStatus;
    private ImageView ivHugeStatus;
    private TextView tvRootStatus;
    private TextView tvVirtStatus;
    private TextView tvHugeStatus;
    private MaterialButton btnReleaseHuge;
    private MaterialButton btnAcquireHuge;
    private int defaultRuntimeColor;
    private boolean hugeBusy;
    /** Cached per process: a granted/denied prompt is not repeated on every 3 s refresh. */
    private static final int ROOT_UNKNOWN = -1;
    private static final int ROOT_GRANTED = 1;
    private static final int ROOT_DENIED = 2;
    private static final int ROOT_NO_MODE = 3;
    private static int rootState = ROOT_UNKNOWN;
    private static String virtLabel = null;
    private static boolean virtAvailable = false;
    private static boolean virtHardware = false;
    /** Cached pool_avail / pool_want (2 MB pages); -1 = module absent or unreadable. */
    private static long hugeAvailPages = -1;
    private static long hugeWantPages = -1;
    private static long hugeCheckedAt = 0;
    private static final long HUGE_POOL_TTL_MS = 5000;
    private MaterialCardView cardUpdate;
    private TextView tvUpdateMessage;
    private UpdateInfo pendingUpdateInfo;
    private VMStore vmStore;
    private DiskStore diskStore;
    private NetworkStore networkStore;
    private final SummaryData data = new SummaryData();
    private final Runnable refreshRunnable = this::scheduleRefresh;
    private DaemonHelper daemon;

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_home;
    }

    @Override
    public int getTitleResId() {
        return R.string.app_name;
    }

    @Override
    protected @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_home;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvDaemonStatus = view.findViewById(R.id.tv_daemon_status);
        tvDaemonPid = view.findViewById(R.id.tv_daemon_pid);
        ivDaemonCheck = view.findViewById(R.id.iv_daemon_check);
        cardDaemon = view.findViewById(R.id.card_daemon);
        tvVMSummary = view.findViewById(R.id.tv_vm_summary);
        tvDiskSummary = view.findViewById(R.id.tv_disk_summary);
        tvNetworkSummary = view.findViewById(R.id.tv_network_summary);
        tvKernelVersion = view.findViewById(R.id.tv_kernel_version);
        tvSocModel = view.findViewById(R.id.tv_soc_model);
        ivRootStatus = view.findViewById(R.id.iv_root_status);
        ivVirtStatus = view.findViewById(R.id.iv_virt_status);
        ivHugeStatus = view.findViewById(R.id.iv_huge_status);
        tvRootStatus = view.findViewById(R.id.tv_root_status);
        tvVirtStatus = view.findViewById(R.id.tv_virt_status);
        tvHugeStatus = view.findViewById(R.id.tv_huge_status);
        btnReleaseHuge = view.findViewById(R.id.btn_release_huge);
        btnAcquireHuge = view.findViewById(R.id.btn_acquire_huge);
        btnReleaseHuge.setOnClickListener(v -> confirmReleaseHuge());
        btnAcquireHuge.setOnClickListener(v -> acquireHuge());
        defaultRuntimeColor = tvRootStatus.getCurrentTextColor();
        tvUpdateMessage = view.findViewById(R.id.tv_update_message);
        cardUpdate = view.findViewById(R.id.card_update);
        vmStore = new VMStore();
        diskStore = new DiskStore();
        networkStore = new NetworkStore();
        var ui = UIContext.fromFragment(this);
        daemon = new DaemonHelper(ui);
        daemon.setOnRefreshDaemonStatus(this::onDaemonStatusChanged);
        ivDaemonCheck.setImageResource(R.drawable.ic_daemon_checking);
        cardDaemon.setCardBackgroundColor(ContextCompat.getColor(
            requireContext(), R.color.daemon_card_background_checking
        ));
        cardDaemon.setOnClickListener(this::showDaemonMenu);
        view.findViewById(R.id.card_vm).setOnClickListener(v -> navigateToTab(R.id.nav_vm));
        view.findViewById(R.id.card_disk).setOnClickListener(v -> navigateToTab(R.id.nav_disk));
        view.findViewById(R.id.card_network).setOnClickListener(v -> navigateToTab(R.id.nav_network));
        view.findViewById(R.id.card_wizard).setOnClickListener(v -> openWizard());
        view.findViewById(R.id.card_import).setOnClickListener(v -> openImport());
        view.findViewById(R.id.card_help).setOnClickListener(v -> openLink(getString(R.string.home_card_help_url)));
        view.findViewById(R.id.card_feedback).setOnClickListener(v -> openLink(GITHUB_ISSUE_URL));
        cardUpdate.setOnClickListener(v -> showUpdateDialog());
        checkForUpdate();
    }

    private void checkForUpdate() {
        var ctx = requireContext();
        if (!MainSettingsFragment.isAutoCheckUpdateEnabled(ctx)) return;
        new VersionCheck().check(ctx, new VersionCheck.Callback() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                if (!isAdded()) return;
                pendingUpdateInfo = info;
                tvUpdateMessage.setText(getString(
                    R.string.home_card_update_available, info.getVersion()));
                cardUpdate.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNoUpdate() {
                if (!isAdded()) return;
                cardUpdate.setVisibility(View.GONE);
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (!isAdded()) return;
                cardUpdate.setVisibility(View.GONE);
            }
        });
    }

    private void showUpdateDialog() {
        if (pendingUpdateInfo == null) return;
        new UpdateDialog(requireContext(), pendingUpdateInfo).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        DaemonConnection.getInstance().addListener(this);
        mainHandler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        DaemonConnection.getInstance().removeListener(this);
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private void scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable);
        runOnPool(() -> {
            loadSummaryData();
            mainHandler.post(() -> {
                updateSummaryView();
                mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
            });
        });
    }

    public void loadSummaryData() {
        try {
            data.daemonPid = DaemonHelper.readPid();
            data.daemonRunning = DaemonHelper.isDaemonRunning();
        } catch (Exception e) {
            data.daemonPid = -1;
            data.daemonRunning = false;
        }
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Exception e) {
            return;
        }
        vmStore.load(ctx);
        vmStore.removeMalformedSharedCopies(ctx);
        diskStore.load(ctx);
        networkStore.load(ctx);
        data.vmTotal = vmStore.size();
        data.diskTotal = diskStore.size();
        data.networkTotal = networkStore.size();
        long totalVirtual = 0;
        for (int i = 0; i < diskStore.size(); i++) {
            try {
                var info = ImageUtils.getImageInfo(diskStore.get(i).getFullPath());
                long vs = info.optLong("virtual-size", -1);
                if (vs > 0) totalVirtual += vs;
            } catch (Exception ignored) {
            }
        }
        data.diskVirtual = totalVirtual;
        var conn = DaemonConnection.getInstance();
        var latch = new CountDownLatch(2);
        conn.buildRequest("vm_list")
            .onResponse(resp -> {
                data.vmRunning = countRunning(resp);
                latch.countDown();
            })
            .onError(e -> {
                data.vmRunning = 0;
                latch.countDown();
            })
            .invoke();
        conn.buildRequest("network_list")
            .onResponse(resp -> {
                data.networkActive = countRunning(resp);
                latch.countDown();
            })
            .onError(e -> {
                data.networkActive = 0;
                latch.countDown();
            })
            .invoke();

        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        var kv = System.getProperty("os.version");
        data.kernelVersion = kv != null ? kv : "N/A";
        try {
            var socModel = QcomChipName.getCurrentSoC();
            var chipName = new QcomChipName(ctx);
            data.socModel = chipName.lookupChipName(socModel);
        } catch (Exception e) {
            data.socModel = "N/A";
        }
        loadRuntimeStatus(ctx);
        data.rootState = rootState;
        data.virtLabel = virtLabel;
        data.virtAvailable = virtAvailable;
        data.virtHardware = virtHardware;
        loadHugePageStatus();
        data.hugeAvailPages = hugeAvailPages;
        data.hugeWantPages = hugeWantPages;
    }

    /**
     * pool_avail / pool_want from the module, in 2 MB pages. The parameters are root-only, so
     * this is a su round trip; the 5 s cache keeps it off the 3 s home refresh's hot path.
     */
    private static synchronized void loadHugePageStatus() {
        long now = SystemClock.elapsedRealtime();
        if (hugeCheckedAt != 0 && now - hugeCheckedAt < HUGE_POOL_TTL_MS) return;
        hugeCheckedAt = now;
        var result = RunUtils.runQuiet(
            "cat /sys/module/gh_hugepage_reserve/parameters/pool_avail; "
                + "cat /sys/module/gh_hugepage_reserve/parameters/pool_want");
        var lines = result.getOutString().trim().split("\n");
        hugeAvailPages = lines.length > 0 ? parsePoolPages(lines[0]) : -1;
        hugeWantPages = lines.length > 1 ? parsePoolPages(lines[1]) : -1;
    }

    private static long parsePoolPages(@NonNull String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Root and hypervisor checks are host facts, not UI state: probe once per process and reuse
     * the answer. Running {@code su -c id -u} every three seconds would prompt the root manager
     * repeatedly, and {@link VMHypervisor#isSupported()} already has a CPU fallback when the
     * device node is not stat-able from the app uid.
     */
    private static synchronized void loadRuntimeStatus(@NonNull Context ctx) {
        if (rootState == ROOT_UNKNOWN) {
            if (RunUtils.isNoRootMode()) {
                rootState = ROOT_NO_MODE;
            } else {
                var result = RunUtils.runQuiet("id -u");
                rootState = result.isSuccess() && "0".equals(result.getOutString().trim())
                    ? ROOT_GRANTED : ROOT_DENIED;
            }
        }
        if (virtLabel == null) {
            var hypervisor = VMHypervisor.findPreferredHypervisor(VMBackend.CROSVM);
            if (hypervisor == null) {
                virtLabel = ctx.getString(R.string.home_virt_none);
                virtAvailable = false;
                virtHardware = false;
            } else if (hypervisor == VMHypervisor.SOFT) {
                virtLabel = ctx.getString(R.string.home_virt_software);
                virtAvailable = true;
                virtHardware = false;
            } else {
                virtLabel = ctx.getString(R.string.home_virt_hardware,
                    hypervisor.getDisplayString(ctx));
                virtAvailable = true;
                virtHardware = true;
            }
        }
    }

    private static int resolveThemeColor(@NonNull Context ctx, int attr) {
        var value = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private void updateRuntimeRows(@NonNull Context ctx) {
        int rootText;
        int rootColor;
        switch (data.rootState) {
            case ROOT_GRANTED:
                rootText = R.string.home_root_granted;
                rootColor = resolveThemeColor(ctx, androidx.appcompat.R.attr.colorPrimary);
                break;
            case ROOT_DENIED:
                rootText = R.string.home_root_denied;
                // Unauthorized is a state, not a failure: keep the row in the same
                // default color as "unknown"/"no mode" instead of painting it red.
                rootColor = defaultRuntimeColor;
                break;
            case ROOT_NO_MODE:
                rootText = R.string.home_root_no_mode;
                rootColor = defaultRuntimeColor;
                break;
            default:
                rootText = R.string.home_root_unknown;
                rootColor = defaultRuntimeColor;
                break;
        }
        tvRootStatus.setText(rootText);
        tvRootStatus.setTextColor(rootColor);
        ivRootStatus.setImageTintList(ColorStateList.valueOf(rootColor));

        int virtColor;
        if (!data.virtAvailable)
            virtColor = ContextCompat.getColor(ctx, R.color.vnc_status_error);
        else if (data.virtHardware)
            virtColor = resolveThemeColor(ctx, androidx.appcompat.R.attr.colorPrimary);
        else
            virtColor = defaultRuntimeColor;
        tvVirtStatus.setText(data.virtLabel == null || data.virtLabel.isEmpty()
            ? getString(R.string.home_virt_none) : data.virtLabel);
        tvVirtStatus.setTextColor(virtColor);
        ivVirtStatus.setImageTintList(ColorStateList.valueOf(virtColor));

        int hugeColor;
        if (data.hugeAvailPages < 0) {
            tvHugeStatus.setText(R.string.home_huge_none);
            hugeColor = ContextCompat.getColor(ctx, R.color.vnc_status_error);
        } else if (data.hugeWantPages > 0) {
            tvHugeStatus.setText(getString(R.string.home_huge_value,
                MemoryUtils.formatGb(data.hugeAvailPages * 2),
                MemoryUtils.formatGb(data.hugeWantPages * 2)));
            hugeColor = resolveThemeColor(ctx, androidx.appcompat.R.attr.colorPrimary);
        } else {
            tvHugeStatus.setText(getString(R.string.home_huge_avail,
                MemoryUtils.formatGb(data.hugeAvailPages * 2)));
            hugeColor = defaultRuntimeColor;
        }
        tvHugeStatus.setTextColor(hugeColor);
        ivHugeStatus.setImageTintList(ColorStateList.valueOf(hugeColor));

        boolean hugeReady = data.hugeAvailPages >= 0 && data.vmRunning == 0 && !hugeBusy;
        btnReleaseHuge.setEnabled(hugeReady);
        btnAcquireHuge.setEnabled(hugeReady);
    }

    public void updateSummaryView() {
        if (!isAdded()) return;
        var ctx = requireContext();
        tvDaemonStatus.setText(data.daemonRunning ?
            R.string.home_card_daemon_running :
            R.string.home_card_daemon_stopped);
        ivDaemonCheck.setImageResource(data.daemonRunning ?
            R.drawable.ic_daemon_running :
            R.drawable.ic_daemon_stopped);
        cardDaemon.setCardBackgroundColor(ContextCompat.getColor(ctx, data.daemonRunning ?
            R.color.daemon_card_background_running :
            R.color.daemon_card_background_stopped));
        if (data.daemonRunning && data.daemonPid > 0) {
            tvDaemonPid.setText(getString(R.string.home_card_daemon_pid, data.daemonPid));
            tvDaemonPid.setVisibility(View.VISIBLE);
        } else {
            tvDaemonPid.setVisibility(View.GONE);
        }
        tvVMSummary.setText(data.vmTotal == 0 && data.vmRunning == 0 ?
            getString(R.string.home_card_vm_empty) :
            getString(R.string.home_card_vm_summary, data.vmRunning, data.vmTotal));
        if (data.diskTotal == 0) {
            tvDiskSummary.setText(getString(R.string.home_card_disk_empty));
        } else {
            tvDiskSummary.setText(getString(
                R.string.home_card_disk_summary,
                data.diskTotal,
                formatSize(data.diskVirtual)
            ));
        }
        if (data.networkTotal == 0 && data.networkActive == 0) {
            tvNetworkSummary.setText(getString(R.string.home_card_network_empty));
        } else {
            tvNetworkSummary.setText(getString(
                R.string.home_card_network_summary,
                data.networkActive, data.networkTotal
            ));
        }
        tvKernelVersion.setText(data.kernelVersion);
        tvSocModel.setText(data.socModel);
        updateRuntimeRows(ctx);
    }

    private void confirmReleaseHuge() {
        if (hugeBusy) return;
        var ctx = requireContext();
        if (data.vmRunning > 0) {
            Toast.makeText(ctx, R.string.home_huge_vm_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (data.hugeAvailPages < 0) {
            Toast.makeText(ctx, R.string.home_huge_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_huge_release_confirm_title)
            .setMessage(getString(R.string.home_huge_release_confirm_msg,
                MemoryUtils.formatGb(Math.max(0, data.hugeAvailPages) * 2)))
            .setPositiveButton(R.string.home_huge_release, (dialog, which) -> runHugeAction(true))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void acquireHuge() {
        if (hugeBusy) return;
        var ctx = requireContext();
        if (data.vmRunning > 0) {
            Toast.makeText(ctx, R.string.home_huge_vm_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (data.hugeAvailPages < 0) {
            Toast.makeText(ctx, R.string.home_huge_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        runHugeAction(false);
    }

    /**
     * Release: soft-disable (pool_want=0), which returns the 2 MB pages to the system while
     * keeping the module loaded. Acquire: restore the target and run the module's acquire worker.
     * Both refuse while a VM is running; the pool is that VM's guest memory.
     */
    private void runHugeAction(boolean release) {
        hugeBusy = true;
        if (release) {
            btnReleaseHuge.setText(R.string.home_huge_release_running);
        } else {
            btnAcquireHuge.setText(R.string.home_huge_acquire_running);
        }
        btnReleaseHuge.setEnabled(false);
        btnAcquireHuge.setEnabled(false);
        runOnPool(() -> {
            long avail;
            if (release) {
                RunUtils.runQuiet("echo 0 > /sys/module/gh_hugepage_reserve/parameters/acquire");
                RunUtils.runQuiet("echo 0 > /sys/module/gh_hugepage_reserve/parameters/pool_want");
                avail = waitForPoolPages(0, 10000);
            } else {
                RunUtils.runQuiet("echo 2048 > /sys/module/gh_hugepage_reserve/parameters/pool_want");
                PoolPreflight.acquire(2);
                avail = waitForPoolPages(512, 30000);
            }
            hugeCheckedAt = 0;
            loadHugePageStatus();
            long result = hugeAvailPages >= 0 ? hugeAvailPages : avail;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                hugeBusy = false;
                btnReleaseHuge.setText(R.string.home_huge_release);
                btnAcquireHuge.setText(R.string.home_huge_acquire);
                data.hugeAvailPages = hugeAvailPages;
                data.hugeWantPages = hugeWantPages;
                updateSummaryView();
                Toast.makeText(requireContext(),
                    release ? getString(R.string.home_huge_release_done)
                        : getString(R.string.home_huge_acquire_done,
                            MemoryUtils.formatGb(Math.max(0, result) * 2)),
                    Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Poll pool_avail until it reaches target pages (0 = drained) or the timeout expires. */
    private static long waitForPoolPages(long target, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        long value = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            var result = RunUtils.runQuiet(
                "cat /sys/module/gh_hugepage_reserve/parameters/pool_avail");
            try {
                value = Long.parseLong(result.getOutString().trim());
            } catch (NumberFormatException e) {
                value = -1;
            }
            if (target == 0 ? value >= 0 && value <= 1 : value >= target) return value;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return value;
    }

    private void onDaemonStatusChanged(boolean running) {
        data.daemonRunning = running;
        updateSummaryView();
    }

    private int countRunning(@NonNull JSONObject resp) {
        var arr = resp.optJSONArray("data");
        if (arr == null) return 0;
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            var obj = arr.optJSONObject(i);
            if (obj != null && obj.optString("state").equalsIgnoreCase("running"))
                count++;
        }
        return count;
    }

    private void showDaemonMenu(View anchor) {
        var popup = new MaterialMenu(requireContext(), anchor);
        popup.inflate(R.menu.menu_daemon_operation);
        popup.setItemVisible(R.id.menu_daemon_start, !data.daemonRunning);
        popup.setItemVisible(R.id.menu_daemon_stop, data.daemonRunning);
        popup.setItemVisible(R.id.menu_daemon_restart, data.daemonRunning);
        popup.setOnMenuItemClickListener(this::onDaemonMenuClicked);
        popup.show();
    }

    private boolean onDaemonMenuClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_daemon_start) {
            daemon.asyncStartDaemon();
            return true;
        } else if (id == R.id.menu_daemon_stop) {
            daemon.asyncStopDaemon();
            return true;
        } else if (id == R.id.menu_daemon_restart) {
            daemon.asyncRestartDaemon();
            return true;
        }
        return false;
    }

    private void navigateToTab(int navId) {
        var act = getActivity();
        if (act == null) return;
        var bottomNav = act.findViewById(R.id.bottom_nav);
        if (bottomNav instanceof BottomNavigationView) {
            ((BottomNavigationView) bottomNav).setSelectedItemId(navId);
        }
    }

    private void openWizard() {
        VMCreateMenu.show(requireContext());
    }

    private void openImport() {
        startActivity(new Intent(requireContext(), VMPkgImportActivity.class));
    }

    private void openLink(String url) {
        startActivity(new Intent(ACTION_VIEW, Uri.parse(url)));
    }

    @Override
    public void refreshView() {
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.post(refreshRunnable);
    }

    @Override
    public void onDaemonEvent(JSONObject data) {
        var inner = data.optJSONObject("data");
        if (inner != null && inner.optString("event").equals("output")) return;
        refreshView();
    }

    @Override
    public void onDaemonConnected() {
        refreshView();
    }

    @Override
    public void onDaemonDisconnected() {
        refreshView();
    }

    private static class SummaryData {
        boolean daemonRunning = false;
        int daemonPid = -1;
        int vmTotal = 0;
        int vmRunning = 0;
        int diskTotal = 0;
        long diskVirtual = 0;
        int networkTotal = 0;
        int networkActive = 0;
        String kernelVersion = "N/A";
        String socModel = "N/A";
        int rootState = ROOT_UNKNOWN;
        String virtLabel = "";
        boolean virtAvailable = false;
        boolean virtHardware = false;
        long hugeAvailPages = -1;
        long hugeWantPages = -1;
    }
}
