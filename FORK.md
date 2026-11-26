# Fork notes / 本分支说明

**English** | [中文](#中文)

## English

This repository is a fork of [DroidVM](https://github.com/Droid-VM/DroidVM), maintained by
[mf9593](https://github.com/mf9593). The app's in-built update check reads this fork's GitHub
Releases instead of the upstream API.

- Fork: <https://github.com/mf9593/DroidVM>
- Upstream: <https://github.com/Droid-VM/DroidVM>
- Changelog: [`docs/CHANGELOG.md`](docs/CHANGELOG.md)
- Unfinished work and known issues: [`TODO.md`](TODO.md)
- License: GPL-3.0-or-later with the additional permissions in `ADDITIONAL-PERMISSIONS`.
  Upstream files keep their original licenses; see `LICENSING.md`.

The maintainer keeps the day-by-day engineering record (commands, measurements, failed
attempts) locally; it is not published in this repository.

### What this fork changes

- **Home runtime card** — ROOT, virtualization and the live huge-page pool
  (`pool_avail`/`pool_want`), with **Release / Acquire huge pages**. The ordinary-memory row
  and the old Free memory action are gone; on the tested device the huge-page pool, not
  `MemAvailable`, gates a VM start.
- **VM list duplicates fixed** — `VMAutoDetect` now checks every disk of every VM, not only
  the boot image. An OS disk attached next to a boot ISO is no longer auto-created as a
  512 MB `(N)` VM (deleting it used to make the next refresh recreate it).
- **VM package import hardening** — a package whose disk payload did not land is rejected;
  `VMStore` cleans old malformed leftovers on startup.
- **Update check and privacy** — `VersionCheck` reads this fork's GitHub Releases; the privacy
  policy is v2 and names GitHub as the update endpoint.
- **Changelog page** — the settings screen has a local, date-grouped changelog.
- **Build portability** — `libvncserver` is vendored with 16 KB page alignment and the
  turbojpeg stub; `DROIDVM_BUILD_DIR` and `DROIDVM_KEYSTORE` override the build directory and
  release signing. Without a keystore the release APK is unsigned.
- **Verified on device** — the zlib-compressed Windows 11 disk boots to the desktop; with the
  GXDE ISO and the Windows disk attached, the UEFI firmware boots the ISO GRUB first. Boot
  order remains a firmware setting; the editor has no boot-order picker.
- **Windows 3D is still not usable** — the stable configuration is `gpu-0` off, `simplefb` +
  VNC 5909. See `TODO.md` for the remaining limitations.

### Building

```bash
git clone --recurse-submodules https://github.com/mf9593/DroidVM.git
cd DroidVM
JAVA_HOME=/path/to/jdk21 \
ANDROID_HOME=/path/to/android-sdk \
./gradlew assembleRelease
```

The release version is pinned in `app/build.gradle.kts` (`1.0.1` / `10001`). The APK is
written under `app/build/outputs/apk/release/` unless `DROIDVM_BUILD_DIR` is set.

## 中文

本仓库是 [DroidVM](https://github.com/Droid-VM/DroidVM) 的 fork，维护者
[mf9593](https://github.com/mf9593)。App 内置的更新检查已改为读取本分支的 GitHub Releases，
不再请求上游的 `api.classfun.cn`。

- 本分支：<https://github.com/mf9593/DroidVM>
- 上游：<https://github.com/Droid-VM/DroidVM>
- 更新日志：[`docs/CHANGELOG.md`](docs/CHANGELOG.md)
- 未完成 / 已知问题：[`TODO.md`](TODO.md)
- 许可证：GPL-3.0-or-later，附加权限见 `ADDITIONAL-PERMISSIONS`；上游文件保留原许可证，
  详见 `LICENSING.md` 与 `LICENSE.txt`。

逐日开发过程（命令、测量数据、失败路径）由维护者保存在本地，不在本仓库发布。

### 本分支改了什么

- **首页运行环境卡**：ROOT、虚拟化、大页池实时容量（`pool_avail`/`pool_want`），以及
  「释放大页 / 获取大页」。普通内存行和旧的「释放内存」按钮已移除；在测试机上决定 VM
  能否启动的是大页池，不是 `MemAvailable`。
- **修复 VM 列表重复项**：`VMAutoDetect` 现在检查每个 VM 的全部磁盘，而不只是启动镜像。
  已挂到现有 VM 的第二块盘不会再被自动建成 512 MB 的 `(N)` 副本（此前删掉后下次刷新又会出现）。
- **VM 包导入加固**：磁盘负载未落盘的包会被拒绝；`VMStore` 在启动时清理旧的残缺残留。
- **更新检查与隐私**：`VersionCheck` 读取本分支的 GitHub Releases；隐私协议升级到 v2，
  明确 GitHub 是更新检查端点。
- **更新日志页**：设置页有本地、按日期分组的更新日志。
- **构建可移植**：`libvncserver` 内置并带 16 KB 页对齐和 turbojpeg stub；
  `DROIDVM_BUILD_DIR`、`DROIDVM_KEYSTORE` 可覆盖构建目录和 release 签名。没有 keystore
  时产出未签名 APK。
- **实机验证**：zlib 压缩的 Windows 11 盘可启动到桌面；GXDE ISO 与 Windows 盘同时挂载时，
  固件优先启动 ISO 的 GRUB。启动顺序仍由 UEFI 固件决定，编辑器不提供启动顺序。
- **Windows 3D 仍不可用**：稳定配置为关闭 `gpu-0`，使用 `simplefb` + VNC 5909。
  其余限制见 `TODO.md`。

### 构建

```bash
git clone --recurse-submodules https://github.com/mf9593/DroidVM.git
cd DroidVM
JAVA_HOME=/path/to/jdk21 \
ANDROID_HOME=/path/to/android-sdk \
./gradlew assembleRelease
```

release 版本在 `app/build.gradle.kts` 固定（`1.0.1` / `10001`）。未设置
`DROIDVM_BUILD_DIR` 时 APK 输出到 `app/build/outputs/apk/release/`。
