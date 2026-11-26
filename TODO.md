# TODO / Known issues

**Status snapshot: 2026-11-25.** This is the honest list of work that is not finished in this
fork, including options the UI already shows but the backend cannot honour. It is meant to be
kept current with every release. The day-by-day engineering record is kept locally by the
maintainer and is not published; [`docs/CHANGELOG.md`](docs/CHANGELOG.md) is the user-facing
changelog.

[中文摘要](#中文摘要)

## 1. Options listed in the UI but not implemented

These are deliberately visible (greyed or marked "not implemented") rather than hidden, so the
menus describe what the upstream project aims at. Do not remove them without a replacement.

| Area | What is missing | Where |
|---|---|---|
| **VirGL OpenGL (virgl2)** | The GL render mode is listed but refused. Host vrend can run it, but the CPU-copy scanout cannot read the GL resources back, and Windows has no matching guest driver. This device's accelerated path is Native Context (`drm2kgsl`). | `ui/vm/edit/graphics/VMEditGraphicsTab.java`, `daemon/vm/backend/CrosvmBackendInstance.java` |
| **PanVK** | Both the render provider (`VK_PANVK`) and the display blit provider (`PANVK`) are listed and refused. PanVK targets Mali; this device is Adreno and uses Turnip. | `lib/store/vm/GpuProvider.java`, `lib/store/vm/GpuBlitProvider.java` |
| **Zero Copy display transport** | Offered for `gpu-0` on the native display, but `DisplayTransportCap.isImplemented(NATIVE)` only accepts CPU and GPU copy. The sink-lends-its-target path is not built. | `lib/store/vm/DisplayTransportCap.java` |
| **Intel HDA** | Peripheral entry exists but is unavailable: crosvm emulates no HDA controller, and QEMU's HDA is not wired either. | `lib/store/vm/PeripheralType.java`, `daemon/vm/backend/QemuBackendInstance.java` |
| **virtio-camera** | Peripheral entry exists but is unavailable: the host Camera2 half exists, the crosvm virtio-media capture device does not. | `lib/store/vm/PeripheralType.java` |
| **xHCI port count** | The picker accepts 0-15, but crosvm's xHCI is fixed at 8 USB2 + 8 USB3; only QEMU honours the number. The card warns in red. | `ui/vm/edit/peripheral/XhciPortCount.java`, `lib/store/vm/VMXhciConfig.java` |
| **QEMU audio detail** | Only on/off is plumbed; endpoint, mode and buffer settings are crosvm concepts and are ignored. Intel HDA is not implemented, native display is refused by the editor. | `daemon/vm/backend/QemuBackendInstance.java` |
| **9P shared directories** | The code explicitly falls back to virtio-fs; there is no 9P transport. | `daemon/vm/backend/CrosvmBackendInstance.java` |

## 2. Known issues on the test device

- **Windows 3D is guest-driver-bound.** The image's `virtio_wddm_rs.sys` (20.15.1.1220) never
  sends `SET_SCANOUT`; the community build-43 driver BSODs this Adreno 840 with
  `WHEA_UNCORRECTABLE_ERROR (0x124)`. The stable configuration is `gpu-0` off, `simplefb` +
  VNC 5909. The host side (drm2kgsl, vrend, readback, VNC) is verified.
- **Gunyah `GH-SHIM` memory acceptance is fragile.** After repeated starts, crosvm can end in
  `MEM_ACCEPT (error 0x15)` or sit in `reclaim_throttle` with VNC 5909 not listening, even with
  several GB of `MemAvailable`. A full reboot clears the Gunyah RM state; a soft reboot does
  not. The `gh_hugepage_reserve` module is **needed** for the LEND mTHP path on this kernel:
  1 GB needs ~512 pages, 1.5 GB ~768, 2 GB ~1024; the runtime pool reached ~709 pages, while a
  boot-time acquire (`boot_acquire=3`) reached 1536 pages and the 2 GB VM booted. Keep the
  module and its pool sized to the VM; the home huge-page row shows the actual numbers.
- **Compressed qcow2 boot is verified.** zlib compression of `26h2.qcow2` succeeded
  (`qemu-img info` reports `compression type: zlib`, `corrupt: false`, 39.05 GiB -> 8.31 GiB);
  the compressed image booted to the Windows 11 desktop through crosvm's flate2/zlib-rs reader.
- **ISO/UEFI boot is verified on the happy path.** The storage editor mounts an `.iso` as a
  CDROM (`DiskBus.CDROM`, `--scsi-block ...,type=cdrom`); with the GXDE ISO first and the
  Windows disk second, the firmware booted the ISO's GRUB. There is still no app-level UEFI
  boot-order picker; the firmware Boot Manager (Esc/F2) remains the way to override it.
- **Duplicate VMs are fixed at the source.** `VMAutoDetect` (run on VM-list resume) checked only
  each VM's boot image, so a second disk attached to an existing VM was treated as a new image
  and auto-created a 512 MB `(N)` VM whenever it was missing (deleting it only made the
  next refresh recreate it). It now checks every disk path.
  `VMImportTask` additionally refuses packages whose disk payload did not land, and `VMStore`
  cleans old leftovers. Verified across refreshes.
- **Compressed qcow2 writes grow the file again.** Compressed clusters are read-only; a guest
  write triggers decompress + copy-on-write. That is inherent to qcow2 compression and should
  be stated in the disk UI help.
- **Update check needs a public repository.** `VersionCheck` calls
  `api.github.com/repos/mf9593/DroidVM/releases/latest`. A missing repository, a repository
  without releases (404), or the unauthenticated rate limit will make the check fail; private
  repositories need a token this build does not ship.
- **Release notes are plain text.** The GitHub release body is shown in the update dialog as-is;
  Markdown emphasis/links are not rendered.

## 3. Open-source and release tasks

- [x] Public GitHub repository `mf9593/DroidVM` created by the maintainer.
- [ ] Push this tree, including `FORK.md`, `TODO.md`, the public changelog, and the update-check
      changes (in progress). The `app/src/main/assets/prebuilts` submodule and the prebuilt tar are generated
      by the prebuilt pipeline; do not commit a hand-built tar.
- [ ] Add the release signing key as the `APP_SIGN_KEY_JKS` secret (see
      `.github/workflows/release.yml`); do not commit a keystore.
- [ ] Publish the first release: tag `v1.0.1` for users still on 1.0.0. This build is
      `1.0.1` / `10001`; the next update is `v1.0.2`. `VersionCheck` parses
      `major.minor.patch` into the `10000/100/1` scheme.
- [ ] Decide whether the fork keeps the upstream `api.classfun.cn` services for non-update
      features; currently only the update check moved to GitHub.
- [ ] Add a short "Fork" section to the GitHub repository description and pin `FORK.md`.
- [ ] Keep `docs/CHANGELOG.md` and the in-app changelog page in sync for each release.

## 4. Good first issues

- Add an app-level UEFI boot-order picker (or a documented "press Esc/F2" flow with a VNC
  keyboard hint) and test CD-ROM vs disk priority.
- Add a compressed-qcow2 boot test to CI or a documented manual test.
- Make the changelog page load `docs/CHANGELOG.md` from the repository at build time instead of
  hand-maintained string resources.
- Surface the update checker's HTTP status (404/403/rate limit) in the settings row instead of a
  generic "failed" toast.
- Strip Markdown from the GitHub release body before showing it in the update dialog.

## 中文摘要

- **UI 已列但未实现**：virgl2/OpenGL、PanVK（渲染 + 显示 blit）、Zero Copy 传输、
  Intel HDA、virtio-camera、crosvm 的 xHCI 端口数（固定 8/8）、QEMU 音频细节、9P
  （实际走 virtio-fs）。这些大多是上游明确标注的未完成项，不要直接删掉入口。
- **本机已知问题**：Windows 3D 卡在 guest 驱动（自带驱动不 `SET_SCANOUT`，build-43
  蓝屏 0x124）；Gunyah `GH-SHIM` 在反复启停后会 `MEM_ACCEPT 0x15` 或 `reclaim_throttle`，
  空闲内存足够也可能卡，完整重启可清 RM（软重启不行）；压缩 qcow2 **已验证可启动 Windows 11 桌面**；ISO/UEFI 已实测固件优先启动 CD；编辑器仍无启动顺序选择。
- **开源/发布待办**：创建公开仓库 `mf9593/DroidVM`（或改 `Constants` 里的 owner/repo）、
  推送文档、配置 `APP_SIGN_KEY_JKS`、打 tag 并附 APK（本构建是 `1.0.1`/`10001`，下一个
  更新用 `v1.0.2`）、同步 `docs/CHANGELOG.md` 与 App 内更新日志。
- **更新检查**：只读公开仓库的 GitHub Releases；404（没有 release）视为“无更新”，
  私有仓库和限流会失败；release body 原样显示，不渲染 Markdown。
