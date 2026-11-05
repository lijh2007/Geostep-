## 0.7.5 — 2026-11-04

## 0.8.4 — 2026-11-05

- 修复「路线模拟闪退」：其实是 **ANR**，不是崩溃。Root 模式下开始模拟（含路线）会触发已记住应用的自动补注入，而 `SuCommandRunner.run` 是阻塞调用，原先跑在 `viewModelScope`（主线程）上，su 卡住 10 秒以上就被系统判为输入无响应（`ANR in com.geostep.app … Input dispatching timed out`），用户看到的就是闪退。
- 改法：`RootDeployment` 的 `injectClient` / `processesOf` / `injectProcess` / `publishState` / `clearState` / `readState` / `injected` / `allowMockLocation` / `bootCount` 全部切到 `Dispatchers.IO`；`RootInjectionController` 的注入调用与 `HomeViewModel.watchInjection` 的自动补注入同样包进 `withContext(Dispatchers.IO)`。
- 真机复测（Redmi K60 / Android 17，debug 构建）：走「路线 → 2 个途经点 → 开始路线」，界面一直可交互，状态胶囊显示「路线模拟中」，进度从 2 m 推进到 8 m、速度 0.4 → 0.6 km/h 缓升，`logcat` 里 `ANR in com.geostep.app` 与 `FATAL EXCEPTION` 计数均为 0。
- 顺带说明：0.8.2 起关于页的作者信息、GitHub 地址与上游致谢沿用不变。

## 0.8.3 — 2026-11-05

- 作者 GitHub 地址更正为实际主页 `https://github.com/lijh2007`（关于页、README、NOTICE、CONTRIBUTING 同步）。
- 版本号 0.8.3（versionCode 40）。

## 0.8.2 — 2026-11-05

- 关于卡片改为展示作者信息：GitHub https://github.com/lijh2007 · sayonara · 1590887927@qq.com，并新增一行「感谢原作者开源」+ 上游 GitHub 与作者邮箱（GitHub https://github.com/jie8190/MockGeo · jie8190 · kprxr.660@qq.com）。
- 开发者联系邮箱统一改为 1590887927@qq.com（诊断反馈邮件、复制邮箱、README、NOTICE 同步）。
- 关于卡片改用玻璃材质，与其余界面一致。
- 版本号 0.8.2（versionCode 39）。

## 0.8.1 — 2026-11-05

- 液态玻璃铺满全部界面：底部面板容器改为 94% 半透明 + 22% 遮罩、	onalElevation = 0；面板与设置页的 9 个中性卡片改用 liquidGlassChip(veilAlpha = 0.90f)。
- 彩色强调块（品牌卡、模式图标、错误条）保持语义色，不套玻璃。
- 版本号 0.8.1（versionCode 38）。

## 0.8.0 — 2026-11-05

- **仿 iOS 液态玻璃 UI**：新增 pp/.../ui/Glass.kt（liquidGlass / liquidGlassChip / GlassPanel），用「半透明表面色打底 + 上亮下暗的白色渐变 + 渐变描边 + 顶部 1dp 高光 + 双层柔和阴影」组合出玻璃质感（Compose 无法对地图做真正的背景模糊，这是最接近的等效方案）。
- 应用到主界面：搜索栏、状态胶囊、地图源胶囊、底部目的地卡片、右侧缩放/定位按钮、底部四个入口胶囊，以及「注入校验」「地图加载较慢」两条横幅。
- 可读性与通透度两者兼顾：玻璃底色默认 92%（卡片）/ 85%（胶囊）不透明度，浅色深色主题都成立。
- 版本号 0.8.0（versionCode 37）。

标准模式自检新增「系统模拟位置应用」一项（提示性，不阻断启动）。

- ProviderEngine.selfCheck() 读取 Settings.Secure.mock_location：与 com.geostep.app 不一致时给出当前取值，并指向开发者选项。
- 实测确认：本机把 mock_location 指向 com.mockgeo.app 时注入依旧生效（ROM 只看 AppOps），因此这一项**只做提示**、不加入阻断集合（ModeReadiness.requiredCheckNames 仍为「定位权限 + 模拟位置 AppOps」）。
- 单测仍为 **263 通过**。

## 0.7.4 — 2026-11-04

主界面横幅组件拆分，纯重构、无行为变化。

- 新增 `app/src/main/kotlin/com/geostep/app/ui/HomeBanners.kt`：`VerificationBanner(checks, verifying, onVerify)`（注入回读校验条）与 `MapIssueBanner(onRetry, onChooseMap)`（地图加载异常条），内容与样式逐行搬移。
- `HomeScreen.kt` 的 `MapHome` 里两段内联 Surface 改为一行调用，文件 512 → 471 行。
- 单测仍为 **263 通过**（module 78、app 39、core:engine 65、core:route 47、core:model 25、core:data 9）。

## 0.7.3 — 2026-11-04

低功耗模式，针对低端机与长时间挂机场景。

- 设置 →「外观与体验」新增「低功耗模式」开关（默认关闭）。
- 开启后模拟刷新放慢到 2 秒一次（`Perf.tickIntervalMs`），位置推送频率随之下降，CPU 唤醒明显减少。
- 地图侧同步降级：关闭按屏幕密度选择更高清瓦片（`Perf.tileScaleToDpi`）、瓦片下载线程固定为 1（`Perf.tileThreadsFor`）。
- 开关状态写入 DataStore（`low_power_mode`），随设置导入导出一起迁移；真机确认写入为 true。
- 新增 `PerfTest` 4 例覆盖三处降级规则，单测合计 **263**（module 78、app 39、core:engine 65、core:route 47、core:model 25、core:data 9）。

## 0.7.2 — 2026-11-04

### 注入管理搬出 ViewModel

- 新增 pp/.../ui/RootInjectionController.kt：RootInjectState + 注入/补注入/移除/会话启动准备
  收敛到一个小状态机里；HomeViewModel 只保留三个一行转发（injectRootSensors、
  
eInjectRememberedPackages、orgetRootPackage）。
- HomeViewModel.kt 748 → **702 行**（本轮重构累计 815 → 702）。
- 行为不变：注入成功仍会写 
ootInjectedPackages、把包交给引擎自动补注入新进程、
  UI 提示文案与失败原因保持一致。RootInjectState 保持 public（al rootInject 是 public 属性）。

## 0.7.1 — 2026-11-04

### 参数面板改成可折叠分组

- 原来 12 个控件平铺，一屏放不完、重点不突出。现在分成四组：
  **移动与步频**（速度/平滑/步频/浮动幅度/浮动周期）、**定位与信号**（精度/海拔/卫星/间隔/网络定位）、
  **GPS 漂移**（开关 + 半径）、**Root 传感器注入**（仅 Root 模式出现）。
- 新增通用组件 CollapsibleSection（pp/.../ui/PanelParts.kt）：图标 + 标题 + 一句话说明 + 旋转箭头，
  点击整行展开/收起，nimateFloatAsState 转箭头、expandVertically + fadeIn 展开，
  折叠状态用 
ememberSaveable 记住；默认展开前两组，漂移组跟随开关、注入组默认收起。
- 参数说明长文本收进「移动与步频」组内，切到标准模式时仍会红字提示「步频需 Root 模式」。

## 0.7.0 — 2026-11-04

### 源码重构（HomeViewModel 瘦身 + 参数边界集中）

- 新增 pp/.../ui/DiagnosticsReportBuilder.kt：把 HomeViewModel.diagnosticReportText()
  里 60 多行的字段拼装搬出去，ViewModel 只剩「收集状态 → 调一次」。
  HomeViewModel.kt 815 → 762 行。
- 新增 pp/.../ui/ParameterLimits.kt：所有界面参数边界（更新间隔 200–2000ms、精度 1–50m、
  漂移 0–50m、速度 0.1–30m/s、海拔 -500–9000m、卫星 0–64、步频 0–300、浮动 0–30%、
  周期 10–120s、历史上限 10–2000）集中一处；9 个 setter 改为引用它，滑杆与 setter 不再各写一份数字。
- 新增 pp/src/test/.../ui/ParameterLimitsTest.kt（6 例）钉住上述边界。
- 新增 pp/.../transfer/GpxTransfer.kt：GPX/设置导入导出的 Uri 读写（8 MB 上限、UTF-8、
  错误文案）集中到一处；HomeViewModel 里的 
eadFile/写文件块改成一行委托，
  HomeViewModel.kt 762 → **748 行**（本轮累计 815 → 748）。
- 测试基线 253 → **259**（core:model 25、core:route 47、core:data 9、module 78、app 35、core:engine 65）。

## 0.6.8 — 2026-11-04

### Root 定位终于在高德地图里生效（这次是真机逐条排掉的）

三处真问题，都是“注入进不去 / 注入不生效”：

1. **只注入主进程**。高德把定位放在 `com.autonavi.minimap:locationservice` 子进程里，
   主进程注了也没用。→ 改用 `ps` 枚举该包的全部 `app_process64` 进程并逐个注入；
   `pidof` 只按主进程名匹配，是被它坑的。
2. **ptrace 远程调用超时**。子进程里 `loader` 报 `remote call timed out`（原超时 3s/8s）。
   → `injector.cpp` 的超时提到 20s / 30s，并在 Kotlin 侧对单个进程重试 3 次。
3. **系统级兜底**。有些进程仍注不进去时，Root 模式启动会顺手把自己设成系统模拟位置应用
   （`appops set … android:mock_location allow` + `settings put secure mock_location`），
   并把假位置推给系统 provider —— 任何进程都能读到位置，代价是这类进程里“无痕”无法隐藏。

另外：注入进进程的假位置现在**一律 `mock=false`**，并接管了 `Location.isFromMockProvider()`，
避免高德这类 App 直接过滤掉“模拟位置”。

真机验证（Redmi K60 / Android 17，`com.autonavi.minimap`）：

```
20378 com.autonavi.minimap            geostep=5
20434 com.autonavi.minimap:locationservice geostep=5
W GeostepModule: no location transport hooks; falling back to periodic injection
I GeostepModule: location client hooks installed: transports=0 last-known=2 current=3 updates=12
D GeostepModule: app-scope hooks installed
DEBUG_START check Root 状态镜像 ok=true 镜像 lat=31.2304 lng=121.4737 spd=1.4 cad=120
dumpsys location: last mock location=Location[gps 31.230400,121.473701 vel=1.4 mock …]
```

高德首页最终落在上海人民广场（31.2304, 121.4737），不再是真实位置。

## 0.6.7 — 2026-11-04

### Root 模式的定位终于真正生效（高德地图等第三方 App）

之前 Root 直连只接管系统定位服务，App 自己通过 `LocationManager` 拿到的仍是真实定位。
0.6.7 在目标进程里直接接管客户端定位：

- `getLastKnownLocation` / `getCurrentLocation` 直接返回模拟坐标；
- `requestLocationUpdates` 注册的监听会由后台按 1 秒节奏补投模拟坐标（`mock=true`、速度与下发速度一致）；
- 注入有效期从 15 秒放宽到 5 分钟（`AppScopeConfig.INJECT_FRESH_MS = 300_000L`），
  避免用户切到高德后 Geostep 被系统冻结、镜像过期导致注入失效——这正是"Root 模式在地图里不起作用"的根因。

实测（Redmi K60 / Android 17 / 高德 `com.autonavi.minimap`）：注入后高德进程出现
`location client hooks installed`、`app-scope hooks installed`，定位蓝点落在下发的上海外滩坐标。

### 新的应用图标

- 自适应图标重做：深蓝渐变底 + 白色描边定位针 + 青色针心 + 落地弧线，另配 `monochrome` 单色层（HyperOS / Android 13+ 主题图标可用）。
- 继续保持矢量实现，不额外增加包体。

### 调试能力

- 目标进程里补了可读日志：`location client hooks installed: transports=… last-known=… updates=…`、
  `request-location-updates`、步频 `step pump: delivered=…`，方便定位"某 App 读不到"的问题。

## 0.6.6 — 2026-11-04

Root 模式「开箱即用」（按用户要求先集中保证标准 + Root 两种模式可用，暂不追 LSPosed）：

- 记住注入过的目标应用：`SettingsRepository` 新增 `rootInjectedPackages`（DataStore key `root_injected_packages`，CSV，导入/导出也带），注入成功后自动保存。
- 开始模拟时自动补注入：`HomeViewModel.watchInjection()` 在 Root 模式进入 Running 后，对记住的包名补一次 `injectRootSensors`，应用重启/更新后不需要手动再填一次。
- 参数面板「Root 传感器注入」区显示已记住的包名 chip：点一下重新注入，点 × 移除（`forgetRootPackage`）。
- 新增纯函数 `isPackageName` / `packagesToInject`（`ui/ModeReadiness.kt`）与 2 个测试，注入前就能在 UI 层拦掉非法包名、避免重复注入。
- 单测 251 → 253。

## 0.6.5 — 2026-11-04

源码重构与交互细节（功能不变）：

- `HomeScreen.kt` 的坐标/距离/错误文案三个纯函数移到 `Labels.kt`，新增 `LabelsTest` 7 例（6 位小数、米/公里阈值、四类失败原因映射、其它失败回退到引擎细节）。
- 地图右下角的 +/−/复位按钮从 `IconButton` 换成 `tapFeedback(scaleDown = 0.9f)`：按下有缩放反馈，点击区域仍为 44dp。
- 修 `LabelsTest` 首版断言与文案不一致的问题（断言改为同时检查“开发者选项”与“模拟位置应用”）。
- 单测合计 244 → 251（app 20 → 27）。

## 0.6.4 — 2026-11-04

源码重构（行为不变）：

- `app/src/main/kotlin/com/geostep/app/ui/Panels.kt` 从 714 行拆到 581 行，`ToolRow` / `SectionLabel` / `EmptyState` / `SettingSlider` / `SwitchRow` 五个通用组件移到新文件 `PanelParts.kt`（同包，调用点无需改动）。
- `HomeViewModel` 里的模式展示名与「能否切换」判定抽成纯函数 `ModeReadiness.kt`（`modeLabelOf` / `requiredCheckNames` / `blockingChecksFor` / `blockedReasonOf`），ViewModel 只保留状态与调度。
- 新增 `app/src/test/kotlin/com/geostep/app/ui/ModeReadinessTest.kt` 6 个用例，钉住「别的模式的失败项不会拦住当前模式」等行为；`HomeViewModel` 逻辑改为委托，提示文案不变。
- 单测合计 238 → 244（app 14 → 20）。

## 0.6.3 — 2026-11-04

- Root 注入式模式实测打通：载荷注入第三方进程后，`app-scope hooks installed` 生效，目标 App 的传感器回调被重写（加速度/陀螺/磁力/重力/旋转向量 12/18 命中合成值）。
- 步频自驱动事件泵在目标进程内真正投递：探针 App 收到 `TYPE_STEP_DETECTOR` 连续事件，间隔 495–510ms，与设定的 120 步/分一致；日志 `step pump: delivered=20 type=18 spm=123`。
- `StepPump` 增加「复用 App 真实事件对象」的兜底：部分 ROM 在目标进程内禁止反射构造 `SensorEvent`（hidden API 策略），此时用任意真实事件作模板改写 values/timestamp/sensor 后重投，泵不再因构造失败而停摆。
- `SensorHooks` 把任意传感器事件都登记为模板，并在拿不到模板时保持下一帧重试，不永久停泵。
- 探针 App 支持 `delayMs` 延迟注册监听器（留出注入窗口）并新增计步器/计步事件采集，便于回归验证。
- 模块版本 0.3.21（versionCode 24），主应用 0.6.3（versionCode 24）。

## 0.6.2 — 2026-11-04

### 低版本 / 低内存设备降级（新增 `ui/Perf.kt`）

`Perf.isLowEnd(context)` 判定：Android 8/9（API ≤ 28）或 `ActivityManager.isLowRamDevice`。

| 项目 | 低端机 | 常规 |
| --- | --- | --- |
| 开屏动画时长 | 0.55× | 1.0× |
| 地图瓦片磁盘缓存 | 48 MB | 160 MB |
| 瓦片下载线程 | 1 | 2 |
| `cacheMapTileCount` / `Overshoot` | 24 / 8 | 48 / 16 |

新增单测 `app/src/test/.../ui/PerfTest.kt`（2 例）钉住上表，避免「优化」把预算调反。

### 修复：`RootEngine` 的抽象漏口

`start()` 里把 `RootEnvironment` 硬转成 `RootDeployment`，导致非真实部署实现（含单测）无法启动；现在真实部署才启用「状态镜像 / 应用注入」，其它实现走纯系统载荷路径。另外把 `ownsSession = true` 提前到 `channel.start()` 之前——SET 已经可能到达服务端时，即使回执丢失也要保留清理责任。

单测回归：`RootEngineTest` 的 2 个用例恢复通过。

## 0.6.1 — 2026-11-04

真机复测（Redmi K60 / Android 17 / HyperOS 官改 / serial 2469112b）。

### 标准模式首次实测通过

开发者选项把 Geostep 选为「模拟位置信息应用」后：

- `dumpsys location` 的 `last mock location` 变成我方下发值：`gps 39.904202,116.407402 hAcc=5.0 vel=1.4 mock {satellites=12}`；
- App 内回读校验：`模拟坐标回写 ok=true 偏差 0.0 米`、`模拟速度回写 ok=true 速度 1.40 m/s`；
- 步频一项按设计返回 `ok=false`（标准模式不注入传感器），提示切到 LSPosed / Root。

### LSPosed 模式：模块未被框架加载（本机实测）

- `zygisk_lsposed` 进程在跑，`pm list packages` 里也有 `com.geostep.module`，但 `/data/adb/lspd/log/modules_*.log` 里**没有任何 Geostep 记录**（`grep -ci geostep` = 0），说明模块未启用或不在作用域内。
- 现在 LSPosed 模式启动失败时直接给出可操作提示：「启用 Geostep 模块、把系统框架与目标应用勾进作用域，然后重启手机或作用域应用」，而不是笼统的 `no status receipt from module`。
- 失败状态条文案同步改为「LSPosed 模块未生效：启用模块、勾选系统框架与目标应用后重启」。

### 自动化调试坑（仅影响 debug 构建的广播联调）

- HyperOS 会把后台的 Geostep 冻结，`am broadcast` 只在 App 处于前台时才送达；连续发多条广播时第二条起常被丢弃。
- 可靠做法：每次 `am force-stop com.geostep.app` → `am start -n com.geostep.app/.MainActivity` → 等 2 秒 → 发一条广播。

## 0.6.0 — 2026-11-04

首个以 **Geostep** 名义发布的版本：包名 `com.geostep.app` / `com.geostep.module`，与旧 `com.mockgeo.*` 不互通，请先导出旧版配置再重装。

### 三种运行模式的真实状态（Redmi K60 / Android 17 / HyperOS 官改实测）

- **标准模式**：启动前检查运行时定位权限与 `AppOps` 模拟位置授权；启动后用 `requestLocationUpdates` 回读坐标（3 秒超时），失败时回退 `getLastKnownLocation` 并按 2.5 米容差判定。真正生效的前提是系统里 Geostep 已被选为「模拟位置信息应用」。
- **LSPosed 模式**：模块 `com.geostep.module` 0.3.20，继续用 `geostep_state_v1` 镜像 + `geostep` provider + `com.geostep.app.status` 状态列。
- **Root 直连模式**：不再向 `system_server` 注入载荷（该 ROM 上 LSPlant 内联钩子会直接崩机，已默认关闭），改为
  1. App 用 su 把状态镜像写进 `Settings.Global.geostep_state_v1`，`RootEngine` 每 3 秒续期（15 秒过期）；
  2. loader 把 `libmockgeo_root.so` 与 `payload.apk` 复制到目标 App 的 `lib/arm64`（`apk_data_file` 上下文，命名空间内可 dlopen），ptrace 注入后在目标进程里装 `AppScopeHooks`；
  3. 只注入 64 位主进程（`app_process64`），32 位进程跳过并提示。

### 修复

- 镜像 `boot` 字段在 su 首条命令空返回时写成 `-1`，导致模块把整份镜像判为过期、注入的 App 读不到状态；改为 `settings` → `Settings.Global` → `cmd settings` 三级回退。
- `RootEngine` 的镜像续期不再依赖 UI tick（自动化启动时 `SimController` 不 tick 会让镜像 15 秒过期），改用构造时注入的协程 scope 每 3 秒发布。
- 注入成功判定改用 `.geostep_<nonce>` 作为 `/proc/<pid>/maps` 标记。
- 预编译 `libmockgeo_root.so` 内仍是 `com.mockgeo.root.*` 类名，新增 `com.mockgeo.root.Compat` 与 `com.geostep.root.NativeHooks` 转发层，消除 `No implementation found` / `ClassNotFoundException`。
- `HomeShortcut`、`SwitchRow`、数值胶囊统一接入 `Modifier.tapFeedback`（按下缩放到 96%，弹簧回弹），开关整行可点。
- 主界面收拢信息层级：「还差 N 项」从卡片外的独立横幅并入卡片顶部（带具体缺项名），模拟参数摘要补上当前步频（标准模式显示「步频需 Hook/Root」），卡片圆角与内距统一到 20dp / 16dp。

### 已知边界

- 只覆盖「自己用 `SensorManager.registerListener` 读传感器」的应用；走 Health Connect / 厂商健康服务聚合的应用无法改写。
- 注入只对注入**之后**注册的监听生效：先打开目标 App → 在 Geostep 里注入 → 再进入运动页。
- HyperOS 会冻结后台的 Geostep，模拟期间让它保持前台，或执行 `adb shell cmd deviceidle whitelist +com.geostep.app`。

## 0.5.6 — 2026-11-05

- Root 直连的 loader 现在接受 `client:<package>` 目标：可以给普通应用进程注入传感器钩子（此前只允许 system_server / probe，注入会报 “target is not a Geostep loader target”）。
- 目标校验：必须是已安装应用的 UID（拒绝 root 与 system），进程名必须等于包名或是它的 `:sub` 子进程。
- 内置 loader 改为直接以 NDK clang 交叉编译产物（体积从 700 KB 降到 ~78 KB）。
- 文档补充注入失败排查。

## 0.5.5 — 2026-11-05

- Root 注入的 su 解析增加回退链（自定义路径 → PATH → 常见绝对路径），只有「启动失败(EACCES/ENOENT/格式不符)」
  才换下一个入口，授权被拒/超时立即报错；全部失败时把每个入口的原始错误拼进提示。
- Root 传感器注入不再要求先停止模拟；注入卡片增加小米运动健康 `com.mi.health`、步道乐跑 `com.lptiyu.tanke` 快捷按钮。

## 0.5.4 — 2026-11-05

- **Root 模式支持按 App 注入传感器**（实验）：新增 `client:<package>` 载荷模式，把 `payload.apk` 注入目标 App 进程，
  其中的 `SensorHooks` 与 LSPosed 模式完全一致——计步器、计步事件、步态波形都跟随步频，静止时由事件泵驱动。
- 主应用「模拟参数」新增 **Root 传感器注入（实验）** 卡片：填目标 App 包名即可注入；注入前会拒绝系统驻留 LSPosed 的设备，
  注入后用 `/proc/<pid>/maps` 确认载荷确实留在目标进程里，失败给出可操作原因（未运行/被冻结/包名不合法）。
- 载荷 `client` 模式不写系统回执（App 进程没有 `/data/misc/geostep` 写权限），改由宿主校验进程内存映射。
- 说明文档 [docs/ROOT_APP_INJECTION.md](docs/ROOT_APP_INJECTION.md)。

## 0.5.3 — 2026-11-05

### 传感器注入可诊断、可覆盖轮询式读取

- **首帧立即投递**：监听器一注册就投一次合成事件，轮询式 App（注册→读一次→注销）也能拿到步数。
- **计步累计值续接**：合成 `TYPE_STEP_COUNTER` 从真实传感器的最后一个值继续累加，避免运动 App 看到“步数归零”。
- **更多入口**：除 `registerListener` / `unregisterListener` / `flush`，现在还覆盖触发式传感器 `requestTriggerSensor` / `cancelTriggerSensor`。
- **诊断日志（`adb logcat -s Geostep`）**：
  - `app queried getDefaultSensor type=19 pkg=…` —— 目标 App 确实在找计步器；
  - `listener registered via registerListener type=19 pkg=…` —— 注入已经进了该进程；
  - `step pump: delivered=20 type=19 spm=118 value=237 pkg=…`（每 20 条一次）—— 合成事件正在投递。
  - 一条日志都没有 = 模块没进那个进程（LSPosed 作用域 / 强制停止后重开）。
- 模块版本 **0.3.20**，主应用 **0.5.3**（versionCode 17）。协议未变，旧主包配新模块仍可用。

## 0.5.2 — 2026-11-05

主应用 **0.5.2** · 内置 Root 载荷 **0.1.4** · LSPosed 模块 **0.3.19**。

- **静止也出步数（自驱动步数事件泵）**：真实计步传感器是事件驱动的，手机不动就不产生 `SensorEvent`，
  第三方运动 App 因此收不到任何步数——这是「小米运动健康读不出步频」的主因。模块现在在目标 App
  注册计步监听器后，若真实传感器 1.2–1.5 秒没有步事件，就按当前**瞬时步频**（含 ±N% 浮动）自己构造事件喂给 App：
  `TYPE_STEP_COUNTER` 的累计值按瞬时步频积分，`TYPE_STEP_DETECTOR` 按 `60000/步频`（下限 80ms）出 1。
  - 事件对象用 `SensorEvent` 的包私有构造器反射创建；若被 hidden-API 策略拒绝，**安静停泵并记一条日志**，
    不把异常抛进目标 App，也不影响真实事件的转发与改值。
  - 真实传感器一旦恢复上报，泵自动让位（静默阈值 1.5 秒），避免同一段时间出双份步数。
  - 目标 App 仍需在 LSPosed 作用域内、并授予「身体活动」权限；完全走 Health Connect / 系统健康数据的
    App 不受传感器注入影响（见 `docs/STEP_CADENCE.md` 4.1）。
- 新增 `:module` 单测 `StepPumpTest`（事件构造失败时返回 `null` 且不虚增计数）；单测 224，`:module` 78。

# 更新日志

## 0.5.1 — 2026-11-05

主应用 **0.5.1** · 内置 Root 载荷 **0.1.4** · LSPosed 模块 **0.3.18**。

### 让步频像真人，让运动 App 读得到

- **步频浮动（新）**：`模拟参数 → 移动` 新增「步频浮动幅度」（0–30%，默认 ±8%）与「浮动周期」（10–120 秒，默认 45）。
  浮动由 `StepCadence.instantSpm` 用两条正弦叠加生成，是**纯确定性函数** —— App 与模块在同一时刻算出同一个值，
  注入回读校验不会因为浮动而误报；设成 `0%` 即回到恒定节奏。
- **计步节奏跟随浮动**：`TYPE_STEP_DETECTOR` 的相邻间隔改为按瞬时步频计算（`60000 / 瞬时步频`，下限 80ms），
  `TYPE_STEP_COUNTER` 按瞬时步频积分，累计值始终等于「这段时长的平均步频 × 时长」。
- **步态波形提升到真实量级（关键修复）**：0.5.0 及以前加速度振幅只有 `0.04 / 0.35 m/s²`，靠加速度计自己数步的
  运动 App（小米运动健康等）判不出步；本版改为垂直峰值 `1.15 × strength × (sin φ + 0.32 sin 2φ)`、
  横向 `0.18 × strength × cos φ`、前后 `0.22 × strength × sin(φ+0.4)`，`strength = min(v/1.4, 1.5)`，与步事件同相。
- **回读校验适配浮动**：`InjectionVerifier.cadence` 允许 ±1 步/分的取整误差（回执上报的是平均步频），
  提示文案带上「浮动 ±N%」。
- **协议**：`SET` 新增可选字段 `step_cadence_variability_pct`、`step_cadence_period_sec`；
  状态镜像新增可选键 `cav` / `cap`（12 键旧镜像仍可解析，其余陌生键仍 fail-closed）；模块版本 0.3.18。
- **第三方 App 排查**：`docs/STEP_CADENCE.md` 新增「第三方运动 App 读不到步数/步频怎么办」清单
  （作用域 → 身体活动权限 → 是否走 Health Connect → 看回读校验与 logcat）。
- **单测**：`StepCadenceTest` 新增 4 例（恒定、确定性、浮动带与均值、物理边界），`InjectionVerifierTest` 新增 1 例；
  合计 **223 个单测全部通过**（module 77 / core:model 25 / core:engine 65 / core:data 9 / core:route 47）。
- 适配：`compileSdk`/`targetSdk` 保持 36（Android 16），沿用同一套 libxposed API 102 入口，未引入 Android 17 私有 API。
  模块 0.3.18 与主包 0.5.1 建议同步升级。

## 0.5.0 — 2026-11-05

主应用 **0.5.0** · 内置 Root 载荷 **0.1.4** · LSPosed 模块 **0.3.17**。

### 工作模式选择与注入回读校验

- **运行模式显式化**：`偏好设置 → 运行模式` 的三项（标准 / LSPosed / Root 直连）改为带实时状态的选项，每项下方直接显示「可用」或具体失败原因（定位权限、模拟位置 AppOps、LSPosed 模块安装/加载/版本、系统控制通道、系统定位钩子、Root 授权、Root 载荷回执），并新增「重新检测」；工具页新增 **工作模式** 入口，点开即见当前模式与状态。
- **切换前就绪检查**：点选模式时先跑该模式的自检，关键项不通过就**不切换**，直接提示缺哪一项（例如“尚未安装 LSPosed 模块”或“未授予 Root 权限”），避免切过去却静默不生效。模拟运行中仍禁止切换。
- **注入回读校验（本版重点）**：开始模拟后 2.5 秒起、每 10 秒把模块回执里**实际生效**的坐标、速度、步频与刚下发的配置逐项比对，主界面状态条显示「位置与步频注入已确认」或具体偏差；也可随时点「校验」立即复查。判定规则：坐标偏差 > 2.5 米、速度差 > 0.05 m/s、步频与预期不符、回执过期、模块未上报（< 0.3.17）都会标红并说明原因。
- **协议与回读**：状态 Provider 新增 `speed_mps`（Double，缺省 NaN）与 `cadence_spm`（Int，缺省 -1）两列，模块写入**实际生效值**（步频按 App 与模块共用的 `StepCadence` 公式计算，避免两边算法漂移导致误报）；系统回执解析同步支持这两列，且保持旧回执兼容。
- **标准模式边界说明**：标准模式只写系统模拟位置、不注入传感器，因此步频校验会明确提示“需切换到 LSPosed 或 Root 模式”，参数面板在标准模式下也以红字提示步频不生效——不再让人误以为调了就有步数。
- 公共步频公式上移到 `core:model` 的 `StepCadence`，模块侧保留同名门面；新增 `InjectionVerifier` 纯函数与 10 个单测。
- 适配：`compileSdk`/`targetSdk` 保持 36（Android 16），沿用同一套 libxposed API 102 入口，未引入 Android 17 私有 API。模块 0.3.17 与主包 0.5.0 建议同步升级（不同步仍可模拟，但回读校验会提示模块未上报）。详见 [docs/WORK_MODES.md](docs/WORK_MODES.md)。

## 0.4.9 — 2026-11-05

主应用 **0.4.9** · 内置 Root 载荷 **0.1.4** · LSPosed 模块 **0.3.16**。

### 自定义模拟步频

- 「模拟参数 → 移动」新增 **模拟步频** 滑块：`自动`（按当前速度推算步长，步行约 92 步/分、跑步 160+，夹在 40–300）或 **1–300 步/分** 固定步频；支持点击数值精确输入与 5 个预设档位（自动 / 慢走 80 / 常规 110 / 跑步 160 / 冲刺 200）。
- 固定步频真正驱动 `TYPE_STEP_COUNTER` 与 `TYPE_STEP_DETECTOR`：计步器按 `dt × 步频/60` 累计（此前固定每秒 1 步），计步事件的间隔由步频决定（此前固定 500ms），静止时一律不出步数。
- 加速度计/线性加速度/重力的步态相位改为跟随步频（`2π × 步频/60 × t`），步事件与加速度峰值同相；0.4.8 之前固定 1.6Hz 的相位不再与实际步频脱节。
- 控制面新增 `step_cadence_spm` 字段，LSPosed 与 Root 两种系统级模式共用同一链路；模块侧 `Settings.Global` 镜像新增可选键 `cad`（0–300 整数），旧模块 APK 不写该键时按「自动」处理，升级期不会让传感器整体 fail-closed。
- 参数面板「恢复默认」与偏好导入导出一并覆盖步频，默认值为「自动」，因此旧设置升级后行为不变。
- 适配：`compileSdk`/`targetSdk` 保持 36（Android 16），未引入 Android 17 私有 API；模块 0.3.16 与主包 0.4.9 需同步升级，模块需手动重启。实现与协议见 [docs/STEP_CADENCE.md](docs/STEP_CADENCE.md)。

## 0.4.8 — 2026-11-03

主应用 **0.4.8（通用包约 39.15 MB）** · 内置 Root 载荷 **0.1.4** · LSPosed 模块 **0.3.15**。

- 修复系统已加载 LSPosed 模块、但安装包信息不可见时，仍提示“未检测到模块”并阻止启动的问题；导出诊断也不再把查询失败直接写成“未安装”。只有确实存在安装与加载版本不一致时才建议为此重启。
- 增加一条经过完整校验的系统状态回传路径，并修正多用户空间的回执寻址，改善部分系统上模块状态无法传回主应用的问题。
- 自定义 su 新增“自动检测 / -c 参数 / 标准输入”，兼容更多提权程序的命令接收方式；授权失败时区分路径不存在、无执行权限、格式不支持和超时。
- 缩小模块对系统进程和无关权限查询的影响，减少锁等待风险，改进稳定性。
- 配套模块升级到 **0.3.15**，内置 Root 载荷升级到 **0.1.4**。模块更新需手动重启生效；已加载旧 Root 载荷的设备也需手动重启。
- 小米 15 / Android 16 上已验证新模块实际加载、三分钟持续定位、取消/注销、卫星与传感器、停止后恢复订阅，以及关闭应用状态 Provider 后的备用回执通道。验证过程中未出现系统重启。
- 真我 Neo7 重启、Android 17 原故障机无回执、ColorOS Root 坐标跳动及具体临时 Root 工具兼容性仍待对应设备验证，尚不宣称全部解决。Root 0.1.4 的隔离进程加载检查通过，纯 Root 系统接管仍待单独复测；标准模式首帧标记仍待干净环境对照。详见 [核验记录与未完成项](docs/VERIFICATION_0.4.8.md)。
- Release 仅提供通用主包、模块和校验文件。签名与旧版一致，可直接覆盖安装；从 0.4.7 升级的 LSPosed 用户需同步更新模块并手动重启，已实际加载 0.3.15 的测试用户无需重复重启。

## 0.4.7 — 2026-11-02

- 主包由 106.35 MB 缩小到 **39.14 MB**，减少约 **63%**，保留 ARM 32/64 位兼容性。本次 Release 只提供通用主包、LSPosed 模块和校验文件。
- 主应用改为经过代码与资源裁剪的 Release 构建，去除未使用的依赖代码和调试工具。
- 原生库采用压缩打包，由 Android 在安装时解压；默认通用包保留 ARM 32/64 位兼容，也支持单独生成更小的 ARM64 包。
- 内置 Root 载荷升级为 0.1.3，删除调试信息和重复打包的原生库；保留完整系统钩子与反射入口，不改变原生加载代码。
- 保留高德/开放地图、路线、摇杆、三种运行模式及自定义 su 路径。现有 LSPosed 模块仍为 0.3.14。
- 签名与旧版一致，可直接覆盖安装。小米 15 / Android 16 上已验证升级、双地图显示、高德搜索与道路规划、路线移动/暂停/继续，以及 LSPosed 模式 180 秒持续定位和停止确认；713 帧更新无错误坐标，取消/注销/flush 检查通过。
- Root 0.1.3 的独立进程加载与 8 项钩子检查通过；**纯 Root 接管系统服务尚未单独复测**。标准模式 GPS/网络坐标输出正确，但当前多定位模块并存环境仍出现首个缓存 GPS 回调标记不一致，**尚未完成停用其他模块后的对照验证**。
- 不将上述结果视作所有 Android 17 机型或第三方业务均已兼容。详见 [真机核验](docs/VERIFICATION_0.4.7.md) 和 [体积优化记录](docs/APK_SIZE.md)。

## 0.4.6 — 2026-11-02

主应用 **0.4.6** · 内置 Root 载荷 **0.1.2** · LSPosed 模块 **0.3.14**。

### 自定义 Root 授权入口

- 在「偏好设置 → Root 授权」新增 **自定义 su 路径**，临时 Root 或非默认位置的提权程序也可以配置使用。留空使用系统默认 `su`，支持恢复默认。
- 新增 **检测授权**，无需开始模拟即可检查是否取得 Root 权限；路径无效、授权被拒绝或执行超时会显示原因。
- 首次授权检测最多等待 30 秒。自定义提权程序需支持 `su -c`，且允许 Geostep 执行；路径设置不能代替设备本身的 Root 能力。
- 路径仅在本机保存，不随偏好备份导入导出，诊断附件也会过滤路径内容。模拟运行期间不能修改路径。

### 全新图标

- 主应用图标改为与界面一致的蓝色风格：定位图钉加信号弧，圆形、圆角等各种桌面形状下都完整显示。
- 支持 Android 13 及以上的「主题图标」，开启后图标会跟随系统配色。
- LSPosed 模块不再显示系统默认的机器人图标，改用同款深色图标，在 LSPosed 管理器里更容易认出。
- 模拟中和悬浮摇杆的通知栏小图标改为同款图钉。

### LSPosed 模式兼容性

- 优化系统定位服务的加载时机，在服务就绪后补充尝试接管，最多等待 65 秒；为荣耀/华为扩展系统组件增加检查，只作用于确认承载定位服务的系统进程。
- 模块的加载过程、安装结果和错误会写入 **LSPosed 管理器的日志**，反馈问题时可以一并截图或导出。

### 问题排查更清楚

- 「运行环境」检查失败时，会说明具体卡在哪一步：命令发不出去、回执读不到、没有回执、回执过期，或运行模式不匹配。不再只显示笼统的「未收到回执」，也不会把这些情况一律当成「没启用模块」。
- 检查时会稍等可能延迟的系统回执（最多 0.45 秒），减少偶发的误报。
- 诊断日志新增应用包名、用户空间，以及安装包是否声明了模拟位置权限，便于排查「开发者选项里找不到 Geostep」的问题。

### 升级说明

- **LSPosed 用户**：同时更新主应用和模块 0.3.14，完整安装后保留「系统框架」作用域（荣耀/华为机型若曾勾选扩展系统组件也请保留），**手动重启手机**以加载新模块。
- **Root 用户**：只需更新主应用。内置载荷升级为 0.1.2，如果当前系统已加载旧载荷，请手动重启后再开始模拟。
- 签名与旧版一致，直接覆盖安装，不需要卸载。

### 已知情况

- 本版针对荣耀 Magic6 / Android 17 上「系统回执未收到」的问题做了兼容和诊断改进，**但尚未在该机型上完成真机验证，不能保证问题已解决**。如果仍然失败，请在「偏好设置 → 诊断与反馈」保存日志，连同 LSPosed 管理器中的 Geostep 日志发送到 kprxr.660@qq.com。
- Root 载荷 0.1.2 只更新了诊断代码，原生注入部分与 0.4.5 相同，但尚未单独完成纯 Root 真机复测。
- 自定义 su 路径已加入自动化验证；临时 Root 工具的实际兼容性仍取决于其执行方式与权限。完整验证范围见 [核验记录](docs/VERIFICATION_0.4.6.md)。

## 0.4.5 — 2026-11-01

主应用 **0.4.5** · 内置 Root 载荷 **0.1.1** · LSPosed 模块 **0.3.13**。包含此前未单独发布的 0.4.4 改动。用户发行附件仅包含主应用、LSPosed 模块及校验文件。

### 后台运行与诊断反馈

- 在“运行状态 → 运行环境”增加后台持续运行引导，说明“不受限制 / 无限制”、厂商省电设置及冻结管理的区别；支持打开本应用的省电设置，无法直达时回退到应用详情。
- 设置新增“诊断与反馈”：保存 `.txt` 日志，或生成附带日志的邮件草稿。关于区域新增联系邮箱 `kprxr.660@qq.com`、邮件联系和复制邮箱。
- 日志包含设备/系统/应用/模块版本、回执时效、钩子与心跳状态及近期带时间戳的应用事件；过滤地图 Key、凭据字段、链接和常见坐标字段，不直接序列化运行位置配置。
- 附件仅共享专用诊断缓存目录，授予选定邮件应用临时读取权限；无需额外存储权限，不会自动发送邮件。日志限当前应用进程，重启前的内存日志不会保留。
- 模拟前台服务增加有超时保护的 CPU 唤醒锁。小米真机对照发现，自动省电仍可能冻结前台服务；需用户将本应用设为厂商“不受限制”，单独添加标准电池豁免不足以解决该设备的冻结问题。

### 定位兼容改进

- 补齐 `ACCESS_MOCK_LOCATION` 声明，修复主应用不能被列为“模拟位置信息应用”候选的代码缺口。
- 根据实际运行的本地系统服务及其父类安装钩子，覆盖厂商重写入口；安装可重试且不叠加同一钩子，避免部分安装成功被误判为整体就绪。
- 增加监听器交付层处理，以及身份 getter/字段/继承层级兼容；保留接收方权限、豁免和取消/注销语义。
- 独立探针记录全程，持续检查默认 60 秒、支持 20–180 秒，检测后续错误坐标和超过 5 秒的断流；1.2 支持显式启用锁屏测试页面，不取消设备密码锁。

### 升级说明

- 从 **0.4.3 或更早正式版** 使用 LSP 升级：完整安装主包和模块 0.3.13，保持系统框架作用域，手动重启以加载新版模块。框架需支持 **libxposed API 102**。
- Root 用户只安装主包；如果当前系统已经加载旧 Root 载荷，需要手动重启后再启动新载荷。当前仍不支持与驻留的 LSPosed 同时使用。
- 已安装内部版 0.4.4，且已加载模块 0.3.13 / Root 0.1.1 的用户，此次只更新主包即可。

### 验证范围

- 小米 15 / Android 16 / LSP 0.3.13：亮屏 180 秒、777 帧无错误坐标；厂商“不受限制”且排除探针自身省电干扰后，90 秒测试（含 60 秒熄屏）296 帧通过。取消/注销/flush、GNSS/NMEA、传感器和动态豁免/恢复检查通过。
- 主应用 12 项单测、构建/Lint、6 项真机诊断导出/附件检查通过；共用钩子和引擎沿用此前已通过的回归，详见 [定位验证](docs/VERIFICATION_0.4.4.md) 与 [诊断验证](docs/VERIFICATION_0.4.5.md)。
- **ColorOS 17 / Android 17 / HyperOS 4 的原始故障机尚未完成同等验收；Root 0.1.1 尚未单独完成纯 Root 真机复测。** Root 0.1.0 的既有验收不能替代新载荷验证。系统接口通过也不代表所有第三方 SDK/业务都会接受结果。

## 0.4.4 — 内部验证版，改动随 0.4.5 发布

- 修复开发者选项无法列出 Geostep 的权限声明缺失。
- LSPosed 0.3.13 / Root 0.1.1 增加实际系统服务类识别、幂等安装、必需入口检查及监听器交付处理，避免辅助钩子安装成功被误判为定位已就绪。
- 模拟前台服务增加有超时保护的 CPU 唤醒锁，诊断增加心跳过期及回调状态。
- 独立探针记录全程，持续测试默认 60 秒，覆盖数秒后的回撤和断流。
- 小米 Android 16 的 3 分钟持续检查通过；定位到自动省电冻结主控的熄屏失效路径，厂商“不受限制”设置配合独立探针排除自身省电干扰后，60 秒熄屏验收通过。补充后台设置说明；测试探针 1.2 支持显式开启锁屏测试页面。
- ColorOS 17 / Android 17 / HyperOS 4 的反馈尚待故障设备复测。详见 [验证说明](docs/VERIFICATION_0.4.4.md)。

## 0.4.3 — 2026-10-31

主应用 **0.4.3** · 内置 Root 载荷 **0.1.0** · 可选 LSPosed 模块 **0.3.12**。GitHub Release 从本版起按主应用版本标记为 `v0.4.3`。

### 新增与修复

- 实现独立 Root 系统定位：内置 ARM64 加载器和系统端载荷，无需单独安装 Geostep 模块 APK。
- 通过独立控制通道核对系统进程、载荷版本和随机标识，避免借用 LSPosed 回执误报 Root 成功。
- 为首次 Root 初始化提供有上限的等待时间；丢失启动回执时保留停止清理责任，未确认停止不返回成功。
- 修复 Hook 安装失败仍计成功、回调元数据发布时序及重复安装问题。
- 修复旧注册回滚可能误删新订阅的并发问题。
- 高德默认未授权，统一授权确认入口；保存 Key 后继续切图，地图重建保留选点，修复无内置 Key 时的提示。
- 增加不读取个人内置 Key 的公开构建开关 `-PpublicBuild=true`、Root 第三方源码与许可、仓库内的设备验收脚本。

### 验收与限制

- 166 项 JVM/debug 测试与构建、Lint 通过；在 Xiaomi 15 / Android 16 / KernelSU、LSPosed 无驻留的环境完成纯 Root 系统定位验收。
- 覆盖 GPS/network/fused、WebView、PendingIntent、取消/注销/flush、GNSS/NMEA、动态豁免与停止恢复。详见 [验证说明](docs/VERIFICATION_0.4.3.md)。
- Root 目前仅支持 ARM64，不能与驻留的 LSPosed 同时运行；应用进程内传感器/SDK 缓存 Hook、其他 ROM 与第三方业务判定不在本轮通过范围内。
- LSPosed 模块 0.3.12 已通过构建、单测和 Lint，本轮未完成该模块版本的单独真机验收。Root 用户无需安装它。
- 公开 APK 不包含个人高德 Key，可使用 OpenStreetMap，或在设置中填入自己的高德 Android SDK Key。

## 0.4.2 — 内部验证版本，未单独发布

Root 能力误报修正、订阅回滚及地图授权修复经内部验证后，随 0.4.3 发布。

## 0.4.1 / Module 0.3.10 — 2026-10-31

历史 Release 标签：[`v0.3.10`](https://github.com/jie8190/Geostep/releases/tag/v0.3.10)。

- 发布地图选点、路线、收藏/历史、悬浮摇杆和个人高德 Key 配置等功能。
- 模块补充处理 ROM 重建监听器后的回调路径。
- **更正：该版 Root 代码仅包含进程内实验，尚未完成独立系统注入。独立 Root 系统定位在 0.4.3 实现并验收。**
