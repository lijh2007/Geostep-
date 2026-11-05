# 自定义模拟步频（0.6.3 / 模块 0.3.21）

面向机器人开发与自动化校验：需要「手机被以固定节奏携带行走」的传感器证据时，可直接指定步频，
而不是按速度被动生成。

> 生效前提：**只有 LSPosed 模式或 Root 直连模式会注入传感器**；标准模式（系统模拟位置）不产生步数，
> App 参数面板会红字提示，切换模式后自动恢复。0.5.0 起开始模拟后还会回读校验实际生效步频，
> 见 [WORK_MODES.md](WORK_MODES.md)。

## 1. 用户可见行为

入口：**模拟参数 → 移动 → 模拟步频 (步/分)**（`ParametersPanel`，滑块 + 精确输入 + 预设胶囊）。

| 设定 | 语义 |
| :--- | :--- |
| `自动`（0，默认） | 按当前速度推算：`stride = 0.42 + 0.23 × v`（m），`步频 = v × 60 / stride`，夹在 40–300。1 m/s ≈ 92 步/分，3 m/s ≈ 162 步/分。 |
| `1–240`（滑块）/ `1–300`（精确输入） | 固定步频，运动中按此节奏出步。 |
| `步频浮动`（0–30%，默认 8%） | 在均值上下按正弦摆动；`0%` = 恒定节奏（旧行为）。两条正弦相位不同，一个周期约 45 秒，看起来像真人越走越快/越走越慢。 |
| `浮动周期`（10–120 秒，默认 45） | 越短越「抖」，越长越像长距离配速漂移。 |
| 任意设定 + 静止 | 速度 `0` 时**不产生任何步数**（保持旧版语义，避免「站着刷步数」）。 |

- 计步器 `TYPE_STEP_COUNTER`：会话内单调递增，每帧 `total += dt × 瞬时步频 / 60`，暂停即停表、恢复即续算。
  瞬时步频 = `StepCadence.instantSpm(均值, now, 浮动%, 周期)`，因此累计值始终等于「这段时间的平均步频 × 时长」。
- 计步事件 `TYPE_STEP_DETECTOR`：相邻间隔按瞬时步频算，`60000 / 瞬时步频` 毫秒（下限 80ms）；
  浮动开启后事件间隔会随之变化（例如 120 步/分 ±8% → 464–543ms 之间摆动），不再是等间隔脉冲。
- 加速度计/线性加速度/重力：步态相位 `2π × 瞬时步频/60 × t`，波形为真实行走量级 ——
  垂直 `1.15 × strength × (sin φ + 0.32 sin 2φ)`、横向 `0.18 × strength × cos φ`、前后 `0.22 × strength × sin(φ+0.4)`，
  `strength = min(v/1.4, 1.5)`。0.5.0 及以前振幅只有 `0.04 / 0.35 m/s²`，**靠加速度计判步的 App 会判不出来**，0.5.1 修复。

## 2. 分层与代码位置

```
ParametersPanel / HomeViewModel.setStepCadence      app/src/main/kotlin/com/geostep/app/ui/
  → AppSettings.stepCadenceSpm (DataStore)          core/data/.../prefs/SettingsRepository.kt
  → SimConfig.stepCadenceSpm                        core/model/.../model/SimConfig.kt
  → ControlPlane.KEY_STEP_CADENCE ("step_cadence_spm")
  → XposedEngine.publishLocked → sendExtraCommand(SET)  core/engine/.../XposedEngine.kt
  → CommandDispatcher.handleSet                     module/.../CommandDispatcher.kt
  → ModuleConfig.stepCadenceSpm (0..300 校验)        module/.../ModuleConfig.kt
  → StatusWriter.publishScopeState → Settings.Global JSON 键 "cad" / "cav" / "cap"
  → AppScopeConfig.ScopeState.cadenceSpm / cadenceVariabilityPercent / cadencePeriodSec（均可选，缺省取默认值）
  → SensorHooks.StepAccumulator / SensorSynth       module/.../SensorHooks.kt, SensorSynth.kt
  → StepCadence.effective / instantSpm / intervalMs   core/model/.../StepCadence.kt（纯函数，App 与模块共用同一份公式）
```

Root 直连模式复用同一个 `XposedEngine`（`ControlPlane.ROOT_PROVIDER`），因此步频在 LSPosed 与 Root
两种系统级模式下都走同一条 SET 链路，无需额外改动。

## 3. 线协议

SET 请求新增一个**可选**字段：

```text
Bundle: step_cadence_spm             = Int   // 0..300；缺省 = 沿用模块当前值
Bundle: step_cadence_variability_pct = Int   // 0..30；缺省 = 沿用模块当前值
Bundle: step_cadence_period_sec      = Int   // 10..120；缺省 = 沿用模块当前值
```

`Settings.Global` 状态镜像（`geostep_state_v1`）新增一个**可选**键：

```json
{"e":1,"h":1,"lat":39.9,"lng":116.4,"spd":1.2,"sat":12,"ts":1700000000000,
 "et":1000,"boot":4,"session":10,"alt":50,"brg":90,"cad":120,"cav":8,"cap":45,"x":"com.a"}
```

- 读端（`AppScopeConfig.parse`）把 `cad` / `cav` / `cap` 当作**可选键**：12 键旧镜像、只带其中一两个键的镜像都能解析，
  但除这三个之外出现任何陌生键仍然 fail-closed（整条作废 → 传感器不注入）。
- `cad` 必须是 0–300 的整数、`cav` 0–30、`cap` 10–120，越界同样作废，与其它字段的严格校验一致。
- 200ms 发布去重条件加入步频：只改步频、其它字段不变时也会立即重发镜像，不需要等到下一个 200ms 窗口。

## 4. 机器人开发用法建议

1. 固定步频 + 定点模拟：`speed` 仍需要 > 0 才有步数。若要「人原地踏步」的证据，可把移动速度设成很小值
   （例如 `0.5 km/h`）再把步频设成 110–120；此时位移极小、步数节奏稳定。
2. 固定步频 + 路线模拟：步频与路线速度独立，可模拟「车在走、人在走」这类传感器与位移不一致的对抗场景。
3. 自动化断言：`StepCadence.effective/intervalMs` 是纯 JVM 函数，脚本可直接用相同公式预测 60 秒内的步数
   （`步数 = 时间 × 步频 / 60`）。
4. 读步频：标准模式（`provider`）不涉及传感器注入；如需读回模块侧实际生效值，走状态回执（`GET_STATUS`）
   或 `Settings.Global` 的 `geostep_state_v1.cad`。

## 4.1 第三方运动 App（小米运动健康等）读不到步数/步频怎么办

按下面顺序自查，绝大多数情况是第 1、2 条：

1. **目标 App 必须在 LSPosed 的作用域里**。Geostep 是「在目标进程内改写传感器事件」，没勾选作用域的 App 完全看不到注入。
   Root 直连模式则是系统级注入，一般无需配置作用域，但需要 su 授权成功。
2. **授予「身体活动」权限**（`ACTIVITY_RECOGNITION`）。Android 10 起，App 读 `TYPE_STEP_COUNTER` 前会检查这个权限；
   没授权时它根本不会注册监听器，也就没有事件可改写。
3. **确认 App 走的是传感器**。小米运动健康这类 App 可能从 Health Connect / 系统健康数据读取步数，
   那条链路由系统健康服务聚合，不经过 `SensorManager`，Geostep 改不到 —— 此时注入日志里能看到「步数事件 0 次」。
4. **看 `诊断 → 注入回读校验`**：步频那条会显示模块上报的期望值/实际值；再用 logcat 看 `Geostep` 标签是否出现
   `step counter rewrite` 之类的日志，可判断监听器是否被接管。
5. **波形太弱的历史问题已在 0.5.1 修复**：0.5.0 及以前的加速度振幅只有 0.04/0.35 m/s²，
   靠加速度计做峰值检测的 App 会认为「没在走路」。0.5.1 的垂直峰值提升到 1.15–1.7 m/s² 并与步事件同相。

> 仍读不到时，最可靠的办法是切到 **Root 直连模式**（系统级注入，不受作用域影响），或在 LSPosed 里把目标 App
> 与「系统框架」都勾上并手动重启。**Health Connect / 系统健康数据本身不做伪造**，这是有意为之的边界。

## 4.2 静止时也出步数：自驱动事件泵（0.5.2）

真实计步传感器是**事件驱动**的：手机不动，`SensorService` 就不产生事件，我们也无处改写，
于是运动 App 的列表一直停在 0。0.5.2 在模块里补了一个事件泵（`module/.../StepPump.kt`）：

| 条件 | 行为 |
| --- | --- |
| App 注册了 `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` 监听器 | 先让真实传感器工作 1.2 秒 |
| 真实传感器 1.5 秒没有步事件，且正在模拟移动 | 按**瞬时步频**（含浮动）自己构造 `SensorEvent` 投递 |
| 真实传感器恢复上报 | 泵立即让位，避免同一段时间出双份步数 |
| 事件对象构造失败（hidden-API 拒绝） | 停泵 + 记一条 `ModuleLog`，不抛异常、不影响真实事件转发 |

- 计数器语义：`TYPE_STEP_COUNTER` 的 `values[0]` 从 0 起按瞬时步频积分；`TYPE_STEP_DETECTOR` 每次给 1，间隔 `60000/步频`（下限 80ms）。
- 该泵只作用于**已勾选作用域**的 App 进程，且需要该 App 真正通过 `SensorManager` 读步数；
  走 Health Connect / 系统健康数据的 App 依旧不受影响（见上一节）。
- 想确认是否生效：`adb logcat -s Geostep` 里能看到 "step pump" 相关日志（构造被拒时有一条警告）。

## 5. Android 16 / 17 说明

- `compileSdk = targetSdk = 36`（Android 16）；应用与模块都不使用 Android 16 专有 API 之外的新接口，`minSdk = 26`。
- Android 17（API 37）沿用 `SensorManager.registerListener` 与 `LocationManager.sendExtraCommand` 两处入口，
  模块通过 libxposed API 102 反射遍历 `declaredMethods` 注册钩子，未新增任何 17 私有 API。
- 传感器链路（`SensorHooks` → `StepCadence`）不依赖系统版本分支，16/17 走同一代码路径；本仓库未在 17 真机上
  完成验收，相关机型问题见 [VERIFICATION_0.4.4.md](VERIFICATION_0.4.4.md)。

## 6. 测试

```bash
# 纯 JVM 单测（步频换算、累计器、镜像解析、证据合成）
./gradlew :module:testDebugUnitTest

# 关键用例
#  StepCadenceTest              步频=自动/固定/越界、间隔与步态频率
#  CompatibilityRegressionTest  暂停保持累计值、步频决定事件间隔、cad 可选键校验
#  EvidenceTemplatesTest        60 秒步数、自动步频与静止不出步数
```

## 7. 本次改动的验证记录

用 JDK 17 + JUnit Platform Console 直接跑各模块编译产物（Gradle 的 Test Executor 在本机分发出的 worker
进程会因为工程路径含空格/中文而拿不到主类，属于环境问题，因此改为 dump classpath 后直跑）：

| 模块 | 用例数 | 结果 |
| --- | --- | --- |
| `:module` | 78 | 全部通过（含 `StepPumpTest`） |
| `:core:model` | 25 | 全部通过 |
| `:core:route` | 47 | 全部通过 |
| `:core:data` | 9 | 全部通过 |
| `:core:engine` | 65 | 全部通过 |

合计 **238 个单测全部通过**（module 78、core:engine 65、core:route 47、core:model 25、app 14、core:data 9），其中与步频直接相关的新增/修改用例：

- `StepCadenceTest`：`auto cadence follows speed`、`fixed cadence wins over speed and is capped`、
  `stationary never produces steps even with a fixed cadence`、`interval and gait frequency derive from cadence`、
  `zero variability keeps the cadence exactly constant`、`variability is deterministic for the same instant`、
  `variability stays inside its band and averages near the mean`（120 步/分 ±15% 实测 min 103.28 / max 137.86 / avg 119.84）、
  `variability is clamped by physical bounds`。
- `CompatibilityRegressionTest`：`step counter follows configured cadence`、`step counter auto cadence scales with speed`、
  `detector spacing follows configured cadence`、`cadence mirror field is optional but validated when present`、
  `paused step counter holds accumulated value`（暂停语义未回退）。
- `InjectionVerifierTest`：`cadence tolerance absorbs rounding and reports the sway`（取整 ±1 容忍、提示带「浮动 ±8%」）。
- `EvidenceTemplatesTest`：`steps advance with time only while moving`（自动步频 60 秒 92 步、固定 60 步/分 60 步、静止 0 步）。

`app` 侧改动（`Panels.kt` 滑杆、`HomeViewModel.setStepCadence`、`SimController.buildConfig`）已用
`:app:compileDebugKotlin` 验证编译通过，无新增编译错误（仅命中既有的 `Icons.Filled.AltRoute` 弃用提示）。

### 产物与本次出包方式

原生库本次改动未涉及（`core/engine/src/main/cpp`、`rootpayload/src/main/cpp` 一行未改），因此复用上游 v0.4.8 官方包里的
预编译件，只重新构建 Kotlin/Dex 部分：

| 预编译件 | 来源 | 大小 |
| --- | --- | --- |
| `libmockgeo_art.so` | 官方 v0.4.8 `lib/arm64-v8a/` | 14,464 |
| `libmockgeo_root.so` | 官方 v0.4.8 `assets/root/` | 944,784 |
| `loader` | 官方 v0.4.8 `assets/root/` | 700,000 |
| `payload.apk` | **本仓库重新构建**（含步频浮动与自驱动步数泵，模块 0.3.19） | 1,131,287 |

`core/engine/build.gradle.kts` 因此增加了 `-PprebuiltNative=true` 开关：置真时跳过 `ndkVersion` 与两处
`externalNativeBuild`，改从 `src/main/jniLibs` 取预置 `.so`；默认行为（不传该参数）与上游完全一致。

构建命令（本机实测通过）：

```bat
gradlew --init-script <mirrors.gradle> -p <repo> ^
  -Pandroid.overridePathCheck=true -PprebuiltNative=true -PappAbis=arm64-v8a ^
  :app:assembleRelease :module:assembleDebug -x :rootpayload:buildRootNative
```

产物：`app/build/outputs/apk/release/app-release.apk`（27.8 MB，R8 混淆后单 dex）、
`app/build/outputs/apk/debug/app-debug.apk`（49.0 MB）、`module/build/outputs/apk/debug/module-debug.apk`（1.5 MB）。
已核验：APK 签名有效；`classes*.dex` 含 `step_cadence_spm` / `stepCadenceSpm` /「模拟步频」；
`assets/root/payload.apk` 内的 dex 含 `step_cadence_spm` / `cad`（Root 模式同样带步频）。

⚠️ 若要从零编译原生库（而不是复用预编译件）：需要 NDK r27（`27.0.12077973`）+ CMake ≥ 3.28 + ninja，
且**工程路径不要含中文**——本机 `D:\deep seek生成` 会让 ninja 把依赖路径按本地代码页解码成乱码
（路径中的中文会被解码成乱码），链接阶段报 `clang++: error: unable to execute command: unspecified system_category error`。
纯 Kotlin 侧构建不受影响。

## 4.3 小米运动健康 / HyperOS 读不到步数怎么查（0.5.3）

第三方运动 App 拿步数有三条路，只有第一条能被本模块改写：

| 取数路径 | 例子 | 本模块能否覆盖 |
| --- | --- | --- |
| `SensorManager` 读 `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` / 加速度计 | 小米运动健康的「手机计步」、Keep、悦跑圈 | ✅ 能（需在 LSPosed 作用域内） |
| Health Connect / 系统健康数据（数据由系统健康服务聚合） | 部分机型的「健康连接」数据源、系统锁屏步数卡片 | ❌ 不能（不经过应用进程的传感器回调） |
| 厂商健康服务 ContentProvider（小米健康 / 穿戴服务聚合） | 小米运动健康里的「设备同步步数」 | ❌ 不能 |

### 三步定位

1. **确认注入进了目标进程**（决定性）：

   ```bash
   adb logcat -c && adb logcat -s Geostep
   ```

   然后按住小米运动健康 → 应用信息 → **强行停止** → 重新打开，做一段模拟。

   | 日志 | 含义 | 处理 |
   | --- | --- | --- |
   | 一条 `Geostep` 日志都没有 | 模块没进这个进程 | LSPosed 勾选 `com.xiaomi.hm.health`（小米运动健康），勾选后**强制停止再打开**；HyperOS 上把省电策略设为「无限制」 |
   | `app queried getDefaultSensor type=19 pkg=com.xiaomi.hm.health` | App 在找计步器 | 正常，继续看下一条 |
   | `listener registered via registerListener type=19 …` | 注入已生效 | 若仍无步数 → 该页面用的是系统聚合数据，见上表第 2/3 行 |
   | `step pump: delivered=20 type=19 spm=118 value=237 …` | 合成事件正在投递 | 说明链路通了；App 仍不显示则看它是否只认「同步步数」 |

2. **确认主应用处于运行态**：LSPosed / Root 模式，状态是「运行中」，步频设为固定值（如 120）便于比对。

3. **确认权限**：Android 10+ 读计步器需要「身体活动 / 健身运动」权限；小米运动健康还需要「自启动」「后台运行」不被限制。

### 常见包名

| 应用 | 包名 |
| --- | --- |
| 小米运动健康 | `com.xiaomi.hm.health` |
| 小米健康 | `com.mi.health` |
| 小米穿戴 | `com.xiaomi.wearable` |
| Google Fit | `com.google.android.apps.fitness` |
| Keep | `com.gotokeep.keep` |
| 华为运动健康 | `com.huawei.health` |

### 0.5.3 为「读不到」做的改动

- 监听器注册后**立即**投一帧合成事件（原来固定等 1.2 秒），轮询式读取不再错过。
- 合成 `TYPE_STEP_COUNTER` 从真实传感器的最后一个累计值继续累加，运动 App 不会看到「步数归零」。
- 覆盖 `requestTriggerSensor` / `cancelTriggerSensor`（触发式传感器入口）。
- 上述日志：`getDefaultSensor` / `getSensorList` 查询记一次、计步监听器注册记一次、事件投递每 20 条记一次（全部带包名，不含目标 App 身份以外的隐私信息）。
