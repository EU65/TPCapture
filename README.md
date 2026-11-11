# TPCapture

已 root 的 Android 设备上的应用流量抓包与改写工具，一个 APK 解决从抓包到改包的全部流程。

## 为什么用它

手机端抓包方案常见的三种形态，各缺一块：

| 形态 | 缺的那块 |
| --- | --- |
| 抓包 App（多为 VPN 模式） | 全局接管、系统里有 VPN 组件和图标痕迹，应用能检测；一般只能看，不能改 |
| 后端 + 电脑 | 手机要连电脑、走 adb 或端口转发，离开电脑就抓不了 |
| 纯脚本 / Magisk 模块 | 没有图形界面，目标、路由、规则全靠手写配置 |

TPCapture 把这些合成一个整体：

- **没有 VPN / TUN**。用 TPROXY 按 UID 在 mangle 表里给选中的应用打标，再由策略路由送到本机代理端口。不建虚拟网卡、不注册 VPN 服务，状态栏没有 VPN 图标，`ConnectivityManager` 之类的检测也看不到代理；只有被选中的应用受影响，其余应用照常联网。
- **纯本机**。管理器、代理后端、SQLite 记录库全在手机上，不需要电脑、adb、端口转发，root 装一次后端就能长期用。
- **全流程可视化**。选应用、启动/重载/停止、翻请求、看详情、写重写规则、导出证书、装开机自启模块、开 MCP，全部在界面里点，不碰配置文件。
- **能解密 HTTPS**。两种证书模式（内置泄漏证书 / 自签 CA），自签 CA 可导出 PEM 与 `.0` 后装进系统信任区，之后就是明文。
- **能改，不只是看**。按 UID、主机、方法、URL 匹配规则，可以拦截请求（自定义状态码和响应体），也可以改写请求和响应的状态码、头、正文，正则或字面量都行。
- **带 MCP 服务**。后端能直接起一个 MCP 服务（默认 `127.0.0.1:18765`），AI 客户端或脚本既能查询会话和记录，也能直接读、增、删、改重写规则和抓包模式，不用人工翻界面。
- **AIO**。一个 APK ≈ 抓包代理 + 请求查看器 + 改写引擎 + 配置面板 + AI 接口。

## 功能

- **按应用抓包**：在应用列表里选择目标 UID，透明代理只接管这些进程的 80/443 流量，其余应用不受影响。
- **HTTP/1.1、HTTP/2、HTTP/3**：TCP 与 QUIC 都能记录；UDP 模式可选开启。
- **请求记录**：按 UID、URL、文本、传输协议、方法筛选，可排除指定主机、只看媒体流量；支持实时刷新和分页加载历史记录。
- **详情查看**：完整元数据、请求头/响应头、请求体/响应体；gzip、deflate、zlib、brotli 自动解码，也可手动重新解压；JSON 格式化与语法高亮；图片、GIF、SVG 预览；按 GBK/UTF-16 等字符集重新解码；正文可导出到文件。
- **重写规则**：按 UID、主机、方法、URL 匹配；可拦截请求（自定义状态码与响应正文），也可改写请求/响应的状态码、头、正文（正则或字面量），规则可排序、单条启用/禁用。
- **证书模式**：内置"泄漏证书"与自签 CA 两种模式；自签 CA 可生成、查看，导出 PEM 或 `.0` 文件，不会自动装进系统。
- **会话与历史**：新建会话时归档旧数据库，可在历史数据库之间切换查看。
- **开机自启动**：一键生成 Magisk 模块，按当前目标和配置尽早启动，可查看开机日志、移除模块。
- **MCP 服务**：在后端启动 MCP 服务（默认 `127.0.0.1:18765`），供外部 MCP 客户端查询会话记录。
- **后端管理**：后端随 APK 内置，一键安装/更新；可清理 TPROXY 残留规则、修复残留操作锁。

## 环境要求

- Android 8.0（API 26）及以上
- 已 root（Magisk、KernelSU 等能提供 `su` 的环境）
- 后端二进制为 **arm64-v8a**，其他架构的设备无法运行
- 抓包后端需要 `iptables`、`ip6tables`、`iproute2`（一般系统自带）

## 使用

1. 从 [Releases](https://github.com/EU65/TPCapture/releases) 下载 APK 安装。
2. 打开应用 → **设置** → 确认后端安装目录（默认 `/data/adb/tpcapture`）→ **安装 / 更新后端**，授予 root。
3. **应用** 页选择要抓包的目标，点 **启动**。记录会出现在 **请求** 页。
4. 需要解密 HTTPS 时，在 **设置 → 证书模式** 里生成自签 CA 并导出证书，自行安装到系统信任区；部分应用有证书固定，仍然无法解密。

## 构建

依赖 JDK 17+（CI 使用 Temurin 21）和 Android SDK Platform 35、Build-Tools 35。后端二进制已作为 `app/src/main/assets/backend.tar` 内置，构建 APK 不需要 Go 环境。

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。release 开启了 R8（`minifyEnabled` + `shrinkResources`），规则见 `app/proguard-rules.pro`。

release 构建必须提供签名，否则直接报错。签名材料不入库，通过环境变量传入：

| 变量 | 说明 |
| --- | --- |
| `TPCAPTURE_KEYSTORE_FILE` | keystore 路径（必填） |
| `TPCAPTURE_KEYSTORE_PASSWORD` | keystore 口令（必填） |
| `TPCAPTURE_KEY_ALIAS` | key 别名，默认 `tpcapture` |
| `TPCAPTURE_KEY_PASSWORD` | key 口令，默认同 keystore 口令 |

本地第一次构建：

```bash
keytool -genkeypair -keystore ~/tpcapture-release.jks -storetype PKCS12 \
  -alias tpcapture -keyalg RSA -keysize 4096 -validity 10950
export TPCAPTURE_KEYSTORE_FILE=~/tpcapture-release.jks
export TPCAPTURE_KEYSTORE_PASSWORD='你的口令'
./gradlew assembleRelease
```

PowerShell 用 `$env:TPCAPTURE_KEYSTORE_FILE = "D:\path\tpcapture-release.jks"`。这个 keystore 丢了就再也发不出能覆盖安装的更新，记得备份。

`local.properties` 不入库，本地构建时自己写 `sdk.dir`。

## CI 与发布

`.github/workflows/release.yml`：

- push 到 `main`/`master`、提交 PR、手动触发：构建 release APK 并上传为构建产物。
- push `v*` 标签或手动触发：额外创建 GitHub Release，版本号取自 `app/build.gradle` 的 `versionName`。

CI 从两个仓库 secret 取签名材料：

| secret | 内容 |
| --- | --- |
| `TPCAPTURE_KEYSTORE_BASE64` | keystore 文件的 base64（单行） |
| `TPCAPTURE_KEYSTORE_PASSWORD` | keystore 口令 |

生成 base64：`base64 -w0 keystore.jks`（Windows：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore.jks"))`）。

## 目录结构

```
app/            管理器（Java 为主，规则编辑用 Compose）
app/src/main/assets/backend.tar   内置后端（arm64）
backend/        抓包后端源码（Go，TPROXY + MITM + MCP）
autostart/      Magisk 开机自启动模块模板
```

## 致谢与设计参考

- **Box for Magisk / boxproxy**：透明代理规则的组织方式，UID 目标的分组与启停流程。
- **mihomo**：按来源 UID 与进程识别流量的思路。
- **Miuix**（`top.yukonga.miuix.kmp`）：重写编辑器的控件与视觉规范，规则编辑界面基于该组件库实现。

内置的"泄漏证书"模式包含公开披露的受影响 CA 证书链，来源为 [mitmproxy PR #8500](https://github.com/mitmproxy/mitmproxy/pull/8500)。

## 作者

GPT 6.1 Terra + DeepSeek V4.1 Flash

## 说明

仅用于在自有设备上调试与分析自己的应用流量。记录可能包含令牌、Cookie 等敏感数据，请自行保管数据库；"泄漏证书"模式仅适合实验环境。
