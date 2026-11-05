package com.geostep.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geostep.app.checks.PermissionChecker
import com.geostep.app.geo.NominatimClient
import com.geostep.app.geo.OsrmClient
import com.geostep.app.geo.AmapGeoClient
import com.geostep.app.geo.AmapKeyStore
import com.geostep.app.BuildConfig
import com.geostep.app.diagnostics.DiagnosticFiles
import com.geostep.app.diagnostics.DiagnosticReport
import com.geostep.app.diagnostics.SupportContact
import com.geostep.app.sim.SimController
import com.geostep.app.transfer.GpxTransfer
import com.geostep.app.sim.SimulationService
import com.geostep.core.data.prefs.AppSettings
import com.geostep.core.data.prefs.SettingsRepository
import com.geostep.core.data.repo.LocationRepository
import com.geostep.core.engine.EngineRouter
import com.geostep.core.engine.RootAccess
import com.geostep.core.engine.XposedEngine
import com.geostep.core.model.*
import com.geostep.core.route.Gpx
import com.geostep.core.route.EditableRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

class HomeViewModel(
    private val appContext: Context,
    private val controller: SimController,
    private val settingsRepo: SettingsRepository,
    private val repo: LocationRepository,
    private val nominatim: NominatimClient,
    private val osrm: OsrmClient,
    private val router: EngineRouter,
) : ViewModel() {
    val sim = controller.ui
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings
    private val _settingsReady = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = _settingsReady
    private val _selected = MutableStateFlow<LatLng?>(null)
    val selected: StateFlow<LatLng?> = _selected
    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName
    val favorites = repo.observeFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val history = settings.map { it.historyLimit }.distinctUntilChanged().flatMapLatest { repo.observeHistory(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routes = repo.observeRoutes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _searchResults = MutableStateFlow<List<NominatimClient.Place>>(emptyList())
    val searchResults: StateFlow<List<NominatimClient.Place>> = _searchResults
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _checks = MutableStateFlow<List<CheckResult>>(emptyList())
    val checks: StateFlow<List<CheckResult>> = _checks
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _diagnosticBusy = MutableStateFlow(false)
    val diagnosticBusy: StateFlow<Boolean> = _diagnosticBusy
    private val _checkingRoot = MutableStateFlow(false)
    val checkingRoot: StateFlow<Boolean> = _checkingRoot
    private val _savingRootPath = MutableStateFlow(false)
    val savingRootPath: StateFlow<Boolean> = _savingRootPath
    private val _rootAccessCheck = MutableStateFlow<CheckResult?>(null)
    val rootAccessCheck: StateFlow<CheckResult?> = _rootAccessCheck

    /** 三种工作模式各自的自检结果（模式选择器展示“是否真的可用”）。 */
    private val _modeChecks = MutableStateFlow<Map<String, List<CheckResult>>>(emptyMap())
    val modeChecks: StateFlow<Map<String, List<CheckResult>>> = _modeChecks

    /** 最近一次注入回读校验结果（坐标/速度/步频是否真的生效）。 */
    private val _injectionChecks = MutableStateFlow<List<CheckResult>>(emptyList())
    val injectionChecks: StateFlow<List<CheckResult>> = _injectionChecks
    private val _verifyingInjection = MutableStateFlow(false)
    val verifyingInjection: StateFlow<Boolean> = _verifyingInjection

    /** Root 模式：把载荷注入目标 App 进程的状态（实验能力）。 */
        private val rootInjector = RootInjectionController(router, settingsRepo)
    val rootInject: StateFlow<RootInjectState> = rootInjector.state
    private val _routeDraft = MutableStateFlow<List<LatLng>>(emptyList())
    val routeDraft: StateFlow<List<LatLng>> = _routeDraft
    private val _routeControls = MutableStateFlow<List<LatLng>>(emptyList())
    val routeControls: StateFlow<List<LatLng>> = _routeControls
    private val _routePlanned = MutableStateFlow(false)
    val routePlanned: StateFlow<Boolean> = _routePlanned
    private val _routeProfile = MutableStateFlow("drive")
    val routeProfile: StateFlow<String> = _routeProfile
    private var detailedDraft = emptyList<RoutePoint>()
    private var controlDetails = emptyList<RoutePoint>()
    private var draftRevision = 0L
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning
    private var searchJob: Job? = null
    private var locatingJob: Job? = null
    private var addressJob: Job? = null
    private var planningJob: Job? = null
    private var planningGeneration = 0L
    private var searchGeneration = 0L
    private var selectionGeneration = 0L
    private val amap = AmapGeoClient(appContext)

    init {
        viewModelScope.launch { controller.notices.collect { _message.value = it } }
        viewModelScope.launch { settingsRepo.settings.collect {
            _settings.value = it
            controller.updateSettings(it)
            _settingsReady.value = true
        } }
        viewModelScope.launch {
            settingsRepo.settings.map { it.historyLimit }.distinctUntilChanged().collect { repo.setHistoryLimit(it) }
        }
        refreshChecks()
        refreshModeChecks()
        viewModelScope.launch { watchInjection() }
    }

    private fun work(success: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); success?.let { postMessage(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { postMessage(error.message ?: "操作失败，请重试") }
        }
    }

    fun refreshChecks() {
        work {
            val current = settingsRepo.settings.first()
            _checks.value = engineChecks(current)
        }
    }
    private fun useAmap(prefs: AppSettings = settings.value) =
        prefs.mapProvider == "amap" && prefs.amapConsent && AmapKeyStore.hasAvailableKey(appContext)

    fun select(pos: LatLng?) {
        selectionGeneration++
        _selected.value = pos
        _selectedName.value = null
        addressJob?.cancel()
        if (pos != null) addressJob = viewModelScope.launch {
            delay(1000)
            if (_selected.value != pos || _selectedName.value != null) return@launch
            try {
                val name = if (useAmap()) amap.reverse(pos) else nominatim.reverse(pos)
                if (_selected.value == pos && _selectedName.value == null) _selectedName.value = name
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Coordinates remain fully usable without an address. */ }
        }
    }
    fun setSelectedName(name: String) { _selectedName.value = name.trim().takeIf { it.isNotEmpty() } }
    fun search(query: String) {
        if (query.isBlank()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _searchResults.value = emptyList()
            try {
                val prefs = settingsRepo.settings.first()
                val found = if (useAmap(prefs)) amap.search(query.trim(), selected.value) else nominatim.search(query.trim())
                if (generation != searchGeneration) return@launch
                _searchResults.value = found
                if (_searchResults.value.isEmpty()) postMessage("没有找到地点，可直接输入经纬度")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == searchGeneration) postMessage("搜索暂时不可用：${error.message}。仍可在地图选点或输入坐标") }
            finally { if (generation == searchGeneration) _searching.value = false }
        }
    }
    fun addFavorite(name: String, pos: LatLng) = work("已加入收藏") { repo.addFavorite(name.trim().ifBlank { "未命名位置" }, pos, settings.value.altitudeM) }
    fun updateFavorite(id: Long, name: String, pos: LatLng, altitude: Double?) = work("收藏已更新") { repo.updateFavorite(id, name, pos, altitude) }
    fun selectFavorite(favorite: Favorite) = work {
        select(LatLng(favorite.lat, favorite.lng))
        setSelectedName(favorite.name)
        favorite.altitude?.let { settingsRepo.setAltitude(it) }
        repo.addHistory(LatLng(favorite.lat, favorite.lng), HistorySource.FAVORITE, favorite.name)
    }
    fun removeFavorite(id: Long) = work { repo.removeFavorite(id) }
    fun addHistory(pos: LatLng, source: HistorySource) = work { repo.addHistory(pos, source, selectedName.value) }
    fun clearHistory() = work("历史记录已清空") { repo.clearHistory() }
    fun deleteHistory(id: Long) = work { repo.deleteHistory(id) }

    private suspend fun prepareStart(): AppSettings? {
        if (_savingRootPath.value) { postMessage("正在保存 su 路径，请稍后开始"); return null }
        (appContext.applicationContext as com.geostep.app.GeostepApp).ready.await()
        val prefs = settingsRepo.settings.first()
        controller.updateSettings(prefs)
        val checks = engineChecks(prefs)
        _checks.value = checks
        val blockers = checks.filter { !it.ok && it.name in setOf("定位权限", "模拟位置 AppOps", "LSPosed 模块安装", "Root 独立注入", "Root 权限", "系统控制通道", "系统定位钩子", "模块版本") }
        if (blockers.isNotEmpty()) { postMessage(blockers.joinToString("；") { it.message ?: it.name }); return null }
        if (router.currentEngineId != prefs.engineId) router.switch(prefs.engineId).getOrThrow()
        return prefs
    }

    private suspend fun engineChecks(prefs: AppSettings): List<CheckResult> = withContext(Dispatchers.IO) {
        PermissionChecker.run(appContext, prefs.engineId == "provider") +
            if (prefs.engineId in setOf("xposed", "root")) router.selfCheck(prefs.engineId) else emptyList()
    }

    /** 三种工作模式各自跑一遍自检，供模式选择器显示“是否真的可用”。 */
    fun refreshModeChecks() {
        work {
            _modeChecks.value = MODE_IDS.associateWith { modeChecks(it) }
        }
    }

    private suspend fun modeChecks(engineId: String): List<CheckResult> = withContext(Dispatchers.IO) {
        PermissionChecker.run(appContext, engineId == "provider") +
            if (engineId == "provider") emptyList() else router.selfCheck(engineId)
    }

    fun modeLabel(engineId: String): String = modeLabelOf(engineId)

    /**
     * 切换工作模式：先按该模式跑一遍自检，不通过就不切换并给出可操作原因。
     * 这样“选了 Root 但没授权”“选了 LSPosed 但模块未安装/未勾选作用域”不会再静默失败。
     */
    fun selectMode(engineId: String) = work {
        check(sim.value.engineState == EngineState.Idle) { "请先停止模拟再切换工作模式" }
        if (engineId == settings.value.engineId) return@work
        val checks = modeChecks(engineId)
        _modeChecks.value = _modeChecks.value + (engineId to checks)
        val reason = blockedReasonOf(engineId, checks)
        if (reason != null) {
            postMessage(reason)
            return@work
        }
        router.switch(engineId).getOrThrow()
        settingsRepo.setEngine(engineId)
        refreshChecks()
        postMessage("已切换到${modeLabel(engineId)}")
    }

    /** 立即回读校验一次：坐标与步频是否真的注入生效。 */
    fun verifyInjection() = work {
        if (_verifyingInjection.value) return@work
        _verifyingInjection.value = true
        try {
            val results = readback()
            _injectionChecks.value = results
            val failed = results.filter { !it.ok }
            postMessage(
                if (failed.isEmpty()) "校验通过：" + results.joinToString("；") { it.message ?: it.name }
                else "校验未通过：" + failed.joinToString("；") { it.message ?: it.name },
            )
        } finally {
            _verifyingInjection.value = false
        }
    }

    /**
     * Root 直连只接管系统定位；要让某个 App 的传感器读到模拟步频，需要把载荷注入它的进程。
     */
    fun injectRootSensors(packageName: String) = work { rootInjector.inject(packageName) }

    /**
     * 开始模拟时给记住过的目标应用补一次注入：应用重启后载荷会丢，这一步让「开箱即用」。
     */
    fun reInjectRememberedPackages(packages: Set<String>) = work { rootInjector.reInject(packages) }

    fun forgetRootPackage(packageName: String) = work { rootInjector.forget(packageName) }

    private suspend fun engineIdOf(): String = settingsRepo.settings.first().engineId

    private suspend fun readback(): List<CheckResult> {
        val cfg = (sim.value.engineState as? EngineState.Running)?.cfg
        return withContext(Dispatchers.IO) {
            if (cfg != null) {
                router.verifyApplied(cfg)
            } else {
                val prefs = settingsRepo.settings.first()
                modeChecks(prefs.engineId)
            }
        }
    }

    /**
     * 运行中周期回读：模块/系统是否一直按我们的配置在注入。
     * 失败只提示一次，避免每 10 秒刷一条消息；状态卡片上会持续显示当前结论。
     */
    private suspend fun watchInjection() {
        var warned = false
        sim.map { it.engineState is EngineState.Running }.distinctUntilChanged().collectLatest { runningNow ->
            if (!runningNow) {
                warned = false
                _injectionChecks.value = emptyList()
                return@collectLatest
            }
            delay(2_500)
            if (engineIdOf() == "root") {
                val remembered = runCatching { settingsRepo.settings.first().rootInjectedPackages }
                    .getOrDefault(emptySet())
                // 先登记目标包，再补注入：引擎会在运行期间持续扫描这些包的新进程。
                withContext(Dispatchers.IO) { rootInjector.startSession(remembered) }
            }
            while (true) {
                val running = sim.value.engineState as? EngineState.Running ?: break
                val results = runCatching { withContext(Dispatchers.IO) { router.verifyApplied(running.cfg) } }.getOrNull()
                if (results != null) {
                    _injectionChecks.value = results
                    val failed = results.filter { !it.ok }
                    if (failed.isNotEmpty() && !warned) {
                        warned = true
                        postMessage("注入校验未通过：" + failed.joinToString("；") { it.message ?: it.name })
                    }
                }
                delay(10_000)
            }
        }
    }
    fun startFixed(context: Context) = work {
        val pos = selected.value ?: error("请先在地图上选点或输入坐标")
        val generation = selectionGeneration
        prepareStart() ?: return@work
        check(generation == selectionGeneration) { "选点已更改，请重新开始" }
        SimulationService.startFixed(context, pos)
        repo.addHistory(pos, HistorySource.MANUAL, selectedName.value)
    }
    fun startFixedAt(context: Context, pos: LatLng, name: String?, source: HistorySource) = work {
        select(pos)
        name?.let(::setSelectedName)
        prepareStart() ?: return@work
        SimulationService.startFixed(context, pos)
        repo.addHistory(pos, source, name)
    }
    fun stopSim(context: Context) = work { SimulationService.stop(context) }
    fun pauseSim() = work { SimulationService.pause(appContext) }
    fun resumeSim() = work { SimulationService.resume(appContext) }
    fun startJoystick(context: Context) = work {
        val origin = (if (sim.value.engineState is EngineState.Running) sim.value.currentPosition else selected.value)
            ?: selected.value ?: error("请先选择摇杆起点")
        if (!Settings.canDrawOverlays(context)) { postMessage("请先在权限检查中开启悬浮窗权限"); return@work }
        val prefs = prepareStart() ?: return@work
        SimulationService.startJoystick(context, origin, prefs.routeSpeedMps)
    }

    private fun cancelPlanning() {
        planningGeneration++
        planningJob?.cancel()
        planningJob = null
        _planning.value = false
    }
    private fun replaceDraft(points: List<LatLng>, details: List<RoutePoint> = emptyList(),
                             controls: List<LatLng>? = null, cancelInFlight: Boolean = true) {
        if (cancelInFlight) cancelPlanning()
        val previousControls = _routeControls.value
        val previousDetails = controlDetails
        draftRevision++
        _routeDraft.value = points
        detailedDraft = details.takeIf { it.size == points.size } ?: points.mapIndexed { i, p -> RoutePoint(seq = i, lat = p.lat, lng = p.lng) }
        _routeControls.value = controls ?: points.takeIf { it.size <= 100 }.orEmpty()
        controlDetails = when {
            _routeControls.value == points -> detailedDraft
            _routeControls.value == previousControls -> previousDetails
            else -> _routeControls.value.mapIndexed { i, p -> RoutePoint(seq = i, lat = p.lat, lng = p.lng) }
        }
        _routePlanned.value = _routeControls.value.isNotEmpty() && _routeControls.value != points
    }
    fun addRoutePoint(pos: LatLng) {
        if (_routeControls.value.isEmpty() && _routeDraft.value.isNotEmpty()) { postMessage("当前为导入轨迹。请保存后清空，再创建途经点路线"); return }
        if (_routeControls.value.size >= 100) { postMessage("最多可编辑 100 个途经点"); return }
        if (_routeControls.value.lastOrNull() == pos) { postMessage("请为下一个途经点选择不同位置"); return }
        val details = controlDetails + RoutePoint(seq = controlDetails.size, lat = pos.lat, lng = pos.lng)
        replaceDraft(_routeControls.value + pos, details)
    }
    fun removeLastRoutePoint() { if (controlDetails.isNotEmpty()) removeRoutePoint(controlDetails.lastIndex) }
    fun removeRoutePoint(index: Int) {
        if (index !in controlDetails.indices) return
        val details = EditableRoute.removeAt(controlDetails, index)
        replaceDraft(details.map { LatLng(it.lat, it.lng) }, details)
    }
    fun moveRoutePoint(index: Int, pos: LatLng) {
        if (index !in controlDetails.indices) return
        val details = EditableRoute.movePoint(controlDetails, index, pos)
        replaceDraft(details.map { LatLng(it.lat, it.lng) }, details)
    }
    fun reverseRouteDraft() {
        val wasPlanned = _routePlanned.value
        val wasTrack = controlDetails.isEmpty()
        val details = EditableRoute.reverse(if (wasTrack) detailedDraft else controlDetails)
        replaceDraft(details.map { LatLng(it.lat, it.lng) }, details, if (wasTrack) emptyList() else null)
        if (wasPlanned) postMessage("途经点已反向，请重新规划以遵循道路方向")
    }
    fun setRouteProfile(id: String) {
        if (id !in listOf("drive", "walk", "ride") || _routeProfile.value == id) return
        cancelPlanning()
        _routeProfile.value = id
        if (_routePlanned.value) replaceDraft(_routeControls.value, controlDetails)
        else draftRevision++
    }
    fun clearRouteDraft() { replaceDraft(emptyList()) }
    fun planOnline() {
        val points = routeControls.value
        if (points.size !in 2..100) { postMessage("在线规划需要 2–100 个途经点；已导入轨迹可直接模拟"); return }
        if (planning.value) return
        val revision = draftRevision
        val generation = ++planningGeneration
        val profile = _routeProfile.value
        _planning.value = true
        planningJob = viewModelScope.launch {
            try {
                val prefs = settingsRepo.settings.first()
                val result = if (useAmap(prefs)) amap.route(points, profile) else {
                    check(profile == "drive") { "开放地图当前支持驾车规划，步行与骑行请切换高德地图" }
                    osrm.route(*points.toTypedArray()).getOrThrow()
                }
                if (revision != draftRevision || generation != planningGeneration) return@launch
                replaceDraft(result.points, controls = points, cancelInFlight = false)
                postMessage("路线已规划，共 %.1f 公里".format(result.distanceM / 1000))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { postMessage(error.message ?: "路线规划失败，请重试")
            } finally { if (generation == planningGeneration) _planning.value = false }
        }
    }
    fun saveRoute(name: String, mode: RouteMode) = work("路线已保存") {
        require(routeDraft.value.size >= 2) { "路线至少需要两个点" }
        repo.saveDetailedRoute(name.trim().ifBlank { "路线 ${routes.value.size + 1}" }, detailedDraft, mode, routeControls.value, routeProfile.value)
    }
    fun loadRoute(route: Route) {
        _routeProfile.value = route.profile
        replaceDraft(route.points.map { LatLng(it.lat, it.lng) }, route.points, route.controlPoints)
        routeDraft.value.firstOrNull()?.let { select(it); setSelectedName(route.name) }
    }
    fun renameRoute(route: Route, name: String) = work("路线已重命名") { repo.renameRoute(route.id, name.trim()) }
    fun deleteRoute(id: Long) = work { repo.deleteRoute(id) }
    fun startRouteSim(context: Context, mode: RouteMode) = work {
        val points = routeDraft.value.toList()
        val details = detailedDraft.toList()
        val revision = draftRevision
        require(points.size >= 2) { "路线至少需要两个点" }
        val prefs = prepareStart() ?: return@work
        check(revision == draftRevision) { "路线已更改，请重新开始" }
        SimulationService.startRoute(context, "当前路线", points, mode, prefs.routeSpeedMps, details)
    }
    fun startSavedRoute(context: Context, route: Route) = work {
        val prefs = prepareStart() ?: return@work
        SimulationService.startRoute(context, route.name, route.points.map { LatLng(it.lat, it.lng) }, route.mode, prefs.routeSpeedMps, route.points)
    }

    fun setEngine(id: String) = selectMode(id)
    fun setRootSuPath(path: String, mode: com.geostep.core.model.RootSuMode) {
        if (sim.value.engineState != EngineState.Idle || _checkingRoot.value || _savingRootPath.value) {
            postMessage("请先停止模拟并等待授权检测结束")
            return
        }
        _savingRootPath.value = true
        work("su 路径已保存") {
            try {
                settingsRepo.setRootSuPath(path, mode)
                _rootAccessCheck.value = null
                refreshChecks()
            } finally { _savingRootPath.value = false }
        }
    }
    fun checkRootAccess() {
        if (_checkingRoot.value || _savingRootPath.value) return
        if (sim.value.engineState != EngineState.Idle) { postMessage("请先停止模拟再检测授权"); return }
        _checkingRoot.value = true
        _rootAccessCheck.value = null
        work {
            try {
                val prefs = settingsRepo.settings.first()
                _rootAccessCheck.value = RootAccess.check(prefs.rootSuPath, prefs.rootSuMode)
            }
            finally { _checkingRoot.value = false }
        }
    }
    fun setInterval(ms: Long) = work { settingsRepo.setInterval(ParameterLimits.interval(ms)) }
    fun setAccuracy(m: Double) = work { settingsRepo.setAccuracy(ParameterLimits.accuracy(m)) }
    fun setJitter(enabled: Boolean, meters: Double) = work { settingsRepo.setJitter(enabled, ParameterLimits.jitter(meters)) }
    fun setRouteSpeed(mps: Double) = work { settingsRepo.setRouteSpeed(ParameterLimits.routeSpeed(mps)) }
    fun setAltitude(m: Double) = work { settingsRepo.setAltitude(ParameterLimits.altitude(m)) }
    fun setSatellites(n: Int) = work { settingsRepo.setSatellites(ParameterLimits.satellites(n)) }
    /**
     * 步频（步/分）。0 = 按速度自动推算；其余按固定步频注入 STEP_COUNTER/DETECTOR。
     * 上界与 core:model 的 SimConfig.MAX_STEP_CADENCE_SPM 一致。
     */
    fun setStepCadence(spm: Int) = work {
        settingsRepo.setStepCadence(ParameterLimits.stepCadence(spm))
    }
    /** 步频浮动幅度（百分比）：0 = 节拍器式恒定，1..30 = 平均步频附近的自然起伏。 */
    fun setStepCadenceVariability(percent: Int) = work {
        settingsRepo.setStepCadenceVariability(ParameterLimits.cadenceVariability(percent))
    }
    /** 步频浮动周期（秒）：一个起伏回合的时长。 */
    fun setStepCadencePeriod(seconds: Int) = work {
        settingsRepo.setStepCadencePeriod(
            ParameterLimits.cadencePeriod(seconds),
        )
    }
    fun setNetworkProviderToo(too: Boolean) = work { settingsRepo.setNetworkProviderToo(too) }
    fun setSineCurve(on: Boolean) = work { settingsRepo.setSineCurve(on) }
    fun resetParametersToDefault() = work("已恢复默认模拟参数") {
        settingsRepo.setRouteSpeed(1.4)
        settingsRepo.setSineCurve(true)
        settingsRepo.setAccuracy(3.0)
        settingsRepo.setAltitude(0.0)
        settingsRepo.setSatellites(12)
        settingsRepo.setStepCadence(SimConfig.STEP_CADENCE_AUTO)
        settingsRepo.setStepCadenceVariability(SimConfig.DEFAULT_STEP_CADENCE_VARIABILITY)
        settingsRepo.setStepCadencePeriod(SimConfig.DEFAULT_STEP_CADENCE_PERIOD_SEC)
        settingsRepo.setInterval(1000L)
        settingsRepo.setNetworkProviderToo(false)
        settingsRepo.setJitter(false, 0.0)
    }
    fun setHistoryLimit(n: Int) = work { settingsRepo.setHistoryLimit(ParameterLimits.history(n)) }
    fun setLowPowerMode(enabled: Boolean) = work { settingsRepo.setLowPowerMode(enabled) }

    fun setScreenKeepOn(on: Boolean) = work { settingsRepo.setScreenKeepOn(on) }
    fun setTheme(theme: String) = work { settingsRepo.setTheme(theme) }
    fun setMapProvider(id: String) = work { settingsRepo.setMapProvider(id) }
    fun setAmapConsent(agreed: Boolean) = work {
        if (!agreed) { searchGeneration++; searchJob?.cancel(); addressJob?.cancel(); planningJob?.cancel(); _searching.value = false }
        settingsRepo.setAmapConsent(agreed)
        if (!agreed) {
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(appContext, false)
            com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(appContext, false)
        }
    }
    fun setMapSatellite(on: Boolean) = work { settingsRepo.setMapSatellite(on) }
    fun setCompatibilityMode(on: Boolean) = work {
        check(sim.value.engineState == EngineState.Idle) { "请先停止模拟再切换无痕模式" }
        settingsRepo.setCompatibilityMode(on)
    }
    fun consumeMessage() { _message.value = null }
    fun postMessage(msg: String) { _message.value = msg }
    fun diagnostics(): List<String> = buildList {
        add("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
        add("允许忽略电池优化：${appContext.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(appContext.packageName)}")
        router.moduleSnapshot()?.let { status ->
            add("系统服务 v${status.moduleVersion} · 状态=${if (status.enabled) "模拟中" else "待机"}")
            if (status.hookDetail.isNotBlank()) {
                add("系统钩子：${status.hookDetail}")
                add("最近回执心跳剩余 ${status.leaseRemainingMs} ms · 本次系统进程心跳过期 ${status.leaseExpirations} 次")
                add("回调改写 ${status.rewrittenCallbacks} 次 · 未匹配订阅的回调 ${status.unmatchedCallbacks} 次（含非模拟接收方，需结合探针判断）")
            }
            add("运行模式：${if (status.reportMock) "普通模式" else "深度无痕模式（隐藏模拟标记）"}")
            add("定位响应统计：缓存定位 ${status.lastCalls} 次 · 实时定位 ${status.currentCalls} 次 · 连续导航 ${status.listenerCalls} 次 · 位置推送 ${status.pushes} 次")
        }
        addAll(controller.recentLogs().takeLast(100).map { "[${it.tag}] ${it.message}${it.error?.let { e -> " ($e)" } ?: ""}" })
    }

    @SuppressLint("MissingPermission")
    fun locateRealPosition() {
        if (locatingJob?.isActive == true) return
        if (sim.value.engineState != EngineState.Idle) { postMessage("请先停止模拟，再获取真实位置"); return }
        val generation = selectionGeneration
        locatingJob = viewModelScope.launch {
            try {
                val manager = appContext.getSystemService(LocationManager::class.java)
                @Suppress("DEPRECATION")
                val last = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                    .filter { !it.isFromMockProvider && android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 300_000_000_000L }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                if (last != null) {
                    if (generation == selectionGeneration && sim.value.engineState == EngineState.Idle) {
                        select(LatLng(last.latitude, last.longitude)); setSelectedName("当前位置")
                    }
                    return@launch
                }
                postMessage("正在获取真实位置…")
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                check(providers.isNotEmpty()) { "请开启系统定位服务" }
                val location = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<Location> { continuation ->
                        val listener = object : LocationListener {
                            @Suppress("DEPRECATION")
                            override fun onLocationChanged(location: Location) {
                                if (!location.isFromMockProvider && continuation.isActive) {
                                    manager.removeUpdates(this)
                                    continuation.resume(location)
                                }
                            }
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                        }
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                        var registered = false
                        var registrationError: Exception? = null
                        providers.forEach { provider ->
                            try {
                                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                                registered = true
                            } catch (error: Exception) { registrationError = error }
                        }
                        if (!registered && continuation.isActive) continuation.resumeWith(Result.failure(
                            registrationError ?: IllegalStateException("无法订阅系统定位")))
                        if (!continuation.isActive) manager.removeUpdates(listener)
                    }
                } ?: error("暂时无法获取真实位置，请到室外重试或手动选点")
                if (generation == selectionGeneration && sim.value.engineState == EngineState.Idle) {
                    select(LatLng(location.latitude, location.longitude)); setSelectedName("当前位置")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { postMessage(error.message ?: "定位失败，请检查权限") }
        }
    }

    private suspend fun readFile(uri: Uri): String = GpxTransfer.readText(appContext, uri)
    private fun writeFile(uri: Uri, text: String, success: String = "导出完成"): String? {
        return writeGeneratedFile(uri, success) { text }
    }
    private fun writeGeneratedFile(uri: Uri, success: String = "导出完成", generate: () -> String): String? {
        work(success) {
            val text = withContext(Dispatchers.Default) { generate() }
            GpxTransfer.writeText(appContext, uri, text)
        }
        return null
    }
    fun importGpx(uri: Uri): String? {
        val revision = draftRevision
        work {
            val track = withContext(Dispatchers.Default) { Gpx.parse(readFile(uri)) } ?: error("无法解析 GPX，请检查轨迹文件")
            require(track.points.size >= 2) { "GPX 路线至少需要两个点" }
            check(revision == draftRevision) { "草稿已更改，请重新导入" }
            replaceDraft(track.points.map { LatLng(it.lat, it.lng) }, track.points, emptyList())
            select(routeDraft.value.first()); setSelectedName(track.name ?: "导入路线")
            postMessage("已导入 ${track.points.size} 个点，保留海拔和分段速度")
        }
        return null
    }
    fun exportGpx(uri: Uri, name: String = "Geostep 路线"): String? {
        if (detailedDraft.isEmpty()) return "路线为空"
        val points = detailedDraft.toList()
        return writeGeneratedFile(uri) { Gpx.export(name, points) }
    }
    fun exportFavorites(uri: Uri): String? {
        val points = favorites.value.mapIndexed { i, p -> RoutePoint(seq = i, lat = p.lat, lng = p.lng, altitude = p.altitude) }
        return writeGeneratedFile(uri) { GpxTransfer.routeGpx("收藏位置", points) }
    }
    fun exportHistory(uri: Uri): String? {
        val points = history.value.reversed().mapIndexed { i, p -> RoutePoint(seq = i, lat = p.lat, lng = p.lng) }
        return writeGeneratedFile(uri) { GpxTransfer.routeGpx("历史位置", points) }
    }
    /** Used by both settings and runtime status; no settings/route serialization. */
    fun diagnosticReportText(): String = DiagnosticsReportBuilder.build(
        context = appContext,
        ui = sim.value,
        settings = settings.value,
        engineId = router.currentEngineId,
        status = router.moduleSnapshot(),
        rootAccess = _rootAccessCheck.value,
        injectionChecks = _injectionChecks.value,
        checks = checks.value,
        logs = controller.recentLogs(),
        secrets = listOf(BuildConfig.AMAP_API_KEY, AmapKeyStore.getCustomApiKey(appContext), settings.value.rootSuPath),
    )

    private fun diagnosticAction(action: suspend (String) -> Unit) {
        if (_diagnosticBusy.value) return
        _diagnosticBusy.value = true
        work {
            try { action(withContext(Dispatchers.IO) { diagnosticReportText() }) }
            finally { _diagnosticBusy.value = false }
        }
    }

    fun exportDiagnostics(uri: Uri): String? {
        diagnosticAction { report ->
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(report) } ?: error("无法写入所选文件")
            }
            postMessage("诊断日志已保存，可作为邮件附件发送")
        }
        return null
    }

    fun sendDiagnosticFeedback() = diagnosticAction { report ->
        val uri = withContext(Dispatchers.IO) { DiagnosticFiles.create(appContext, report) }
        val chooser = SupportContact.attachmentChooser(appContext, uri)
            ?: error("未找到可发送附件的邮件应用。请先保存日志，再发送至 ${SupportContact.EMAIL}")
        appContext.startActivity(chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun exportSettings(uri: Uri): String? = writeFile(uri, settingsRepo.exportJson(settings.value))
    fun importSettings(uri: Uri): String? {
        work("设置已恢复") {
            check(sim.value.engineState == EngineState.Idle) { "请先停止模拟再恢复设置" }
            val json = readFile(uri)
            check(sim.value.engineState == EngineState.Idle) { "模拟已启动，请停止后再恢复设置" }
            settingsRepo.importJson(json)
        }
        return null
    }

    companion object {
        /** 工作模式：标准（系统模拟位置）/ LSPosed（框架 Hook）/ Root（su 载荷）。 */
        val MODE_IDS = listOf("provider", "xposed", "root")
    }
}
