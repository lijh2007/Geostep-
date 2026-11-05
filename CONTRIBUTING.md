# 参与开发

维护者：[GitHub @sayonara](https://github.com/lijh2007) · 1590887927@qq.com

上游项目：[jie8190/MockGeo](https://github.com/jie8190/MockGeo)（感谢原作者开源）

# 参与贡献

感谢你愿意参与 Geostep。这个项目涉及系统级注入，改动面大，麻烦先读一遍下面的约定。

## 开发环境

* JDK 17、Android SDK（platform 36、build-tools 35+）
* Gradle 8.14.3（用仓库自带的 `./gradlew`）
* NDK 27.0.12077973 + CMake 3.28+（只有需要重编原生件时才装）
* 设备要求：Android 8.0+（API 26），注入类功能需要 Root 或 LSPosed

```bash
printf 'sdk.dir=/path/to/android-sdk\n' > local.properties
./gradlew :app:assembleDebug
```

如果没装 NDK，可以 `-PprebuiltNative=true` 复用现成的 `libmockgeo_art.so`（该文件不在版本库里）。

## 目录职责

| 目录 | 职责 | 改动注意 |
| --- | --- | --- |
| `core/model` | 协议常量、`SimConfig`、`StepCadence` | **纯 Kotlin、无 Android 依赖**，App 与模块共用同一份公式，改这里必须两边一致 |
| `core/route` | 轨迹与速度曲线 | 纯逻辑，需有单测 |
| `core/data` | 设置、历史（DataStore / Room） | 改字段要同时改 `SettingsJson` 的编码与解码，并保持旧导出文件可导入 |
| `core/engine` | 注入引擎与路由、回读校验 | 系统侧代码，注意 `RootEngine` / `XposedEngine` / `ProviderEngine` 的接口一致 |
| `module` | 进程内传感器与证据钩子 | 与 `rootpayload` **共用源码**（`sourceSets` 复用），改动要保证两边都能编译 |
| `rootpayload` | Root 载荷（C++/LSPlant） | 需要 NDK；`PayloadBootstrap` 的 `system` / `client:<pkg>` / `probe` 三种模式 |
| `app` | Compose UI | 面向用户的文案走 `stringResource`，避免硬编码中文 |

## 代码约定

* Kotlin 官方风格，4 空格缩进；Compose 中一个文件聚焦一个面板。
* 新增状态一律走不可变数据类 + `MutableStateFlow`，不要用可变字段跨线程共享。
* 涉及传感器/定位数值的公式必须放在 `core/model`，模块侧只做转发，保证 App 侧校验能算出同一结果。
* 新的控制面字段：`ControlPlane` 加常量 → `XposedEngine` 的 SET Bundle → `CommandDispatcher` → `ModuleConfig` → `StatusWriter` 镜像 JSON → `AppScopeConfig` 解析（**可选键要进 `OPTIONAL_KEYS`，否则旧版镜像会被整体判为非法**）。
* 提交前跑：`./gradlew :core:model:test :core:route:test :core:data:test :module:testDebugUnitTest :core:engine:testDebugUnitTest`。

## 提交信息

用 `type: 摘要` 的形式，例如：

```
feat(cadence): 支持 ±30% 步频浮动
fix(root): loader 接受 client:<package> 目标
docs: 补充 Android 16/17 适配说明
```

## 不要提交

* `local.properties`、keystore、`build/`、`.cxx/`、`rootpayload/build/root-native/`、`core/engine/src/main/jniLibs/`
* 任何真实用户的位置/轨迹数据
* 第三方应用的包名清单之外的逆向产物

## 合规

贡献的代码不能用于绕过第三方应用的正常计费、排名或健康数据审核流程；不接受以「刷量」为目的的功能（例如伪造多设备同步、批量账号打卡）。
