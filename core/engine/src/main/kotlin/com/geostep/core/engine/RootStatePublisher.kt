package com.geostep.core.engine

import android.os.SystemClock
import com.geostep.core.model.ControlPlane
import com.geostep.core.model.SimConfig
import org.json.JSONObject

/**
 * Root 模式的状态镜像发布器。
 *
 * 正常情况下这些字段由注入 system_server 的载荷写入 `Settings.Global`；但部分 ROM 会拦截
 * remote call（loader 报 "unexpected signal"），此时退化为「注入式 Root 模式」：App 自己用
 * su 把同一份 JSON 写进 `Settings.Global`，注入到目标 App 进程的 [com.geostep.module.AppScopeHooks]
 * 照常读取，位置与步频注入不受影响。
 */
internal object RootStatePublisher {

    fun state(cfg: SimConfig, session: Long, bootCount: Int): String = JSONObject()
        .put("e", 1)
        .put("h", if (cfg.reportMock) 0 else 1)
        .put("lat", cfg.position.lat)
        .put("lng", cfg.position.lng)
        .put("spd", cfg.speedMps.toDouble())
        .put("sat", cfg.satellites)
        .put("ts", System.currentTimeMillis())
        .put("et", SystemClock.elapsedRealtime())
        .put("boot", bootCount)
        .put("session", session)
        .put("alt", cfg.altitude)
        .put("brg", ((cfg.bearingDeg % 360f) + 360f) % 360f)
        .put("cad", cfg.stepCadenceSpm)
        .put("cav", cfg.stepCadenceVariabilityPercent)
        .put("cap", cfg.stepCadencePeriodSec)
        .put("x", "")
        .toString()

    suspend fun publish(shell: SuCommandRunner, cfg: SimConfig, session: Long, bootCount: Int): Result<Unit> =
        runCatching {
            val json = state(cfg, session, bootCount)
            shell.run("settings put global ${ControlPlane.GLOBAL_STATE_KEY} '$json'")
        }

    suspend fun clear(shell: SuCommandRunner): Result<Unit> = runCatching {
        shell.run("settings delete global ${ControlPlane.GLOBAL_STATE_KEY}")
    }
}
