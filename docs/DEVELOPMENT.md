# 开发文档

[返回首页](../README.md) · [签名配置](SIGNING.md) · [更新记录](../CHANGELOG.md)

## 项目参数

| 项目 | 当前配置 |
| --- | --- |
| 应用名称 / 包名 | 车机无线助手 / `com.carwireless.toolbox` |
| 版本 | `versionName 2.9.9` / `versionCode 34` |
| 目标设备 | BYD MX9，Android 4.2.2（API 17） |
| 目标显示 | 横屏 1024 × 600，density 160 |
| Android SDK | `minSdk 17` / `targetSdk 28` / `compileSdk 35` |
| 构建工具 | Gradle 9.5.0 / AGP 8.9.2 / Build Tools 36.0.0 |
| Java | 已有构建环境为 JDK 26，源码和字节码兼容级别为 Java 8 |
| 依赖 | 无第三方运行时依赖，不使用 AndroidX |

构建配置分别位于 `gradle/wrapper/gradle-wrapper.properties`、`build.gradle`、`app/build.gradle`。Java 编译级别不等于 Android 运行时提供的 Java API 级别。

## 构建环境与命令

安装 Android SDK Platform 35、Build Tools 36.0.0 和 JDK，设置 `JAVA_HOME`、`ANDROID_HOME`。首次构建需要网络以下载 Gradle 和 Android 构建插件；应用本身的文件传输在局域网内完成。

先按[签名配置](SIGNING.md)准备密钥和项目根目录下的 `signing.properties`，再运行：

```powershell
# Windows PowerShell
.\gradlew.bat --version
.\gradlew.bat assembleRelease
```

```bash
# macOS / Linux
./gradlew --version
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

如果出现 `class file major version 70`，说明涉及 Java 26 字节码。先确认 `JAVA_HOME` 与 `gradlew --version` 实际使用的 JDK，并检查是否混用了其他 JDK 生成的缓存。优先使用上述已有构建环境复现。

## 源码结构

```text
app/src/main/java/com/carwireless/toolbox/
├── App.java                  应用初始化、日志与崩溃捕获
├── MainActivity.java         三卡片主界面与操作调度
├── ui/                       圆环、开关、卡片与 Path 图标
├── wireless/
│   ├── WirelessService.java   状态读取、广播和轮询
│   ├── Snapshot.java          界面读取的状态快照
│   ├── HotspotController.java 热点切换与配置
│   ├── WifiController.java    Wi-Fi 控制
│   ├── ApBridge.java          旧版热点隐藏 API 的反射封装
│   ├── P2pCleaner.java        开启热点前尝试清理 P2P 状态
│   └── ClientScanner.java     ARP 和 dnsmasq 租约读取
├── core/                     步骤执行器、日志、配置与输入校验
├── boot/                     开机广播与自动开启热点
└── transfer/FileServer.java  局域网 HTTP 文件服务

app/src/main/res/raw/transfer_page.html  手机浏览器页面
```

界面通过 Java 代码构建，使用系统控件和自绘图形，无 XML 布局。在目标设备的 density 160 下，1 dp 对应 1 px；调整布局时还需检查字号、键盘弹窗和三卡片可见区域。

## 状态与操作流程

### 状态读取

`WirelessService` 通过系统广播与 2 秒轮询维护状态，界面从 `Snapshot` 读取 Wi-Fi、热点、配置及连接设备信息。广播和轮询共同处理旧 ROM 中广播缺失的情况。

`Snapshot` 当前是带有可变字段的状态对象，使用方应将其作为读取结果；它并非不可变对象。

### 串行操作

`OperationRunner` 按步骤执行操作，每步有独立超时；界面当前配置为每步 20 秒。出现错误或超时后停止后续步骤，generation 令牌阻止迟到的回调推进已结束的操作，完成回调统一派发到主线程。

热点切换不能仅以 `setWifiApEnabled()` 的返回值判定成功。`HotspotController` 最多回读 28 次，每次间隔 500 ms，确认实际热点状态后才完成步骤。

### 隐藏 API

旧热点接口及状态常量集中在 `ApBridge`。新增系统控制时应在对应控制器中完成状态确认，并通过 `ReportLog` 记录结果，避免在界面中增加另一套状态判断。

## 文件传输接口

文件服务监听端口依次尝试 `8080`、`8081`、`8088`、`9090`。当前绑定所有本机网络接口，界面提供可访问的局域网地址。无独立鉴权，使用时应限制可连接设备并在结束后关闭服务。

| 方法 | 路径 | 请求 / 响应 |
| --- | --- | --- |
| `GET` | `/` | 返回内置 HTML 页面 |
| `GET` | `/api/files` | 返回 `[{"name":"example.apk","size":123}]` |
| `PUT` | `/upload?name=example.apk` | 原始文件字节；需要 `Content-Length` |
| `GET` | `/download?name=example.apk` | 下载文件，携带 `Content-Disposition` |
| `DELETE` | `/file?name=example.apk` | 删除单个普通文件 |

查询参数中的文件名应进行 URL 编码。上传路径会被处理为文件名，下载和删除则按现存文件名查找；目录不参与下载和删除。

| 约束 | 值 |
| --- | --- |
| 存储目录 | `/sdcard/车机传输/` |
| 单文件上限 | 2 GiB |
| 上传前空间检查 | 至少剩余文件大小 + 200 MiB |
| 上传文件名长度 | UTF-8 编码后最多 200 字节，尽量保留扩展名 |
| 列表条数 | 最多 200 条 |
| 读写缓冲 | 16 KiB，流式传输 |

维护文件服务时需保留以下行为：

- 上传完成时检查实际写入字节数与 `Content-Length` 一致，失败后清理残缺文件。
- 同名文件分配使用同步和 `createNewFile()` 占位，避免并发覆盖。
- 路径解析使用文件名处理和 canonical path 校验，阻止访问存储目录以外的路径。
- 下载使用现存文件名，不对旧文件名重新截断。
- 删除仅接受普通文件，前端保留 1 秒内二次点击确认。

## Android 4.2 兼容检查

新增代码需检查 Android API 17 是否提供对应方法和类。尤其避免未经兼容处理使用 `setLetterSpacing`、`setElevation`、`setTranslationZ`、`VectorDrawable`、`java.time` 和 Stream API。DEX 字符串扫描只能作为补充，不能替代 API 检查和实机验证。

发布前使用 Build Tools 检查 APK，Windows PowerShell 示例：

```powershell
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$apk = 'app/build/outputs/apk/release/app-release.apk'
& "$buildTools/apksigner.bat" verify --verbose --print-certs $apk
& "$buildTools/aapt2.exe" dump badging $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
```

确认包名、版本、最低 SDK 及 v1/v2 签名，再在目标车机检查热点切换、配置修改、开机自启、上传、下载和删除。Android 6.0 及以上的运行时权限和新热点接口尚未适配，不能仅凭安装成功判断兼容。

## 日志与排查

`ReportLog` 在内存保留最近 300 行日志，包含热点、Wi-Fi、开机自启、文件传输、状态广播与崩溃信息。热点密码通过 `secret()` 注册后，日志消息中的匹配内容会被替换；报告的状态快照不包含密码字段。异常内容及其他设备信息仍应在公开前人工检查。

诊断报告位于 `/sdcard/CarWirelessToolbox/report-yyyyMMdd-HHmmss.txt`，包含设备信息、状态快照和操作日志。排查时先确认具体机型、Android 版本、应用版本、操作步骤和失败时间。

文件传输逻辑可通过 Android 桩类在桌面 JVM 中验证 HTTP 行为。仓库当前未提供可直接运行的桌面测试工具；如补充测试，优先覆盖中文文件名、同名并发、路径越界、截断上传、空文件、容量限制、下载和删除。热点及厂商 ROM 行为需要实机验证。

## 发布流程

1. 确认源码版本、APK 内版本和更新记录一致。
2. 校验 APK 签名、包名、SDK 和 SHA-256。
3. 提交源码及文档，为对应提交创建版本标签。
4. 在 GitHub Releases 上传命名后的 APK 与校验文件，说明适用设备、版本变化及验证范围。

APK、构建缓存、实际签名配置和私钥由 `.gitignore` 排除。公开仓库保留源码、[签名模板](../signing.properties.example)及[签名说明](SIGNING.md)，安装包通过 Releases 分发。
