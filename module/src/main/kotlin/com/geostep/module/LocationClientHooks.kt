package com.geostep.module

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * 客户端进程内的 LocationManager 钩子。
 *
 * Root 直连模式默认只接管 system_server 的定位，目标 App 自己通过 LocationManager 拿到的是真实定位。
 * 这里在 App 进程内接管三条路径：
 *  1. getLastKnownLocation / getCurrentLocation 直接返回模拟位置；
 *  2. system 把真实定位投递给 App 之前，从 LocationManager 内部的 Transport 换成模拟位置（首选）；
 *  3. 若该 ROM 上拿不到 Transport，则在注册监听后按 1 秒节奏补投模拟位置（兜底）。
 */
internal object LocationClientHooks {

    private const val TICK_MS = 1_000L

    /** 不同 Android 版本里承载回调的传输类（都是 @hide，只能按名字加载）。 */
    private val TRANSPORT_CLASSES = listOf(
        "android.location.LocationManager\$LocationListenerTransport",
        "android.location.LocationManager\$ListenerTransport",
    )

    fun install(api: XposedInterface, loader: ClassLoader) {
        val manager = runCatching { Class.forName("android.location.LocationManager", false, loader) }.getOrNull()
            ?: return
        val main = Handler(Looper.getMainLooper())
        val tickers = Collections.synchronizedMap(WeakHashMap<LocationListener, Runnable>())

        manager.declaredMethods.filter { it.name == "getLastKnownLocation" }.forEach { method ->
            runCatching {
                api.hook(method).intercept(XposedInterface.Hooker { chain ->
                    val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                    if (!AppScopeHooks.isActive(state)) return@Hooker chain.proceed()
                    FakeLocationFactory.create(providerOf(chain.args), state)
                })
            }.onFailure { ModuleLog.w("last-known-location hook failed", it) }
        }

        manager.declaredMethods.filter { it.name == "getCurrentLocation" }.forEach { method ->
            runCatching {
                api.hook(method).intercept(XposedInterface.Hooker { chain ->
                    val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                    if (!AppScopeHooks.isActive(state)) return@Hooker chain.proceed()
                    val fake = FakeLocationFactory.create(providerOf(chain.args), state)
                    val consumer = chain.args.filterIsInstance<Consumer<*>>().firstOrNull()
                    if (consumer == null) return@Hooker chain.proceed()
                    val executor = chain.args.filterIsInstance<Executor>().firstOrNull()
                    val deliver = Runnable {
                        @Suppress("UNCHECKED_CAST")
                        (consumer as Consumer<Location>).accept(fake)
                    }
                    (executor ?: Executor { command -> main.post(command) }).execute(deliver)
                    null
                })
            }.onFailure { ModuleLog.w("current-location hook failed", it) }
        }

        val transports = installTransportHooks(api, loader)
        if (transports == 0) ModuleLog.w("no location transport hooks; falling back to periodic injection")

        manager.declaredMethods.filter { it.name == "requestLocationUpdates" }.forEach { method ->
            val index = method.parameterTypes.indexOfFirst { LocationListener::class.java.isAssignableFrom(it) }
            if (index < 0) return@forEach
            runCatching {
                api.hook(method).intercept(XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    if (transports == 0) {
                        val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                        val listener = chain.args.getOrNull(index) as? LocationListener
                        if (listener != null && AppScopeHooks.isActive(state)) {
                            startTicker(main, tickers, listener, providerOf(chain.args))
                        }
                    }
                    result
                })
            }.onFailure { ModuleLog.w("request-location-updates hook failed", it) }
        }

        manager.declaredMethods.filter { it.name == "removeUpdates" }.forEach { method ->
            val index = method.parameterTypes.indexOfFirst { LocationListener::class.java.isAssignableFrom(it) }
            if (index < 0) return@forEach
            runCatching {
                api.hook(method).intercept(XposedInterface.Hooker { chain ->
                    val listener = chain.args.getOrNull(index) as? LocationListener
                    val result = chain.proceed()
                    if (listener != null) stopTicker(tickers, listener)
                    result
                })
            }.onFailure { ModuleLog.w("remove-updates hook failed", it) }
        }

        manager.declaredMethods.filter { it.name == "requestSingleUpdate" }.forEach { method ->
            val index = method.parameterTypes.indexOfFirst { LocationListener::class.java.isAssignableFrom(it) }
            if (index < 0) return@forEach
            runCatching {
                api.hook(method).intercept(XposedInterface.Hooker { chain ->
                    val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                    val listener = chain.args.getOrNull(index) as? LocationListener
                    if (listener != null && AppScopeHooks.isActive(state)) {
                        val fake = FakeLocationFactory.create(providerOf(chain.args), state)
                        main.post { runCatching { listener.onLocationChanged(fake) } }
                    }
                    chain.proceed()
                })
            }.onFailure { ModuleLog.w("request-single-update hook failed", it) }
        }

        ModuleLog.i(
            "location client hooks installed: transports=$transports " +
                "last-known=" + manager.declaredMethods.count { it.name == "getLastKnownLocation" } +
                " current=" + manager.declaredMethods.count { it.name == "getCurrentLocation" } +
                " updates=" + manager.declaredMethods.count { it.name == "requestLocationUpdates" },
        )
    }

    /**
     * 真实定位在投递给 App 之前都会经过 Transport 对象；这里把参数换成模拟位置，
     * 下游（App 的 listener）自然只看到模拟值，也不会出现"真实定位后到覆盖"的问题。
     */
    private fun installTransportHooks(api: XposedInterface, loader: ClassLoader): Int {
        var installed = 0
        TRANSPORT_CLASSES.forEach { name ->
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull()
            if (type == null) {
                ModuleLog.i("transport class missing: $name")
                return@forEach
            }
            val methods = buildList {
                var cursor: Class<*>? = type
                while (cursor != null) {
                    addAll(
                        cursor.declaredMethods.filter {
                            it.name == "onLocationChanged" && it.parameterTypes.isNotEmpty() &&
                                Location::class.java.isAssignableFrom(it.parameterTypes[0])
                        },
                    )
                    cursor = cursor.superclass
                }
            }
            methods.forEach { method ->
                runCatching {
                    api.hook(method).intercept(XposedInterface.Hooker { chain ->
                        val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                        if (!AppScopeHooks.isActive(state)) return@Hooker chain.proceed()
                        val real = chain.args.firstOrNull() as? Location ?: return@Hooker chain.proceed()
                        val fake = FakeLocationFactory.create(real.provider ?: LocationManager.GPS_PROVIDER, state)
                        chain.proceedWith(fake)
                    })
                    installed += 1
                }.onFailure { ModuleLog.w("transport hook failed: ${method.name}", it) }
            }
            ModuleLog.i("transport $name: ${methods.size} methods")
        }
        return installed
    }

    private fun providerOf(args: List<Any?>): String =
        (args.firstOrNull() as? String)?.takeIf { it.isNotBlank() } ?: LocationManager.GPS_PROVIDER

    private fun startTicker(
        main: Handler,
        tickers: MutableMap<LocationListener, Runnable>,
        listener: LocationListener,
        provider: String,
    ) {
        stopTicker(tickers, listener)
        val ticker = object : Runnable {
            override fun run() {
                if (tickers[listener] !== this) return
                val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                if (!AppScopeHooks.isActive(state)) {
                    tickers.remove(listener)
                    return
                }
                runCatching { listener.onLocationChanged(FakeLocationFactory.create(provider, state)) }
                    .onFailure { ModuleLog.d("listener rejected fake location") }
                main.postDelayed(this, TICK_MS)
            }
        }
        tickers[listener] = ticker
        main.postDelayed(ticker, 0L)
    }

    private fun stopTicker(tickers: MutableMap<LocationListener, Runnable>, listener: LocationListener) {
        tickers.remove(listener)?.let { ticker ->
            runCatching { Handler(Looper.getMainLooper()).removeCallbacks(ticker) }
        }
    }
}
