# 签名配置

[返回首页](../README.md) · [开发文档](DEVELOPMENT.md) · [配置模板](../signing.properties.example)

项目在构建时读取根目录下的 `signing.properties`。仓库公开说明和模板，实际口令及私钥由构建者在本地配置。

## 1. 准备签名密钥

已有 Android 发布密钥时可以直接使用。首次创建可运行以下命令，按提示输入密码及证书信息：

```bash
keytool -genkeypair -v -keystore car-wireless-release.jks -alias carwireless -keyalg RSA -keysize 2048 -validity 10000
```

生成的 `.jks` 用于给自行构建的 APK 签名，应妥善保存。后续更新使用同一密钥，才能覆盖安装已有版本。

## 2. 创建本地配置

在项目根目录复制模板：

```powershell
# Windows PowerShell
Copy-Item signing.properties.example signing.properties
```

```bash
# macOS / Linux
cp signing.properties.example signing.properties
```

编辑 `signing.properties`，填写自己的路径和口令：

```properties
storeFile=C:/keys/car-wireless-release.jks
storePassword=replace_with_your_store_password
keyAlias=carwireless
keyPassword=replace_with_your_key_password
```

| 字段 | 含义 |
| --- | --- |
| `storeFile` | keystore 文件路径，建议使用绝对路径 |
| `storePassword` | keystore 的访问密码 |
| `keyAlias` | 签名密钥别名，需与 keystore 中的条目一致 |
| `keyPassword` | 对应密钥的密码；根据 keystore 类型，可能需与仓库密码相同 |

Windows 路径使用 `/`，例如 `C:/keys/car-wireless-release.jks`；Properties 文件中的反斜杠需要转义。macOS / Linux 可以填写 `/home/yourname/keys/car-wireless-release.jks`。模板中的占位值不能直接用于发布构建。

`signing.properties`、`*.jks` 和 `*.keystore` 已被 `.gitignore` 排除。签名说明、示例配置与公开证书指纹可以同步到仓库。

## 3. 构建与签名方案

`app/build.gradle` 的 release 签名配置已设置：

| 方案 | 配置 | 用途 |
| --- | --- | --- |
| v1（JAR） | 开启 | 供 Android 4.2.2 等旧系统验证 |
| v2 | 开启 | 供支持 v2 的系统验证 |
| v3 / v4 | 关闭 | 当前发布配置不使用 |

Android 4.2.2 必须有有效的 v1 签名。项目同时保留 v2；旧系统本身不要求 v2。

```powershell
.\gradlew.bat assembleRelease
```

```bash
./gradlew assembleRelease
```

APK 输出为 `app/build/outputs/apk/release/app-release.apk`。

## 4. 验证安装包

Windows PowerShell：

```powershell
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$apk = 'app/build/outputs/apk/release/app-release.apk'
& "$buildTools/apksigner.bat" verify --verbose --print-certs $apk
& "$buildTools/aapt2.exe" dump badging $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
```

macOS / Linux：

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app/build/outputs/apk/release/app-release.apk
```

应确认签名验证成功，v1 / v2 为 `true`，包名为 `com.carwireless.toolbox`，最低 SDK 为 17，并核对版本及签名证书。

### v2.9.9 发布包的公开信息

| 项目 | 值 |
| --- | --- |
| 版本 | 2.9.9（versionCode 34） |
| 文件名 | `车机无线助手-v2.9.9.apk` |
| 大小 | 64,761 字节 |
| 签名 | v1 + v2，RSA 2048 位 |

APK SHA-256：

```text
92b6086da704a8d88d11551c2dfd3fc478800931cdee71e7fc860e9b178c7ad7
```

签名证书 SHA-256：

```text
ee95678acb21701eeffae7e681efb0fe1a938c67eb7e9e5597f1a0057bce63c8
```

证书指纹是公开的校验信息，无法用来给其他安装包签名。

## 覆盖安装与签名不一致

Android 覆盖安装要求包名和签名一致。若 `adb install -r` 返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，应先核对新旧 APK 的签名证书。

自行生成的密钥无法签出与本项目 Releases 相同签名的 APK。使用自行构建版本时，需要继续使用自己的原密钥；从其他签名的版本切换时，通常需要先卸载原应用再安装，这会清除应用配置，应提前记录热点设置。
