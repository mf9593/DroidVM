# DroidVM 免Root模式修改说明

## 概述

本修改让 DroidVM 在**没有 root 权限**的情况下也能运行虚拟机，通过 gunyah hypervisor 的硬件虚拟化加速。

## 核心原理

- **gunyah** 是内核态 hypervisor（EL2），通过 ARM 硬件指令（HVC）与用户态通信，**不依赖 root**
- **crosvm** 作为用户态 VMM，需要 root 的功能可以绕过：
  - TAP 网络 → 改用 SLIRP 用户态网络
  - /dev/gh_unmovable → 关掉 hugepages 就不需要
  - cgroup → 跳过
  - sysctl 写入 → 跳过
- 需要 root 的操作在 no-root 模式下自动跳过或使用替代方案

## 修改文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `lib/run/user/UserRunContext.java` | 非root命令执行器，用 Runtime.exec() 直接执行，不经过 su |
| `lib/run/user/UserRunResult.java` | UserRunContext 的执行结果 |

### 修改文件

| 文件 | 修改内容 |
|------|----------|
| `lib/utils/RunUtils.java` | 添加 `noRootMode` 开关和 `setNoRootMode()`/`isNoRootMode()` 方法 |
| `lib/daemon/DaemonHelper.java` | no-root 模式下用 ProcessBuilder 直接启动 daemon，不经过 su |
| `daemon/vm/backend/CrosvmBackendInstance.java` | 网络改用 SLIRP (`--net user`)，跳过 cgroup 和 udmabuf 写入 |
| `daemon/vm/VMBackendInstance.java` | 跳过内存清理（写入 /proc/sys 和 /sys/kernel/mm 需要 root） |
| `daemon/network/NetworkInstance.java` | no-root 模式下跳过网络后端启动（TAP/bridge 需要 CAP_NET_ADMIN） |
| `daemon/server/ServerContext.java` | no-root 模式下跳过 pkill 进程清理 |
| `DroidVMApp.java` | 启动时检测 `files/noroot` 文件，存在则启用 no-root 模式 |
| `ui/setup/step/RootStepFragment.java` | no-root 模式下隐藏 root 检查步骤 |

## 使用方法

### 启用免Root模式

1. 构建并安装修改后的 APK
2. 创建标志文件启用 no-root 模式：
   ```bash
   adb shell "echo '' > /data/data/cn.classfun.droidvm/files/noroot"
   ```
3. 或者通过 Termux（有 root）：
   ```bash
   su -c "echo '' > /data/data/cn.classfun.droidvm/files/noroot"
   ```
4. 打开 DroidVM，root 检查步骤会自动跳过
5. 创建 VM 时设置 `hugepages: false`

### VM 配置要求

在 `vms.json` 中为每个 VM 设置：
```json
{
  "hugepages": false,
  "prepare_lend_mthp": "disabled"
}
```

### 网络说明

- no-root 模式下使用 crosvm 内置的 SLIRP 用户态网络（NAT）
- VM 可以访问外网，但外部无法直接访问 VM 内部服务
- 桥接网络、DHCP、VLAN 等高级网络功能不可用

## 限制

| 功能 | Root 模式 | No-Root 模式 |
|------|-----------|-------------|
| 硬件虚拟化（gunyah） | ✅ | ✅ |
| SLIRP 网络（NAT） | ✅ | ✅ |
| 桥接网络 | ✅ | ❌ |
| USB 直通 | ✅ | ❌ |
| GPU 加速 | ✅ | ✅（部分） |
| 大页内存 | ✅ | ❌（用 mTHP 替代） |
| cgroup CPU 隔离 | ✅ | ❌ |
| VNC 远程显示 | ✅ | ✅ |
| 原生显示 | ✅ | ✅ |

## 构建说明

### 前提条件

- Android SDK（compileSdk 37）
- Android NDK 27+
- JDK 21+
- Gradle 9.6+

### 构建步骤

```bash
cd DroidVM
# 初始化子模块
git submodule update --init --recursive
# 创建 local.properties
echo "sdk.dir=/path/to/android-sdk" > local.properties
# 构建
./gradlew assembleRelease
```

### 子模块说明

需要克隆以下子模块（GitHub 慢时可用代理）：
- `app/src/main/assets/prebuilts` - 预编译二进制（crosvm, daemon 等）
- `app/src/main/cpp/jsoncpp` - JSON 解析库
- `app/src/main/cpp/libvncserver` - VNC 服务端
- `app/src/main/cpp/udisasm` - x86 反汇编器

## 技术细节

### RunUtils 路由逻辑

```
noRootMode = true  →  UserRunContext (Runtime.exec, 无 su)
uid == 0           →  SystemRunContext (直接 shell)
uid != 0           →  RootRunContext (su 执行)
```

### crosvm 命令行差异

| 参数 | Root 模式 | No-Root 模式 |
|------|-----------|-------------|
| `--net` | `tap-name=xxx,mac=...` | `user` (SLIRP) |
| `--gpu-cgroup-path` | 设置 | 跳过 |
| `--hugepages` | 可选 | 必须 false |

### 性能影响

| 项目 | 影响 |
|------|------|
| vCPU 执行 | 无影响（gunyah 硬件加速不变） |
| 内存访问 | 5-10%（无大页，mTHP 自动处理） |
| 网络吞吐 | SLIRP 比 TAP 慢 20-30% |
| 启动速度 | 无影响 |

## 对比 QEMU

| 方案 | 原理 | 速度 | 是否需 root |
|------|------|------|------------|
| gunyah + crosvm (no-root) | 硬件虚拟化 | 接近原生 | 否 |
| QEMU TCG | 软件指令翻译 | 极慢（10-50x 慢） | 否 |
| QEMU + KVM | 硬件虚拟化 | 接近原生 | 设备不支持 |
