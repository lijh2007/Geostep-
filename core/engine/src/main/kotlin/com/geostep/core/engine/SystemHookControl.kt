package com.geostep.core.engine

import com.geostep.core.model.CheckResult
import com.geostep.core.model.InjectionEngine
import com.geostep.core.model.SimConfig

interface SystemHookControl : InjectionEngine {
    suspend fun pingModule(): Boolean
    fun statusSnapshot(): XposedEngine.ModuleStatus?

    /**
     * 回读校验：确认模块真的把坐标与步频注入进了系统。
     * 默认实现按当前快照比对；需要区分“回执过期”的实现可以覆盖。
     */
    suspend fun verifyApplied(cfg: SimConfig): List<CheckResult> =
        InjectionVerifier.verify(cfg, statusSnapshot())
}

data class RootReceipt(val pid: Int, val nonce: String, val version: String) {
    fun matches(status: XposedEngine.ModuleStatus?): Boolean = pid > 1 && version.isNotBlank() && status != null &&
        status.backend == "root" && status.hooksInstalled && status.processId == pid &&
        status.instanceNonce == nonce && nonce.matches(Regex("[0-9a-f]{32}")) &&
        status.moduleVersion == version
}

interface RootEnvironment {
    val version: String
    suspend fun readiness(): List<com.geostep.core.model.CheckResult>
    suspend fun load(): RootReceipt
}
