package com.mockgeo.root

import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 兼容层：仓库内置的预编译 `libmockgeo_root.so` 仍按旧包名 `com.mockgeo.root` 查找入口类。
 *
 * Project 改名后真正的实现位于 [com.geostep.root.PayloadBootstrap] / [com.geostep.root.NativeHooks]，
 * 这里只做转发，保证旧原生件与新载荷可以继续配对使用。重新编译原生库后可以删除本文件。
 */
object NativeHooks {
    @JvmStatic external fun initialize(): Boolean
    @JvmStatic external fun hook(target: Executable, context: Any, callback: Method): Method?
    @JvmStatic external fun deoptimize(target: Executable): Boolean
}

object PayloadBootstrap {
    @JvmStatic
    fun start(mode: String, nonce: String, directory: String): Int =
        com.geostep.root.PayloadBootstrap.start(mode, nonce, directory)
}
