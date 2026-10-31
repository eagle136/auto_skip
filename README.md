# auto_skip · 开屏文本自动点击

**开屏自动尝试移除用户特定的福祉，非常不推荐大家使用。如果需要移除更多的福祉，自己重新训练模型。**

具体来说：在应用启动的那一两秒里，找到屏幕上特定位置的短文本，然后点一下。

这件事对用户没有实际收益，对应用方却是实打实的损失——那次本该发生的曝光没有了。所以这里公开的
不是一款值得安装的软件，而是一份技术样本：怎么在 Android 上把**截图链路、小目标文本检测、
无障碍事件时序**做到能用的程度。样本的价值在方法，不在功能。

如果你在找「一键去开屏」的工具，请直接关掉这个页面。

适配 Android 15+（`minSdk 35`，`targetSdk 37`），仅 arm64。开发验证机为 realme GT8 Pro
（ColorOS / Android 16，1080×2352）。

---

## 它具体做什么

识别屏幕上特定位置的短文本，命中就点。就这些。它不拦截网络请求、不修改其他应用、不读应用数据、
不联网上传任何东西——截图、打分、决策全在本机内存里完成，进程结束即消失。

命中判定不靠关键词匹配，而是一个 170 KB 的卷积网络给候选框打分（阈值 0.90）。文本内容本身
不参与决策，参与决策的是字形的形状、密度、长宽比与上下文。所以它对不同字号、不同语言、不同
底色的同类文本都能用。

## 两条链路

两条路互斥：同一帧只允许一条点中，点过的一方让位 1.5–2s，避免一次触发点两下。

| 链路 | 触发 | 判据 | 适用 |
|---|---|---|---|
| **节点路**（无障碍） | `TYPE_WINDOW_STATE_CHANGED` | 遍历 `AccessibilityNodeInfo`，文本/描述匹配 | 标准原生控件，最省电最准 |
| **视觉路**（CornerTask） | 同上 | 截图 → 分带提候选 → SkipNet 打分 → 落地点击 | 自绘按钮、WebView 页面、文字直接压在图片上 |

视觉路的完整链路：

```
事件 → 起会话 → 截裸帧(screencap, ~210ms) → 半分辨率亮度图
     → 按带取瓦片（热区 8 块 / 每 6 帧全带）
     → 候选提案（连通域 + 热区 top-hat 双路）
     → SkipNet 打分（阈值 0.90）
     → BlobGate 实心块否决
     → 标题栏判据（限时 180ms，fail-open）
     → 抖动 → gestureTap
```

端到端冷启动到点中约 **1.0–1.5s**（含系统事件投递 ~520ms 固有延迟）。

## 值得看的地方

真正花时间的是几个性能与鲁棒性上的取舍，跟这个用途本身无关：

- **截图走 `screencap` 裸帧**，BGRA8888 直接从 stdout 读进来。`screencap -p`（PNG）实测
  0.96–1.10s，裸帧 210ms。差 5 倍，整条链路的预算就靠这一下腾出来。
- **推理是纯 Kotlin 手写前向**（3 层 conv + pool），权重 170 KB，不引任何推理框架、不带 native
  依赖。冷启动首次推理 25–40ms。
- **候选提案分两路互补**：普通连通域处理有底的按钮；热区 top-hat（`lum − min7x7(lum)`）处理
  文字直接压在画面上的情况——宽过 7px 的亮带被最小值滤波抹平，3–5px 的笔画完整留下，天然免疫
  贴边连通域被整块砍掉的问题。
- **一次触发只点一下**，点完等 1.2s 不做校验不补点。补点必然落到下一页上，这是实测结论。
- **不常驻悬浮窗**。`SYSTEM_ALERT_WINDOW` 只在手动打开调试开关后才挂一条全屏 Canvas，平时不建
  窗口——部分游戏的反作弊会把隐形悬浮窗判成外挂。
- **小框护栏按屏幕位置分档**（右上角 90×26 / 其余上半屏 140×46 / 下半屏 60×24）。分档依据是
  高度而非宽度：实测真实目标全在 3%–8.5% 高度区间，误报在 12.8%，宽度差只有 4px 分不开。

## 权限与运行方式

扫描本身只需要无障碍服务；截图与补权限需要更高权限，两档可选：

| 档位 | 依赖 | 能力 |
|---|---|---|
| 无障碍 | 系统设置里开启服务 | 节点路 + `takeScreenshot` |
| Root | su（Magisk / KernelSU / APatch） | 上述 + `screencap` 裸帧 + `WRITE_SECURE_SETTINGS` |
| Shizuku | Shizuku 服务 | 同 Root，走 `IRemoteProcess` |

## 签名与包名：每次发布都不一样

这个项目**不维护固定身份**。

- 仓库里 `signing/open-release.jks` 是一份**一次性随机密钥**，口令明文写在
  `signing/open-release.properties` 里，故意入库——它不代表任何身份，只为了让 clone 下来的人
  不用自建密钥就能直接出包。
- 正式发布走 `tools/make_release.py`：每次运行现生成新的 RSA 3072 密钥和新的
  `applicationId`（形如 `com.<8位>.<6位>`），构建完把 APK 丢进 `releases/`，目录里只留最新一个。
- 因此 **`releases/` 里每个 APK 的包名都不同，签名指纹也都不同**，彼此无法互相覆盖安装。

这是刻意的：包名与签名指纹是应用在设备上的身份，固定身份意味着可以被按身份识别和追溯。

**代价说清楚**：随机包名等于每次都是全新应用。装新版本后，无障碍服务要重新开、Root 授权要重新
给、规则配置从零开始，旧版本不会自动升级、也不会自动卸载。这是设计的直接后果，不是 bug。
想在一个版本上长期用，就自己用 `-PappId=` 把包名和签名定死。

## 构建

需要 JDK 17+、Android SDK（`compileSdk 37`）。`native/` 里的 C 源码手编，正常构建不需要 NDK。

```bash
git clone <repo>
cd auto_skip

# SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 出包（随机身份）
python tools/make_release.py
# → releases/auto_skip-v1.0-com.xxxxxxxx.yyyyyy.apk

# 固定身份、用自己的签名
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore keystore/release_key.jks \
  -alias mykey -keyalg RSA -keysize 3072 -validity 10000
# 编辑 keystore.properties 填口令，然后
./gradlew assembleRelease -PappId=com.example.myapp
```

若 Gradle 选错了 JDK，在 `~/.gradle/gradle.properties` 里加：

```properties
org.gradle.java.home=/path/to/jdk-17-or-newer
```

### 重建守护 ELF（可选）

`app/src/main/assets/elf/` 里的 `fgwatch-arm64` / `watchdog-arm64` 由 `tools/skp_fgwatch.c`、
`native/watchdog.c` 交叉编译而来，源码在仓库内，用 NDK 编即可。仓库已带编译好的二进制。

### 训练模型（可选）

`tools/` 下是模型与语料的完整生成链：合成语料（`glyph_synth.py` / `gen_skip_corpus.py`）→
训练（`train_skip_torch.py`，需 PyTorch）→ 导出 `skipnet_f32.bin` + `skipnet_model.json` →
放到 `app/src/main/assets/`。评估脚本 `eval_gen.py` / `eval_skip_real.py`。

## 配置

运行时配置在应用私有目录，规则页可直接改：

- `shared_prefs/skip.global.xml` — 全局开关（识别时长、识别延迟、调试悬浮窗、运行方式）
- `shared_prefs/skip.rules.xml` — 每包规则（节点路文本、是否走视觉路）
- `app/src/main/assets/default_rules.json` — 出厂默认规则
- `app/src/main/java/com/v5yn133n3/skp/K.kt` — 全部常量（阈值、抖动、超时、护栏尺寸）

包名随机后，路径里的 `com.v5yn133n3.skp` 换成实际包名。

## 目录结构

```
app/src/main/java/com/v5yn133n3/skp/
  engine/    SkipService 无障碍服务、CornerTask 视觉主链路、CornerScan 候选提案、
             SkipNet 前向、BlobGate 实心块否决、BandScan 分带、PopupTask、Matcher
  priv/      Root/Shizuku 通道：Shell 执行、Capture 裸帧、Daemon 常驻、FgWatch 前台监听、SelfHeal
  keep/      保活：BootReceiver、KeepAlive
  data/      规则存储
  ui/        MainActivity（Compose）、RuleScreen、ImageTestActivity（离线截图测试页）
  K.kt       常量集中地
app/src/main/assets/
  skipnet_f32.bin / skipnet_model.json   模型权重与结构
  elf/                                   保活守护二进制
signing/     一次性公开签名（故意入库）
native/      watchdog.c
tools/       语料合成、训练、评估、发布脚本、守护 C 源码
plan/        方案文档
releases/    发布产物，只留最新一个
```

## 已知限制

- 只处理**启动画面**。信息流、弹窗、视频贴片不在范围内（`PopupTask` 只是兜底）。
- 文字压在**同亮度光带**上时，普通连通域会把两者黏成一块整块丢弃；只有热区 top-hat 能救。
- 下带（屏幕下方 42%）偶尔会把图片内容当目标，靠 60×24 高度护栏压制，不是根除。
- 只适配 arm64 与竖屏坐标系；横屏未验证。
- 各家 ROM 的无障碍行为差异大（ColorOS 上 `am force-stop` 会把本应用从
  `enabled_accessibility_services` 里摘掉），排障看 `adb logcat -s SkipEngine:I`。

## License

MIT，见 [LICENSE](LICENSE)。许可宽松不代表推荐使用。
