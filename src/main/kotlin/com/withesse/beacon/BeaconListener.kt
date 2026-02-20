package com.withesse.beacon

/**
 * SDK event callback interface.
 * Host app can selectively override methods for reporting, alerting, etc.
 *
 * SDK 事件回调接口。
 * 宿主 App 可选择性实现感兴趣的方法，用于上报、弹窗等。
 *
 * Usage | 用法:
 *   Beacon.addListener(object : BeaconListener {
 *       override fun onCrash(type: String, logPath: String?) { ... }
 *       override fun onLowMemory(heapUsedMB: Long, heapMaxMB: Long, ratio: Float) { ... }
 *   })
 */
interface BeaconListener {

    /** Java/Native crash or ANR | Java/Native 崩溃或 ANR */
    fun onCrash(type: String, logPath: String?) {}

    /** Memory usage exceeds warning threshold | 内存超过告警阈值 */
    fun onLowMemory(heapUsedMB: Long, heapMaxMB: Long, ratio: Float) {}

    /** FPS drops below warning threshold | 帧率低于告警阈值 */
    fun onLowFps(fps: Int, maxFps: Int, droppedFrames: Int) {}

    /** App foreground/background change | App 前后台切换 */
    fun onAppForegroundChanged(foreground: Boolean, foregroundDurationMs: Long) {}
}
