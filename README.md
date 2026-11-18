# 车机无线助手

一个给 **BYD MX9 车机（Android 4.2.2）** 写的热点管理 + 文件互传小工具。

车机没有应用商店，想开热点得翻进系统设置，想往车机里塞个 APK 得拔 U 盘。这个应用把这两件事做成了两个按钮。

- **热点**：一键开关、改名称密码、查看已连设备、开机自动开
- **传文件**：车机开热点，手机浏览器打开卡片上的地址，双向传、可删除

整个应用 **64 KB**，纯 Java + 系统控件，无第三方依赖、无网络请求、无后台常驻。

---

## 兼容性

| 系统 | 能装 | 热点 | 文件传输 |
|---|---|---|---|
| Android 4.2 – 5.1（API 17–22） | ✅ | ✅ | ✅ |
| Android 6.0 – 7.1（API 23–25） | ✅ | ⚠️ | ❌ 无运行时权限 |
| Android 8.0+（API 26+） | ✅ | ❌ 隐藏 API 已移除 | ❌ |

不是"忘了兼容"——目标设备只有一台停在 4.2.2 的车机。开热点依赖 `WifiManager.setWifiApEnabled`，这个方法在 8.0 从 AOSP 移除，普通应用没有替代路径。**换新系统等于重做热点这一层，不是加兼容分支能解决的。**

---

## 快速开始

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

**工具链固定为 Gradle 9.5.0 + AGP 8.9.2 + JDK 26**。用 JDK 17 / Gradle 8.11 会报 `class file major version 70`。

### 签名

把 `signing.properties.example` 复制成 `signing.properties` 并填入自己的 keystore：

```properties
storeFile=/path/to/your.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_password
```

必须 **v1 + v2 同时启用，v3/v4 关闭**——Android 4.2.2 只认 v1，而新工具链默认只出 v2。

校验：

```bash
apksigner verify -v app-release.apk   # 应看到 v1 = true, v2 = true
```

### 安装

```bash
adb install -r app-release.apk
```

---

## 使用

界面是三张等宽卡片，没有标题栏——600px 高的屏幕，一条标题栏就要吃掉 76px。

**左卡 · 圆环**：青色=热点开着，灰色=关着。圆环本身就是按钮，点一下切换。底部一行「开机自启」开关。

**中卡 · 热点配置**：上半显示名称和密码，点击可改；页脚是已连设备数，点击看完整列表（IP / MAC / 主机名）。

**右卡 · 文件传输**：开启后在热点网段监听 8080，卡片上显示访问地址。手机连上热点，浏览器打开该地址即可：

- 选文件上传，支持多选，队列串行发送
- 列表里每个文件可**下载**到手机、可**删除**（点一下变红、1 秒内再点确认）
- 文件保存在车机 `/sdcard/车机传输/`

---

## 文件传输 API

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/` | 上传页面 |
| `GET` | `/api/files` | 文件列表 `[{"name":"","size":0}]` |
| `PUT` | `/upload?name=xxx` | 上传，body 为原始字节流 |
| `GET` | `/download?name=xxx` | 下载，`Content-Disposition` 保留中文名 |
| `DELETE` | `/file?name=xxx` | 删除单个文件 |

端口从 8080 起，被占用依次试 8081 → 8088 → 9090，界面显示实际绑定的那个。

**限制**：单文件 2 GB；剩余空间需 ≥ 文件大小 + 200 MB；文件名按 UTF-8 截到 200 字节（ext4 上限 255 字节）。

**安全**：所有接口共用一套名字校验（剥离路径 + `canonicalPath` 前缀校验），`../` 一律 404；删除只允许普通文件，目录不响应。

---

## 设计要点

**状态只有一个来源。** 界面不直接调系统接口，全部从 `WirelessService` 的快照读。广播和 2 秒轮询双保险——老车机的状态广播经常丢，只靠广播会出现"界面显示关着、实际开着"。

**所有操作串行执行，且必须回读确认。** `setWifiApEnabled` 返回 `true` 只代表"请求被接受"，不代表热点起来了。开关热点后要轮询 `getWifiApState` 直到状态真的变了，14 秒超时才算失败。

**隐藏 API 只有一个出口。** `setWifiApEnabled` / `getWifiApState` / `getWifiApConfiguration` 全部集中在 `ApBridge`，状态常量也只定义一份——历史上三处各抄一份、数字对不上，排查成本极高。

**上传必须校验收到的字节数。** 客户端正常关闭（FIN）时 `read()` 返回 -1 而不抛异常，只看 `catch` 会把半截文件当成功，用户拿到残包还以为传完了。

---

## 项目结构

```
app/src/main/java/com/carwireless/toolbox/
├── MainActivity.java      三卡片主界面
├── ui/                    纯代码绘制：圆环、开关、卡片、图标
├── wireless/              状态读取与热点/Wi-Fi 控制
├── core/                  步骤流水线、日志、配置、校验
├── boot/                  开机自启
└── transfer/FileServer.java   局域网 HTTP 文件服务
app/src/main/res/raw/transfer_page.html   手机端页面（单文件，无依赖）
```

全部界面用代码绘制，没有 XML 布局文件。原因很实际：`density 160` 下 **1dp = 1px**，代码里直接按像素写比在 XML 里反复调 `dp` 直观。

---

## 开发约定

**禁用 API 21+ 的方法。** 在 4.2.2 上会直接 `NoSuchMethodError`。每次打包后扫一遍 dex：

```bash
unzip -o -q app-release.apk -d /tmp/apkcheck && cd /tmp/apkcheck
for p in setLetterSpacing setElevation setTranslationZ VectorDrawable \
         postInvalidateOnAnimation "java/util/stream" "java/time/" "String;->join"; do
  echo "$p : $(grep -c -a -F "$p" classes*.dex | awk -F: '{s+=$2} END{print s+0}')"
done
```

全部为 0 才算通过。同样地：不使用 AndroidX、不使用 `java.time`、不使用 stream。

**诊断日志不能省。** 所有关键路径走 `ReportLog`，标签分 `HOTSPOT` / `WIFI` / `传输` / `BOOT` / `CRASH`。密码写入前脱敏。用户报"连不上"时，界面上可以一键导出报告到 `/sdcard/CarWirelessToolbox/`。

**不装车机也能测文件传输。** `FileServer` 不依赖 Android 运行时，用桩编译到桌面 JVM 就能跑真实 HTTP，用 `curl` 和 raw socket 打。已覆盖中文名、同名并发、路径穿越、半截上传、超限、空文件等用例。

更多实现细节见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

---

## 已知限制

- 不支持文件夹（目录会被跳过，上传路径会被拍平）
- 列表最多 200 条
- 无剩余空间显示
- 无回收站，删除不可恢复
- 文件列表不支持多选批量操作（上传时可多选）

换到别的车机上大概率会踩到上面没列出的问题。遇到了可以提 Issue，附上界面上导出的诊断报告最有用——里面有时刻、系统版本和每一步的失败原因。

---

## 许可

[MIT](LICENSE)。

个人项目，写给自己车机用的，觉得有用就直接拿去。
