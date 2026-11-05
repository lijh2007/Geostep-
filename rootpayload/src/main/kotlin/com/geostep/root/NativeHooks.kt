package com.geostep.root

import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Registered by the injected native worker in this VM, never via a test-process proxy.
 *
 * 仓库内置的 `libmockgeo_root.so` 是改名前的预编译件，它把 JNI 方法注册在旧包名
 * [com.mockgeo.root.NativeHooks] 上，因此这里转发到旧包名的同名声明（见 Compat.kt）。
 * 重新编译原生库后应改回直接声明 `external`。
 */
object NativeHooks {
    @JvmStatic fun initialize(): Boolean = com.mockgeo.root.NativeHooks.initialize()

    @JvmStatic fun hook(target: Executable, context: Any, callback: Method): Method? =
        com.mockgeo.root.NativeHooks.hook(target, context, callback)

    @JvmStatic fun deoptimize(target: Executable): Boolean =
        com.mockgeo.root.NativeHooks.deoptimize(target)
}
