# Beacon SDK ProGuard Rules | Beacon SDK 混淆规则

# Public API | 公开接口
-keep class com.withesse.beacon.Beacon { public *; }
-keep class com.withesse.beacon.log.BLog { public *; }
-keep class com.withesse.beacon.config.BeaconConfig { *; }
-keep class com.withesse.beacon.config.ConfigStore { public *; }
-keep class com.withesse.beacon.export.LogExporter { public *; }
-keep class com.withesse.beacon.export.DeviceInfoCollector { public *; }
-keep class com.withesse.beacon.apm.StartupTracer { public *; }
-keep class com.withesse.beacon.BeaconListener { *; }
-keep class com.withesse.beacon.apm.BeaconNetworkInterceptor { public *; }

# Dependencies | 第三方依赖
-keep class xcrash.** { *; }
-keep class com.tencent.mars.** { *; }
-keep class com.tencent.mars.xlog.** { *; }
-keep class com.tencent.mmkv.** { *; }
