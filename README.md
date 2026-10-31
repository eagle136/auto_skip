# auto_skip · 开屏广告自动跳过

Android 开屏广告自动跳过工具。不依赖云端、不联网、不上传截图：截图、检测、决策全部在本机完成。

适配 Android 15+（`minSdk 35`，`targetSdk 37`），仅 arm64。实测机型 realme GT8 Pro（ColorOS / Android 16，1080×2352）。

---

## 怎么工作

两条链路并存，同一帧只允许一条点中，点过的一方让位 1.5–2s，避免一次触发点两下。

| 链路 | 触发 | 判据 | 适用 |
|---|---|---|---|
| **节点路**（无障碍） | `TYPE_WINDOW_STATE_CHANGED` | 遍历 `AccessibilityNodeInfo`，文本/描述匹配规则 | 标准 Android 控件，最省电最准 |
| **OCR 路**（CornerTask） | 同上 | 截图 → 分带提候选 → SkipNet 打分 → 落地点击 | 自绘按钮、WebView 广告、白字压图 |

OCR 路的完整链路：

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

### 关键实现选择

- **截图走 `screencap` 裸帧**，直接把 BGRA8888 从 stdout 读进来。`screencap -p`（PNG）实测 0.96–1.10s，裸帧 210ms。
- **SkipNet 是纯 Kotlin 手写前向**（3 层 conv + pool），权重 `skipnet_f32.bin` 仅 170KB，无 native 依赖、无推理框架。
- **一次触发只点一下**，点完等 1.2s 不做校验不补点 —— 补点必然落到新页面。
- **候选提案分两路**：普通连通域处理有胶囊底的按钮；热区 top-hat（`lum − min7x7(lum)`）处理白字直接压在广告图上的情况，光带被抹平、笔画留下。
- **小框护栏按屏幕位置分档**（右上角 90×26 / 其余上半屏 140×46 / 下半屏 60×24），防止把正文或 banner 当按钮点掉。
- **不常驻悬浮窗**。`SYSTEM_ALERT_WINDOW` 只在手动打开调试开关后才用一条全屏 Canvas，平时不建窗口 —— 游戏反外挂会把隐形悬浮窗判成外挂。

## 权限与运行方式

扫描本身只需要无障碍服务；截图与补权限需要更高权限，两档可选：

| 档位 | 依赖 | 能力 |
|---|---|---|
| 无障碍 | 系统设置里开启服务 | 节点路 + `takeScreenshot` |
| Root | su（Magisk / KernelSU / APatch） | 上述 + `screencap` 裸帧 + `WRITE_SECURE_SETTINGS` |
| Shizuku | Shizuku 服务 | 同 Root，走 `IRemoteProcess` |

包名 `com.v5yn133n3.skp`，桌面显示名「蹦一蹦」。

## 构建

需要 JDK 17+、Android SDK（`compileSdk 37`）、NDK 非必需（`native/` 里的 C 源码手编，见下）。

```bash
git clone <repo>
cd auto_skip

# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 配置签名（可选，缺省则 release 不签名）
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore keystore/release_key.jks \
  -alias autoskip -keyalg RSA -keysize 4096 -validity 10000
# 编辑 keystore.properties 填入口令

# 3. 构建
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

若 Gradle 选错了 JDK，在 `~/.gradle/gradle.properties` 里加：

```properties
org.gradle.java.home=/path/to/jdk-17-or-newer
```

### 重建守护 ELF（可选）

`app/src/main/assets/elf/` 里的 `fgwatch-arm64` / `watchdog-arm64` 由 `tools/skp_fgwatch.c`、`native/watchdog.c` 交叉编译而来，源码在仓库内，直接用 NDK 编即可。仓库已带编译好的二进制，正常构建不需要这一步。

### 训练模型（可选）

`tools/` 下是模型与语料的完整生成链：合成语料（`glyph_synth.py` / `gen_skip_corpus.py`）→ 训练（`train_skip_torch.py`，需 PyTorch）→ 导出 `skipnet_f32.bin` + `skipnet_model.json` → 放到 `app/src/main/assets/`。评估脚本 `eval_gen.py` / `eval_skip_real.py`。

## 配置

运行时配置在应用私有目录，规则页可直接改：

- `/data/data/com.v5yn133n3.skp/shared_prefs/skip.global.xml` — 全局开关（OCR 时长、识别延迟、调试悬浮窗、运行方式）
- `/data/data/com.v5yn133n3.skp/shared_prefs/skip.rules.xml` — 每包规则（节点路文本、是否走 OCR）
- `app/src/main/assets/default_rules.json` — 出厂默认规则
- `app/src/main/java/com/v5yn133n3/skp/K.kt` — 全部常量（阈值、抖动、超时、护栏尺寸）

## 目录结构

```
app/src/main/java/com/v5yn133n3/skp/
  engine/    SkippService 无障碍服务、CornerTask OCR 主链路、CornerScan 候选提案、
             SkipNet 前向、BlobGate 实心块否决、BandScan 分带、PopupTask、Matcher
  priv/      Root/Shizuku 通道：Shell 执行、Capture 裸帧、Daemon 常驻、FgWatch 前台监听、SelfHeal
  keep/      保活：BootReceiver、KeepAlive
  data/      规则存储
  ui/        MainActivity（Compose）、RuleScreen、ImageTestActivity（离线截图测试页）
  K.kt       常量集中地
app/src/main/assets/
  skipnet_f32.bin / skipnet_model.json   模型权重与结构
  elf/                                   保活守护二进制
native/       watchdog.c
tools/        语料合成、训练、评估、以及守护 C 源码
plan/         方案文档
```

## 已知限制

- **只处理开屏广告**。信息流、弹窗、视频贴片不在范围内（`PopupTask` 只是兜底）。
- 白字压在**同亮度光带**上时，普通连通域会把两者黏成一块整块丢弃；只有热区 top-hat 能救。
- 下带（屏幕下方 42%）偶尔会把图片内容当按钮，靠 60×24 高度护栏压制，不是根除。
- 只适配 arm64 与竖屏坐标系；横屏游戏开屏未验证。
- 各家 ROM 的无障碍行为差异大（ColorOS 上 `am force-stop` 会把本应用从 `enabled_accessibility_services` 里摘掉），排障见 `会话续接.md` §7。

## 免责声明

仅供个人学习与自动化研究使用。绕过开屏广告可能违反部分应用的服务条款；因使用本工具产生的任何后果由使用者自行承担。请勿用于商业分发或绕过付费内容。

## License

MIT，见 [LICENSE](LICENSE)。
