# 车机无线助手（专用版）

给 BYD MX9 车机用的热点管理 + 文件传输工具。没有账号、没有网络请求、不常驻后台。

| 项 | 值 |
|---|---|
| 包名 | `com.carwireless.toolbox` |
| 目标设备 | BYD MX9，Android 4.2.2（API 17），1024×600，density 160 |
| 当前版本 | 34 / 2.9.9 |
| 体积 | 约 64 KB，纯 Java + 系统控件，无第三方依赖 |

---

## 1. 构建

环境要求：**Gradle 9.5.0 + AGP 8.9.2 + JDK 26**。

> 不要用 JDK 17 / Gradle 8.11，会报 `class file major version 70`。这是这套工具链的硬约束。

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名走 `signing.properties`（**已在 `.gitignore` 中，不入版本库**），`app/build.gradle` 里读它。首次构建时从 `signing.properties.example` 复制一份并填入自己的信息：

```properties
storeFile=/path/to/your-release.jks
storePassword=***
keyAlias=***
keyPassword=***
```

**必须 v1 + v2 同时签，v3/v4 关闭**。4.2.2 只认 v1，新工具链默认只出 v2，缺一个都装不上。

校验：

```bash
SDK=$ANDROID_HOME/build-tools/36.0.0
"$SDK/apksigner.bat" verify -v app-release.apk   # 要看到 v1 = true, v2 = true
"$SDK/aapt2.exe" dump badging app-release.apk | head -1
```

---

## 2. 目录结构

```
app/src/main/java/com/carwireless/toolbox/
├── MainActivity.java        三卡片主界面（1093 行，全工程最大）
├── App.java                 全局单例：ReportLog + 崩溃捕获
├── ui/                      纯代码绘制，无 XML 布局
│   ├── Ui.java              配色、dp、卡片/按钮 Drawable
│   ├── PowerRing.java       200dp 圆环，状态即按钮
│   ├── Toggle.java          自绘开关
│   └── IconView.java        矢量图标（手绘 Path，不用 VectorDrawable）
├── wireless/                系统状态与操作
│   ├── WirelessService.java 唯一状态源，广播 + 2s 轮询
│   ├── Snapshot.java        状态快照（不可变值对象）
│   ├── HotspotController.java / WifiController.java  开关与配置
│   ├── ApBridge.java        隐藏 API 的唯一出口
│   ├── P2pCleaner.java      开热点前清 P2P 残留（尽力而为）
│   └── ClientScanner.java   /proc/net/arp + dnsmasq 租约读已连设备
├── core/
│   ├── OperationRunner.java 串行步骤流水线，20s 总超时
│   ├── ReportLog.java       环形日志 + 导出诊断报告
│   ├── Prefs.java           开机自启配置
│   ├── Validation.java      SSID / 密码校验
│   └── Reflect.java         反射工具
├── boot/                    开机自启（Receiver + IntentService）
└── transfer/
    └── FileServer.java      局域网 HTTP 文件服务（744 行）

app/src/main/res/raw/transfer_page.html   手机端上传/下载页面（单文件，无外部依赖）
```

---

## 3. 架构

### 3.1 状态只有一个来源

界面**不直接调系统接口**，所有状态从 `WirelessService.snapshot()` 读：

```
系统广播 ─┐
          ├─→ WirelessService ──(主线程回调)──→ MainActivity.render()
2s 轮询 ──┘        │
                   └─→ Snapshot（apState / ssid / password / clients）
```

为什么广播和轮询都要：老车机的状态广播经常丢，只靠广播会出现"界面显示关着、实际开着"。轮询只盯三件事（Wi-Fi 开关、热点开关、热点配置），蓝牙和 P2P 不轮询——每 2 秒少几十次 binder 调用。

### 3.2 操作是串行流水线

所有写操作走 `OperationRunner`，不并发：

```
Step[] → 逐步执行 → 每步 20s 超时 → 任一步返回非 null 即中止
```

关键约定：**每一步都必须回读确认**。`setWifiApEnabled` 返回 `true` 只代表"请求被接受"，不代表热点起来了。`HotspotController.doSwitch()` 里那个 28 次 × 500ms 的轮询循环就是干这个的。

### 3.3 隐藏 API 只有一个出口

`setWifiApEnabled` / `getWifiApState` / `getWifiApConfiguration` 都不在公开 SDK 里，全部集中在 `ApBridge`。热点状态常量（`AP_ENABLED = 13` 等）也只在这里定义一份——历史上三处各抄一份、数字对不上，排查成本很高。

---

## 4. 文件传输

车机开热点后监听局域网端口，手机浏览器访问 `http://<车机IP>:8080` 即可双向传文件。

端口从 `8080` 起，被占用就依次试 `8081 → 8088 → 9090`，界面显示实际绑定的那个。

### 4.1 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 上传页面（`res/raw/transfer_page.html`） |
| GET | `/api/files` | 文件列表 JSON |
| PUT | `/upload?name=xxx` | 上传，body 为原始字节流 |
| GET | `/download?name=xxx` | 下载，带 `Content-Disposition` 保留中文名 |
| DELETE | `/file?name=xxx` | 删除单个文件 |

### 4.2 硬约束

| 约束 | 值 | 常量 |
|---|---|---|
| 单文件上限 | 2 GB | `MAX_FILE` |
| 剩余空间余量 | ≥ 文件大小 + 200 MB | `RESERVE` |
| 文件名上限 | 200 **字节**（UTF-8，保留扩展名） | `MAX_NAME_BYTES` |
| 存储目录 | `/sdcard/车机传输/` | `DIR_NAME` |

### 4.3 几条不能改的实现细节

- **收完必须校验 `written == length`**。客户端正常关闭（FIN）时 `read()` 返回 -1 而不抛异常，只看 `catch` 会漏掉半截文件，结果是残包 + 200。
- **`unique()` 必须 `synchronized` + `createNewFile()` 占位**。两台手机同时传同名文件时，`exists()` 判断会同时通过，两份数据交错写进同一个文件。
- **文件名按 UTF-8 字节截断，不是按字符**。ext4 上限是 255 字节，85 个汉字只有 85 字符却占 255 字节。
- **下载用 `storedName()`，上传用 `safeName()`**。下载绝不能压长度，否则老版本留下的超长文件名会 404。
- **删除只允许 `isFile()`**，目录一律 404。写错这一条，一个路径穿越请求就能删掉整个 `/sdcard`。
- **上传/下载都边读边写**（16KB buffer）。车机内存紧，整文件进内存会 OOM。

---

## 5. 兼容红线

### 5.1 1dp = 1px

1024×600 + density 160，所以 **1dp 就是 1px**。字号必须按 px 量级写，不能按常规手机的 dp 感觉估。布局横屏优先，三张卡平分整屏。

### 5.2 禁止使用的 API

API 21+ 的方法在 4.2.2 上会直接 `NoSuchMethodError`。每次打包后必须扫一遍 dex：

```bash
unzip -o -q app-release.apk -d /tmp/apkcheck && cd /tmp/apkcheck
for p in setLetterSpacing setElevation setTranslationZ VectorDrawable \
         postInvalidateOnAnimation "java/util/stream" "java/time/" "String;->join"; do
  echo "$p : $(grep -c -a -F "$p" classes*.dex 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')"
done
```

全部为 0 才算通过。同样的原因：**不用 AndroidX**（`android.useAndroidX=false`），不用 `java.time`、不用 stream、不用 lambda 之外的 Java 8 语法糖（`compileOptions` 虽写 1.8，但运行时要靠 desugar，这里一律回避）。

### 5.3 只支持 4.2 ~ 5.1

`minSdk 17` 能装到很新的系统，但**能用**的只有 API 17-22：

- **6.0+**：没有运行时权限申请，写 `/sdcard` 直接失败（文件传输、导出报告全废）
- **8.0+**：`setWifiApEnabled` 从 AOSP 移除，普通应用拿不到替代接口
- **11+**：分区存储，直写外部存储彻底不可行

不是"忘了兼容"，是这台车就停在 4.2.2，为不存在的目标做分支是纯浪费。换车要重新设计热点那层，不是加兼容分支能解决的。

---

## 6. 调试

### 6.1 日志

`ReportLog` 是环形缓冲（内存内保留最近 300 行），标签分六类：

| 标签 | 来源 |
|---|---|
| `HOTSPOT` | 热点开关、配置读写 |
| `WIFI` | Wi-Fi 开关 |
| `BOOT` | 开机自启 |
| `传输` | 文件服务（上传/下载/删除/拒绝原因） |
| `STATE` / `RECEIVER` | 状态刷新与广播异常 |
| `CRASH` | 未捕获异常 |

**密码写入前会过 `secret()` 脱敏**，导出的报告里看不到明文。

### 6.2 导出报告

界面左卡底部 → 导出到 `/sdcard/CarWirelessToolbox/report-yyyyMMdd-HHmmss.txt`，内容为设备信息 + 状态快照 + 操作日志。用户报"连不上"时先要这个文件。

### 6.3 不装车机也能测文件传输

`FileServer` 不依赖 Android 运行时（只用 `Context` 拿 `Resources`、`Environment` 拿存储路径）。用桩编译到桌面 JVM 就能跑真实 HTTP：

```bash
# 1. 建桩：android/content/Context、android/content/res/Resources、
#    android/os/Environment、com.carwireless.toolbox.R、App、ReportLog
# 2. 原样拷入 FileServer.java
javac -encoding UTF-8 -d out $(find src -name "*.java")
java -Dfile.encoding=UTF-8 -cp out Main     # start() + sleep
```

然后用 `curl` 和 raw socket 打真实请求。已覆盖的用例：中文名上传/下载、同名改 `(1)`、空文件、路径穿越、半截上传、超 2G、缺 `Content-Length`、并发同名、特殊字符名、90 汉字超长名。

**模拟器不用试**：x86 r07 镜像在 `system_server` 里必崩（`libcrypto.so bn_mul_mont` SIGSEGV），本机 AEHD 硬件加速被拒，`-accel off` 跑十分钟仍 offline。直接装车。

---

## 7. 版本

| 版本 | 内容 |
|---|---|
| 2.9.5 | 文件传输加固：短包校验、并发同名、字节级文件名、端口顺延、容量保护、fsync |
| 2.9.6 | 修复键盘弹出压扁三卡片（`adjustNothing` + 弹窗 `ADJUST_PAN`） |
| 2.9.7 | 新增下载接口；上传页面移动端重做 |
| 2.9.8 | 列表紧凑化（行高 73→50px）；新增删除（行内二次确认） |
| 2.9.9 | 删除交互改为「点一下上膛、1 秒内再点确认」 |

---

## 8. 已知限制

- 列表最多返回 200 条（2.9.5 之前是 50，传 60 个文件会有 10 个凭空消失），超出不显示
- 不支持文件夹（目录会被跳过，上传时路径会被拍平）
- 无剩余空间显示（服务端已算 200MB 余量，但没暴露给页面）
- 无回收站，删除不可恢复
- 页面不支持多选、批量操作
