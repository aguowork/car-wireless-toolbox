# 车机无线助手

为 **BYD MX9 · Android 4.2.2** 车机设计的热点管理与局域网文件互传工具。打开热点、修改名称和密码、查看连接设备，以及用手机浏览器上传和下载文件，都可以在一个界面中完成。

纯 Java 与系统控件，无第三方依赖。v2.9.9 安装包约 **63.2 KiB**，无需账号，文件通过局域网直接传输。

**[下载 v2.9.9](https://github.com/aguowork/car-wireless-toolbox/releases/tag/v2.9.9)** · [所有版本](https://github.com/aguowork/car-wireless-toolbox/releases) · [开发文档](docs/DEVELOPMENT.md) · [签名配置](docs/SIGNING.md) · [更新记录](CHANGELOG.md)

## 下载与安装

在 [Releases](https://github.com/aguowork/car-wireless-toolbox/releases) 的 **Assets** 中选择显示为「车机无线助手-v2.9.9.apk」的附件，通过 U 盘或已有的传输方式复制到车机，再使用车机的安装器安装。GitHub 会过滤附件文件名中的中文，实际下载文件名为 `car-wireless-toolbox-v2.9.9.apk`。

如果车机已连接 ADB，也可以运行：

```bash
adb install -r "car-wireless-toolbox-v2.9.9.apk"
```

覆盖安装需要新旧 APK 使用相同签名。自行构建的安装包与本项目发布包签名不同，处理方式见[签名配置](docs/SIGNING.md#覆盖安装与签名不一致)。

## 功能与使用

应用采用横屏三卡片布局，目标屏幕为 **1024 × 600，density 160**。

| 卡片 | 功能 | 操作 |
| --- | --- | --- |
| 左侧：热点开关 | 开启、关闭热点；设置开机自启 | 点击圆环切换，青色表示已开启；使用底部开关设置自启 |
| 中间：热点配置 | 修改热点名称和密码；查看连接设备 | 点击名称或密码编辑，点击设备数量查看 IP、MAC 和主机名 |
| 右侧：文件传输 | 手机与车机互传文件 | 开启传输后，使用手机浏览器访问卡片显示的地址 |

### 用手机传文件

1. 在车机上开启热点和文件传输。
2. 手机连接该热点，在浏览器中打开右侧卡片显示的地址。
3. 选择文件上传，支持多选，文件按队列依次发送。
4. 在文件列表中点击下载，或执行删除。**删除需要在 1 秒内再次点击确认**；超时后恢复原状。

文件保存到车机的 `/sdcard/车机传输/`。访问端口优先使用 `8080`，被占用时依次尝试 `8081`、`8088`、`9090`，以界面显示的地址为准。手机提示热点无法上网时，请保持连接；传输不需要互联网。

### 传输限制

| 项目 | 限制 |
| --- | --- |
| 单文件大小 | 最大 2 GiB |
| 存储空间 | 上传前需至少剩余「文件大小 + 200 MiB」 |
| 文件列表 | 最多显示 200 个文件 |
| 上传文件名 | 按 UTF-8 限制为 200 字节，尽量保留扩展名 |
| 文件夹 | 不支持文件夹传输，列表跳过目录 |
| 删除与批量操作 | 无回收站；列表不支持多选批量下载或删除 |

文件传输使用局域网 HTTP，当前没有独立的登录或鉴权功能。请仅向可信设备提供热点密码，传输结束后关闭文件传输。

## 设备与系统兼容性

本项目主要面向 BYD MX9 的 Android 4.2.2 系统。其他车机 ROM 对热点接口和存储权限的实现可能不同。

| 系统 | 支持情况 |
| --- | --- |
| Android 4.2–5.1（API 17–22） | 当前实现的适用范围，其他设备需实机验证 |
| Android 6.0–7.1（API 23–25） | 未适配运行时权限，文件传输和报告导出可能无法使用 |
| Android 8.0 及以上（API 26+） | 未适配；旧热点接口和存储机制存在兼容限制 |

安装包的 `minSdk` 为 17，`targetSdk` 为 28；能够安装不代表所有功能可用。

## 从源码构建

当前项目使用 **Gradle 9.5.0、Android Gradle Plugin 8.9.2、Android SDK Platform 35、Build Tools 36.0.0**，已有构建环境使用 **JDK 26**。

1. 安装对应工具，并设置 `JAVA_HOME` 和 `ANDROID_HOME`。
2. 按[签名配置](docs/SIGNING.md)创建本地 `signing.properties`。
3. 在项目根目录执行构建：

```powershell
# Windows PowerShell
.\gradlew.bat assembleRelease
```

```bash
# macOS / Linux
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。签名模板已公开，实际签名口令和密钥保留在本地。完整环境、源码结构与验证流程见[开发文档](docs/DEVELOPMENT.md)。

## 问题反馈

可从车机界面导出诊断报告，保存位置为 `/sdcard/CarWirelessToolbox/report-yyyyMMdd-HHmmss.txt`。提交 [Issue](https://github.com/aguowork/car-wireless-toolbox/issues) 时，请附上车机型号、Android 版本、应用版本、操作步骤及相关日志。公开报告前，请检查其中的热点名称、设备 IP 和 MAC 等信息。

## 许可证

项目采用 [MIT License](LICENSE)，可按许可证使用、修改和分发。
