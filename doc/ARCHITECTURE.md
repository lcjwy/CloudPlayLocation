# 架构与核心逻辑说明

> 需求见 `doc/REQUIREMENTS.md`。本文档记录模块划分、依赖关系、核心逻辑与模块归纳，供后续 AI/开发者直接索引，无需重读代码。

## 1. 模块依赖图

```
                    ┌─────────┐
                    │  :app   │  壳：LocationApplication / MainActivity / NavHost / di/AppModule(Koin)
                    └────┬────┘
        ┌──────────┬─────┼──────────┬───────────────┐
        ▼          ▼     ▼          ▼               ▼
  :feature:home :feature:map :feature:settings :service:mock
        │          │              │               │
        │          │ (仅 :map:api)│               │
        │          ▼              │               │
        │      :map:api           │               │
        │       │    │            │
        │   :map:baidu :map:osm   │   ← 具体实现仅由 :app 选择并注入
        ▼          ▼              ▼               ▼
            ┌─────────────────────────────┐
            │ :core:ui  :core:data  :core:common │
            └─────────────────────────────┘
```

- feature 之间互不依赖；`:feature:map` 只依赖 `:map:api`；`:map:baidu`/`:map:osm` 只被 `:app` 依赖。
- Koin DI：模块定义只在 `:app`（`di/AppModule.kt`），core/feature 保持框架无关；Room 用 KSP。
- 静态检查：ktlint（行宽 100，`.editorconfig`）+ detekt（函数 ≤40 语句，`config/detekt/detekt.yml`），root `build.gradle.kts` 统一应用到全模块。

## 2. 核心逻辑

### 2.1 权限与启用链路
```
用户开开关 / 点列表项 / 地图页锁定
  → 位置权限(FINE+COARSE)？ → 否：运行时申请
  → 模拟位置应用已选？(试探 addTestProvider, SecurityException=未选) → 否：跳 ACTION_APPLICATION_DEVELOPMENT_SETTINGS
  → (悬浮窗开启时) canDrawOverlays？ → 否：跳 ACTION_MANAGE_OVERLAY_PERMISSION
  → WiFi 已开启？(Toast 软提醒，不阻断，提示后直接启动)
  → MockLocationManager.start(point)
```
- 悬浮窗长按启停走同一校验链，但权限不足只 Toast 不弹窗（`MockLocationManager.tryToggleFromOverlay`）。
- **WiFi 闪回原理**：系统网络定位服务基于 WiFi 扫描（BSSID）可独立算出真实位置，部分经 FusedLocationProvider 的应用绕过 LocationManager 的 TestProvider，导致位置跳回真实位置。应用侧两层软提醒：① 启动前 WLAN 开启则 Toast 提示（不阻断）；② 设置页常驻「WiFi 闪回防护」区块，实时显示 WLAN 与系统「Wi-Fi 扫描」状态（`MockCheck.isWifiScanAlwaysAvailable`，ON_RESUME 刷新），引导关闭扫描类开关（跳 `android.settings.LOCATION_SCANNING_SETTINGS`，机型缺失回落 `ACTION_LOCATION_SOURCE_SETTINGS`）。**关闭扫描类开关无需断开 WiFi 联网**。

### 2.2 位置注入循环（MockLocationService）
- 注册双 TestProvider：GPS（POWER_USAGE_HIGH/ACCURACY_FINE）+ Network（LOW/COARSE）。**统一走废弃的 10 参 `addTestProvider` 重载 + 常量**（Gogogo 同款，含 API 31+；`ProviderProperties.Builder()` 新重载在部分 ROM 行为不一致）；注册后 `isProviderEnabled` 仍报禁用时（系统位置关闭常见）补一次 `setTestProviderEnabled(true)`。
- `HandlerThread("MockLocation")` 自循环：每 interval（10–100ms，默认 100）先 Network 后 GPS 各 `setTestProviderLocation` 一次。
- **注入容错（系统位置关闭场景）**：`setTestProviderLocation` 抛 `SecurityException`（部分 ROM 在系统位置关闭时）**容忍并继续重试，不 stopSelf**——Gogogo 同款策略，位置开启后注入自动恢复；日志仅记每次故障首次。模拟权限探测（`MockLocationAccess.isGranted`）**先定论后清理**：`addTestProvider` 成功即已授权，清理调用的异常不参与判定，避免被误判为「未选择模拟位置应用」。
- Location 字段：accuracy(GPS=1f/Network=50f)、altitude=55.0、speed=0、bearing=0、time、elapsedRealtimeNanos、extras(satellites=7)。
- 运行中换点/改频率 = 重发 intent（`onStartCommand` 更新并重排循环）；服务无 BIND，状态经 companion `isAlive` 暴露。
- `SecurityException`（模拟位置应用被取消选择）→ 停止注入并自杀。
- 双 provider 原因：部分应用只读 GPS、部分融合 Network，双注入保证一致。

### 2.3 坐标转换（:core:common CoordUtils）
- 全链路内部一律 WGS84；仅百度显示边界做 WGS84↔BD09LL。
- 链条：WGS84↔GCJ02↔BD09（`wgs2bd09`/`bd092wgs` 组合封装），纯数学，移植自 Gogogo MapUtils。
- 缺此转换会偏移数百米（国测局加密）。

### 2.4 地图抽象层
```
MapAdapter（一律 WGS84 出入）：
  view / currentZoom / onCenterChanged
  moveCamera(lat,lng,zoom) / zoomIn / zoomOut
  setMyLocationEnabled(bool) / moveToMyLocation(): Boolean
  onResume / onPause / onDestroy
MapAdapterFactory: (context, MapConfig) -> MapAdapter，由 :app 提供实现选择
```
- `MapConfig.source` 决定实现；未同意隐私 → :app 工厂强制回退 osmdroid。
- 百度懒初始化：`BaiduSdkInitializer.ensureInit(context, privacyAgreed)` 幂等，仅在工厂创建百度 Adapter 时调用，先于 MapView 创建；`setAgreePrivacy(true)` + `initialize` + `setCoordType(BD09LL)`。
- 百度显示 `wgs2bd09`、回调 `bd092wgs`；`programmaticMove` 标志抑制程序化相机移动期间的回调抖动。
- osmdroid：WGS84 直通；`Configuration` 缓存指向应用私有目录（免存储权限）；中心点回调 120ms 防抖。
- UI（Compose）：`AndroidView` 包 MapView + 中心十字准星 Canvas 覆盖层 + 底部坐标面板 + 右侧缩放按钮，两源共用。

### 2.5 悬浮窗手势（FloatingButtonView + FloatingControlService）
- `TYPE_APPLICATION_OVERLAY` + NOT_FOCUSABLE|NOT_TOUCH_MODAL，rawX/rawY 差值拖动 + `updateViewLayout`。
- ACTION_DOWN 起 2s 计时（33ms 进度环刷新）；移动超 touchSlop → 判定拖动并取消长按；2s 计满 → 震动 + `tryToggleFromOverlay()`。
- FloatingControlService 为 specialUse FGS（manifest 声明 PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。

### 2.6 状态管理与 DI
- `MockLocationManager`（:service:mock 进程级单例）：以 `(悬浮窗开关, 运行开关, 注入频率)` 的 DataStore 组合流为唯一事实源，`reconcileOnChange` collect 后统一启停 MockLocationService/FloatingControlService；进程重启校正残留 mockEnabled。UI 与悬浮窗只调 `start(point)`/`stop()`/`tryToggleFromOverlay()`。`start`：运行中重发 intent 换点；服务已死则**直接重启**（服务被系统杀死后 mockEnabled 残留 true，仅写开关会被 distinctUntilChanged 吞掉导致点击无响应）。
- Koin（4.x，仅 :app 触碰）：`di/AppModule.kt` 注册 SettingsRepository / PointRepository / MapAdapterFactory 三个单例；`LocationApplication.startKoin` 后经 `MockLocationManager.init(this, get())` 交接；MainActivity 用 `by inject()` 注入后以参数下传，feature 层保持无框架依赖。
- 启停校验链 UI 下沉为 `:core:ui` 的 `MockStartGate`（`rememberMockStartGate`）：主页与地图页共用「权限申请 → 模拟位置/悬浮窗引导弹窗 + WiFi 闪回 Toast 软提醒」全流程，消除重复。不校验系统 GPS 开关（推荐先启动虚拟位置、再手动开系统定位）。

### 2.7 百度 Key 注入链
```
local.properties(BAIDU_MAP_KEY, 不入库) → :app build.gradle 读取 → manifestPlaceholders[baiduMapKey]
  → app manifest <meta-data com.baidu.lbsapi.API_KEY ${baiduMapKey}>
```
- 未申请 Key 时占位值 `PLEASE_APPLY_BAIDU_AK`：百度瓦片空白/定位失败属预期，切 osmdroid 可完整体验。
- release 混淆开启，keep `com.baidu.**`、`org.osmdroid.**`（app/proguard-rules.pro）。

## 3. 模块归纳

| 模块 | 职责 | 关键类（对外接口） | 依赖 |
|---|---|---|---|
| `:app` | 壳：导航、首启隐私弹窗、Koin 模块装配、Key 注入 | `MainActivity`（NavHost: home / map?pid={pid} / settings，条目定位跳转带 pid）、`LocationApplication`（startKoin + MockLocationManager.init）、`di/AppModule`（Repository/MapAdapterFactory 单例） | 全部模块, Koin |
| `:core:common` | 无 Android UI 的纯基础：坐标转换、权限校验、系统跳转 | `GeoLatLng`、`MapSource`(枚举)、`CoordUtils`、`MockLocationAccess.isGranted`、`MockCheck`(validate/hasLocationPermission/isWifiEnabled/isWifiScanAlwaysAvailable)、`MockCheckError`、`SystemIntents`(start 支持回落页) | 无 |
| `:core:data` | 持久化与领域模型 | `SettingsRepository`（mapSource/intervalMs/floatingEnabled/privacyAgreed/mockEnabled/selectedPoint + setters）、`PointRepository`(history/favorites/byId/saveHistory/saveFavorite/setFavorite/delete/deleteAll/touch)、`buildPointRepository(context)`、`SavedPoint`/`SelectedPoint`、`local/`(PointEntity/PointDao/AppDatabase) | :core:common |
| `:core:ui` | 主题与通用组件 | `theme/LocationTheme`、`EmptyState`、`ConfirmDialog`、`MockStartFlow`（`MockStartGate`/`rememberMockStartGate`/`rememberPermissionGate`/`JumpGuideDialog`/`jumpDialogFor`）、`OnResumeEffect`、`PrivacyPolicyText`、`PrivacyPolicyDialog`（首启与设置页共用） | :core:common, Compose BOM |
| `:map:api` | 地图抽象（WGS84 契约） | `MapAdapter`（含 `reverseGeocode` 反查地点名，失败返 null 交上层回退）、`MapConfig`、`MapAdapterFactory` | :core:common |
| `:map:baidu` | 百度实现 + SDK 载体（libs/ 内 jar+so，仅 arm64-v8a） | `BaiduSdkInitializer.ensureInit`、`BaiduMapAdapter`（内部 BD09 转换、LocationClient 蓝点 5s 间隔且 onPause 停止）、`baiduReverseGeocode`（GeoCoder 反查，3s 超时兜底） | :map:api, :core:common, BaiduLBS jar |
| `:map:osm` | osmdroid 实现（Maven 依赖，无 Key） | `OsmMapAdapter`（WGS84 直通、120ms 防抖、私有目录缓存） | :map:api, :core:common, osmdroid-android |
| `:service:mock` | 虚拟位置核心：注入服务、悬浮窗、状态机 | `MockLocationService`（双 TestProvider 注入循环）、`FloatingControlService`、`FloatingButtonView`（2s 长按进度环/拖动）、`MockLocationManager`（init/start/stop/tryToggleFromOverlay/running）、`MockLocationService.isAlive` | :core:common, :core:data |
| `:feature:home` | 主页：选中卡片+开关（点击卡片进地图查看）、历史/收藏 Tab、条目标题=名称/副标题=经纬度（点击复制）、多选批量删除、条目定位跳转、空态 | `HomeScreen(pointRepository, settingsRepository, onAddPoint, onOpenMap, onLocateOnMap, onOpenSettings)`、`HomeComponents`(SelectedPointCard/PointListSection/TabsBar/SelectionBar/PointRow)、`HomeSelectionState`(多选状态) | :core:*, :service:mock, icons-extended |
| `:feature:map` | 地图选点：十字准星、缩放、经纬度输入、条目定位跳转+确认使用、锁定/保存/收藏、退出四选一 | `MapScreen(..., focusPointId)`（AskUseDialog 确认使用/仅查看）、`Dialogs.kt`(LatLngInputDialog/NameDialog/reverseGeocode)、`MapComponents`(MapSurface/Crosshair/BottomPanel) | :core:*, :map:api, :service:mock |
| `:feature:settings` | 设置：地图源/注入频率(10–100ms 步进10)/悬浮窗开关/WiFi闪回防护/隐私/开发者选项/关于 | `SettingsScreen(settingsRepository, onBack)` | :core:* |
| `build-logic` | convention plugins | `location.android.library`（minSdk26/Java11）、`location.android.library.compose`（+compose） | AGP9 + compose compiler |

## 4. 构建要点（踩坑记录）

- **仓库镜像**：`settings.gradle.kts` 依赖仓库加了阿里云镜像（public/google 优先，google()/mavenCentral() 兜底）；AGP9 内置 Kotlin（2.2.10）的 `kotlin-compiler-embeddable` 首次直连 Maven Central 下载极慢，无镜像会像"卡死"。
- **KSP1 + AGP9 内置 Kotlin**：需 `gradle.properties` 加 `android.disallowKotlinSourceSets=false`，否则 KSP 注册生成源码时报错。
- **Room 2.6.1 与 Kotlin 2.0 不兼容**（KSP 报 `unexpected jvm signature V`），已升 Room 2.7.2。
- **包路径易错**：`ProviderProperties` 在 `android.location.provider`；`AndroidView` 在 `androidx.compose.ui.viewinterop`；`LocalLifecycleOwner`（lifecycle 2.8+）在 `androidx.lifecycle.compose`。
- **模块签名泄漏**：DataStore `edit{}` 返回 `Preferences`，Repository setter 必须写成块体返回 Unit，否则下游模块被迫依赖 datastore。
- DataStore 属性委托 `preferencesDataStore` 是单例约束：同文件同进程只能有一份（SettingsRepository 持 context 单例使用）。
- `gradle-daemon-jvm.properties` 固定 JDK 21 工具链（foojay 解析）。
- **detekt/ktlint 接入**：detekt 1.23.8 在 Gradle 9.2.1 可用（2.0 尚为 alpha）；`@Composable` 命名需 `.editorconfig` 设 `ktlint_function_naming_ignore_when_annotated_with = Composable`；`EmptyFunctionBlock` 属 `empty-blocks` 规则集（非 style）；MagicNumber 对 Compose UI 字面量是噪音，已关闭。
- **出包命令**：正式包直接 `./gradlew release`（`app/build.gradle.kts` 的快捷任务，= assembleRelease + 自动输出**项目级** `build/outputs/named/虚拟定位_1.1.0_release.apk` 下划线命名副本）。
- **APK 体积**（release ≈13.5MB，debug ≈31MB 仅自测）：三项手段——① `packaging.jniLibs.useLegacyPackaging = true` 让 so 在 APK 内压缩存储（百度 map so 12.6MB→5.3MB；代价是安装后 so 解压、磁盘占用略增）；② release `isShrinkResources = true`；③ `androidResources.localeFilters += "zh"` 仅保留中文（AGP9 用 localeFilters，旧版 resourceConfigurations 已换名）。debug 大是因不混淆 + dex 未裁剪，交付一律用 release。
- **lintVital 阻断 release**：release 编译自动跑 lintVital，`ACCESS_MOCK_LOCATION` 会触发 MockLocation fatal 检查（lint 默认该权限仅限 debug 构建）；已在 manifest 该条目上加 `tools:ignore="MockLocation"` 定向豁免。

## 5. 待办与已知边界

- [ ] 百度 Key 申请后填 `local.properties` 的 `BAIDU_MAP_KEY`（绑定包名 com.chan.location + 签名 SHA1）。
- [ ] 百度 so 仅 arm64-v8a：x86_64 模拟器请切 osmdroid 源。
- [ ] 注入频率改后需重发 intent 生效：设置页改动后若服务运行中，由 MockLocationManager reconcile 的 intervalMs 变化自动重发。
- [ ] 手动验收清单见 `REQUIREMENTS.md` 第 8 节。
