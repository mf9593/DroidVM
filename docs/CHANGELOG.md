# Changelog

User-facing changes for this fork. The maintainer keeps the day-by-day engineering
record (commands, measurements, failed attempts) locally; it is not published here.

## 1.0.1 — 2026-11-25

### 中文

- **首页运行环境卡**：显示 ROOT、虚拟化和大页池实时容量，提供「释放大页 / 获取大页」；
  移除普通内存行和旧的「释放内存」按钮。
- **修复 VM 列表重复项**：自动识别此前只检查启动镜像；已挂到现有 VM 的第二块盘会被
  自动建成一个 512 MB 的 `(N)` 副本，删掉后下次刷新又会出现。现在检查全部磁盘路径。
- **VM 包导入加固**（独立修复）：磁盘负载未落盘的包不再注册残缺 VM；启动时清理旧残留。
- **验证**：zlib 压缩的 Windows 11 盘可启动到桌面；GXDE ISO 与 Windows 盘同时挂载时，
  固件优先启动 ISO 的 GRUB。
- **更新检查**：改为读取本仓库的 GitHub Releases；设置页新增本地更新日志。
- **构建**：`libvncserver` 内置并带 16 KB 页对齐；构建目录和签名可用环境变量覆盖。

### English

- Home runtime card: ROOT, virtualization and the live huge-page pool, with
  Release/Acquire huge pages. The ordinary-memory row and Free memory action are gone.
- VM list duplicate fix: auto-detect now checks every disk of every VM, not only the
  boot image, so an OS disk attached next to a boot ISO is no longer auto-created as a
  512 MB `(N)` copy (deleting it made the next refresh recreate it).
- VM package import hardening (separate fix): a package whose disk payload did not land
  is rejected, and old malformed leftovers are cleaned on startup.
- Verified: the zlib-compressed Windows 11 disk boots to the desktop; with the GXDE ISO
  and the Windows disk attached, the firmware boots the ISO GRUB first.
- Update check reads this repository's GitHub Releases; the settings page has a local
  changelog.
- Build: `libvncserver` is vendored with 16 KB page alignment; build directory and
  release signing can be overridden with environment variables.
