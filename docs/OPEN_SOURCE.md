# 开源交付说明（Geostep 0.6.8）

本文件说明这个仓库**哪些东西进源码、哪些不进、拿到源码后怎么编出可安装的 APK**，
以及必须遵守的第三方许可。仓库根目录的 `LICENSE`（Apache-2.0）、`NOTICE`、
`README.md`、`CONTRIBUTING.md`、`.github/workflows/android.yml` 是配套材料。

## 1. 进仓库的东西

| 路径 | 内容 |
| --- | --- |
| `app/` | 主应用（Compose / Material 3）：地图、参数面板、运行模式、注入校验 |
| `core/model/` | 纯 Kotlin 协议与公式：`ControlPlane`、`SimConfig`、`StepCadence`、`EngineState` |
| `core/engine/` | 引擎抽象、`EngineRouter`、`XposedEngine`、`ProviderEngine`、`RootEngine`、`RootDeployment`、`InjectionVerifier`（含 `src/main/cpp` 原生源码） |
| `core/route/`、`core/data/` | 路线规划 / DataStore 设置与导入导出 |
| `module/` | LSPosed 模块与 App 进程内注入：`AppScopeHooks`、`LocationClientHooks`、`SensorHooks`、`StepPump`、`StatusWriter` |
| `rootpayload/` | Root 载荷（复用 `module/` 源码）与 `src/main/cpp`（`injector.cpp`、`bootstrap.cpp`） |
| `probe/` | 诊断用探针 App（抓传感器/定位回调写 JSON，排查注入是否生效） |
| `docs/` | 协议、验证记录、Root 注入、步频说明、本文件 |
| `gradle/`、`gradlew*`、`settings.gradle.kts`、`build.gradle.kts` | Gradle 包装器与版本目录 |

## 2. 不进仓库的东西（`请自行构建或从 Release 提取`）

| 排除项 | 原因 | 替代做法 |
| --- | --- | --- |
| `core/engine/src/main/jniLibs/arm64-v8a/libmockgeo_art.so` | 预编译原生件，源码在 `core/engine/src/main/cpp`（LSPlant/Dobby/xDL） | 有 NDK 的机器上直接 `:core:engine:assembleRelease` 编译；或从官方 v0.4.8 Release 的 APK 里取同名文件（同 ABI） |
| `rootpayload/build/root-native/stripped/{libmockgeo_root.so,mockgeo_loader}` | 同上（`injector.cpp` / `bootstrap.cpp` 编出来的产物） | `:rootpayload:buildRootNative`（需要 NDK 27 + CMake ≥ 3.28） |
| `build/`、`.gradle/`、`.kotlin/`、`.cxx/`、`releases/` | 构建缓存 | — |
| `local.properties`、`*.keystore`、`*.jks` | 本机 SDK 路径与签名 | 自己的 `local.properties` → `sdk.dir=...` |
| `产出/`、`_dl/`、`toolchain/` | 本机打包与调试产物 | — |

`.gitignore` 已按上表配置。

## 3. 构建

环境：JDK 17、Android SDK（platform 36、build-tools ≥ 35.0.0）、Gradle 8.14.3（wrapper 会自取）。

### 3.1 只出 App（复用预编译原生件）

```bash
# 1) 把 libmockgeo_art.so 放到 core/engine/src/main/jniLibs/arm64-v8a/
# 2) 把 libmockgeo_root.so + loader 放到 rootpayload/build/root-native/stripped/
./gradlew :app:assembleRelease \
  -PprebuiltNative=true -PappAbis=arm64-v8a \
  -x :rootpayload:buildRootNative
```

> 本仓库开发机上就是这么出包的（原生逻辑本版未改动）。`-PprebuiltNative=true` 会跳过
> `core/engine` 的 CMake 构建，改用 `jniLibs` 里的 `.so`。

### 3.2 全量构建（含原生）

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
./gradlew :app:assembleRelease :rootpayload:assembleRelease -PappAbis=arm64-v8a
```

注意：

- 构建路径**不能包含非 ASCII 字符**，否则 CMake/ninja 会把依赖文件路径按本地代码页解码出错；
  遇到请加 `android.overridePathCheck=true` 并换到纯英文路径。
- `rootpayload` 的 `CMakeLists.txt` 要求 CMake ≥ 3.28（SDK 自带的 3.22 不够）。
- `:module` 只依赖本地 jar `core/engine/libs/libxposed-api-102.jar`（`compileOnly`），
  运行时装在 LSPosed 环境里。

## 4. 测试

```bash
./gradlew :core:model:test :core:route:test :core:data:test \
  :module:testDebugUnitTest :app:testDebugUnitTest :core:engine:testDebugUnitTest
```

当前基线：**253 个用例全通过**（core:model 25、core:route 47、core:data 9、module 78、
app 29、core:engine 65）。全部是 JVM 单测，不需要设备。

## 5. 发布流程

```bash
./gradlew :app:assembleRelease :app:assembleDebug :module:assembleDebug \
  -PprebuiltNative=true -PappAbis=arm64-v8a -x :rootpayload:buildRootNative
# 产物
#   app/build/outputs/apk/release/app-release.apk
#   app/build/outputs/apk/debug/app-debug.apk
#   module/build/outputs/apk/debug/module-debug.apk
sha256sum app-release.apk app-debug.apk module-debug.apk > SHA256SUMS.txt
```

发布时请同时更新 `CHANGELOG.md` 与 `README.md` 顶部的版本行。

## 6. 第三方许可（详见 `NOTICE`）

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| LSPlant | LGPL-3.0 | ART 内联钩子（`core/engine/src/main/cpp`） |
| Dobby / xDL | Apache-2.0 | 函数内联与动态加载 |
| libxposed API 102 | Apache-2.0 | 模块入口（本地 jar） |
| AndroidX / Compose / Material 3 | Apache-2.0 | UI |
| osmdroid | Apache-2.0 | 开源地图瓦片 |
| 高德地图 SDK | 高德自有条款 | 可选地图源，商用需自行申请 Key 并遵守其条款 |

LGPL-3.0 的 LSPlant 以**源码形式**在仓库内，修改后再分发需同样开源该部分；
`libmockgeo_art.so` 按本仓库策略不随源码分发。

## 7. 合规提醒

本项目用于位置与传感器模拟的**研究与测试**。使用前请确认目标应用的服务条款与当地法律，
不得用于考勤作弊、竞赛成绩、保险理赔等欺诈场景。作者不对使用后果负责。
