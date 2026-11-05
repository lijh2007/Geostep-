# Root 模式下的 App 传感器注入（0.6.3 起可用）

Root 直连模式默认只接管**系统定位**，不会碰 App 自己读的传感器——所以步道乐跑、小米运动健康这类
按步数/步频判断的 App，在 Root 模式下拿到的仍是真实传感器数据：手机不动就是 0 步。

0.6.3 起把载荷注入**指定 App 进程**，让它的传感器回调跟随模拟步频。这条路径已在一台
Redmi K60（HyperOS / Android 17）上实测通过。

## 两个阶段的分工

| 组件 | 作用 |
| --- | --- |
| 状态镜像（`Settings.Global.geostep_state_v1`） | 主应用每 3 秒用 su 写一次当前模拟参数，15 秒过期；注入进目标进程的模块读它 |
| 进程内载荷（`client:<package>`） | ptrace 注入 → dlopen `libmockgeo_root.so` → 加载 `payload.apk` → 装 `AppScopeHooks`（传感器/证据钩子） |

> 历史包袱：早期版本把载荷注入 `system_server` 做系统级 Hook。部分 ROM（含 HyperOS / Android 17）上
> LSPlant 与 ART 不兼容，会直接整机重启（栈顶为 `lsplant::SetClassStatus_`），因此 0.6.x 默认关闭系统载荷，
> Root 模式改为「状态镜像 + 应用进程注入」。

## 怎么用

1. 主应用选「Root 直连」并授权 su，正常开始模拟（位置先生效）。
2. **先打开目标 App**（注入是基于进程的，进程不在就无法注入）。
3. 回到 Geostep →「模拟参数」→「Root 传感器注入（实验）」→ 填包名 → 点「注入」。
   - 小米运动健康 `com.mi.health`（部分机型为 `com.xiaomi.hm.health`）
   - 步道乐跑 `com.lptiyu.tanke`
   - 不确定包名：`adb shell pm list packages | grep -i <关键词>`
4. 回到目标 App 开始跑步/步行；步数应随模拟步频增长，速度为 0 时不出步。

## 判定注入成功

- 界面提示「已注入 …」＝宿主已确认目标进程里出现了我们的载荷。
- 手动核对：`adb shell su -c "grep -c geostep /proc/$(pidof com.lptiyu.tanke)/maps"` 大于 0。
- 日志：`adb logcat -s GeostepModule:V` 出现
  - `client bootstrap: package=… loader=…`
  - `app-scope hooks installed`
  - `step pump: delivered=N type=18 spm=…`（type 18 = `TYPE_STEP_DETECTOR`）

## 实测证据（0.6.3）

- 探针 App（`com.geostep.probe`，本仓库 `probe/` 模块）在注入后收到连续 `TYPE_STEP_DETECTOR`：
  事件间隔 495–510ms，对应 120 步/分；日志 `step pump: delivered=20 type=18 spm=123`。
- 同一进程 18 条传感器采样中 12 条命中合成波形（加速度、陀螺、磁力、重力、旋转向量、线性加速度）。
- 标准模式同时复测无回归：`dumpsys location` 的 `last mock location` 为下发坐标、`vel=1.4`、`satellites=12`。

## 关键实现细节（排障用）

- 落点：把 `libmockgeo_root.so` 与 `payload.apk` 复制到目标 App 的 `/data/app/~~<hash>/<pkg>-<hash>/lib/arm64/`
  （`chmod 644`、`chcon u:object_r:apk_data_file:s0`），再 `loader <pid> <so> <apk> <nonce> client:<pkg>`；
  注入结束删除临时文件。目标 App 的 `/data/data` 在 root shell 里可能不可见（命名空间隔离），所以不能用私有目录。
- 进程挑选：优先 `cmdline` 等于包名且 `basename(readlink /proc/<pid>/exe) == app_process64` 的进程；
  32 位进程、只有子进程在跑的情况会提示「主进程没有在运行（或不是 64 位进程）」。
- `SensorEvent` 构造：目标进程若禁止反射构造（hidden API 策略），`StepPump` 退化为复用 App 收到的真实事件对象
  作为模板（改 `values` / `timestamp` / `sensor` 后重投），泵不会因为构造失败停摆。
- 监听器时机：钩子只对**注入之后**注册的监听器生效；注入完再让 App 进入运动页注册传感器最稳。

## 边界与注意事项

- 目标 App **重启/被系统杀死后注入失效**，需要重新注入；停止模拟会清理状态。
- 设备上同时驻留 LSPosed 时 Root 注入会被拒绝（两套钩子会打架）：改用 LSPosed 模式并把 App 加进作用域。
- 只覆盖 `SensorManager` 取数路径。走 **Health Connect / 厂商健康服务 ContentProvider** 的 App 读到的仍是真实数据。
- 目标 App 需要「身体活动」（`ACTIVITY_RECOGNITION`）权限，否则系统层不给它计步数据。
- Geostep 进程被系统冻结后镜像会停止续期（15 秒过期），模拟期间建议保持前台或加后台白名单。
- 注入属实验能力：加固/反调试 App 可能失败，界面会给出原因。

## 实现位置

- `rootpayload/.../PayloadBootstrap.kt`：`mode = "client:<package>"` 分支 → `AppScopeHooks.install(...)`。
- `core/engine/.../RootDeployment.kt`：`injectClient()`、`clientCopy()`、`mainProcess()`、`publishState()`、`readState()`。
- `core/engine/.../RootStatePublisher.kt`：把模拟参数写成 `geostep_state_v1` 镜像。
- `core/engine/.../RootEngine.kt`：clientMode、3 秒续期循环、`verifyApplied()` 回读判定。
- `module/.../SensorHooks.kt`、`module/.../StepPump.kt`、`module/.../SensorSynth.kt`：进程内注入本体。
- `app/.../HomeViewModel.kt`、`app/.../ui/Panels.kt`：注入入口与状态提示。

## 客户端定位接管（0.6.7 起）

注入进目标进程的载荷除了传感器，还会接管 `android.location.LocationManager`：

| 入口 | 行为 |
| --- | --- |
| `getLastKnownLocation` | 直接返回模拟坐标（`mock=true`） |
| `getCurrentLocation` | 用传入的 `Executor` 回投模拟坐标 |
| `requestLocationUpdates` | 注册后每 1 秒补投一次模拟坐标（速度、卫星数随下发值） |

镜像有效期放宽到 **5 分钟**（`AppScopeConfig.INJECT_FRESH_MS`）：Root 模式下 Geostep 退到后台
会被 HyperOS 冻结，若仍按 15 秒判定，切到高德/运动 App 时注入就失效了。

排查日志（目标进程，tag `GeostepModule`）：

```
client bootstrap: package=<pkg> loader=…
location client hooks installed: transports=… last-known=2 current=3 updates=12
request-location-updates: active=true args=5
app-scope hooks installed
```

`transports=0` 表示该 ROM 上拿不到内部回调传输类，此时自动退回"每秒补投"方案（实测同样能让高德显示模拟位置）。

## 多进程注入与超时（0.6.8）

高德、地图类 App 会把定位单独跑在子进程里（`com.autonavi.minimap:locationservice`），
所以注入目标是**该包全部 64 位进程**，不是主进程一个：

- 枚举：`ps -A -o PID=,NAME=` + `awk` 前缀匹配 + `readlink /proc/<pid>/exe` 必须为 `app_process64`。
  不要用 `pidof <pkg>`——它只按主进程名匹配。
- 单个进程的 `loader` 远程调用可能超时（`remote call timed out`），`injector.cpp` 的超时已提到
  20s / 30s，App 侧还会重试 3 次。
- 运行期间每 6 秒扫一次目标包的新进程自动补注入（高德的定位进程常常是后起的）。

## 系统级兜底（0.6.8）

注入失败也不至于完全没效果：Root 模式启动时会：

```
appops set <pkg> android:mock_location allow
settings put secure mock_location <pkg>
```

然后由 `ProviderEngine` 把假位置推给系统 provider。任何进程（包括注不进去的子进程）都能读到位置，
但这些进程里 `isFromMockProvider()` 仍为 true，“无痕”只在注入成功的进程里有效。
