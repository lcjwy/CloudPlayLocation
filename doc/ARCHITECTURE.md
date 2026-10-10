# 架构与核心逻辑说明

> 需求见 `doc/REQUIREMENTS.md`，功能模块速查见 `doc/FEATURES.md`，项目概览见根目录 `README.md`。本文档记录模块划分、依赖关系、核心逻辑与模块归纳，供后续 AI/开发者直接索引，无需重读代码。

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
  → 模拟位置应用已选？(独立 permission_probe 试探 addTestProvider, SecurityException=未选) → 否：跳 ACTION_APPLICATION_DEVELOPMENT_SETTINGS
  → (悬浮窗开启时) canDrawOverlays？ → 否：跳 ACTION_MANAGE_OVERLAY_PERMISSION
  → WiFi 已开启？(Toast 软提醒，不阻断，提示后直接启动)
  → MockLocationManager.start(point)
```
- 悬浮窗长按启停走同一校验链，但权限不足只 Toast 不弹窗（`MockLocationManager.tryToggleFromOverlay`）。
- **WiFi 闪回原理**：系统网络定位服务基于 WiFi 扫描（BSSID）可独立算出真实位置，部分经 FusedLocationProvider 的应用绕过 LocationManager 的 TestProvider，导致位置跳回真实位置。应用侧两层软提醒：① 启动前 WLAN 开启则 Toast 提示（不阻断）；② 设置页常驻「WiFi 闪回防护」区块，实时显示 WLAN 与系统「Wi-Fi 扫描」状态（`MockCheck.isWifiScanAlwaysAvailable`，ON_RESUME 刷新），引导关闭扫描类开关（跳 `android.settings.LOCATION_SCANNING_SETTINGS`，机型缺失回落 `ACTION_LOCATION_SOURCE_SETTINGS`）。**关闭扫描类开关无需断开 WiFi 联网**。

### 2.2 位置注入循环（MockLocationService）
- 注册双 TestProvider：GPS（POWER_USAGE_HIGH/ACCURACY_FINE）+ Network（LOW/COARSE）。**统一走废弃的 10 参 `addTestProvider` 重载 + 常量**（Gogogo 同款，含 API 31+；`ProviderProperties.Builder()` 新重载在部分 ROM 行为不一致）；注册后 `isProviderEnabled` 仍报禁用时（系统位置关闭常见）补一次 `setTestProviderEnabled(true)`。
- `HandlerThread("MockLocation")` 自循环：每 interval（10–100ms，默认 100）先 Network 后 GPS 各 `setTestProviderLocation` 一次。
- **注入容错（系统位置关闭场景）**：`setTestProviderLocation` 抛 `SecurityException`（部分 ROM 在系统位置关闭时）**容忍并继续重试，不 stopSelf**——系统位置开启后注入自动恢复；提示与日志整个服务周期仅一次（`apiErrorShown`，成功不复位：GPS/Network 单边交替失败时防刷屏）。模拟权限探测使用独立 `com.chan.location.permission_probe`，不修改运行中的 GPS/Network；（`MockLocationAccess.isGranted`）**先定论后清理**：`addTestProvider` 成功即已授权，清理调用的异常不参与判定，避免被误判为「未选择模拟位置应用」。
- Location 字段：accuracy(GPS=1f/Network=50f)、altitude=55.0、speed=0、bearing=0、time、elapsedRealtimeNanos、extras(satellites=7)。
- 运行中换点/改频率 = 重发 intent（`onStartCommand` 更新并重排循环）；服务无 BIND，状态经 companion `isAlive` 暴露。**进程模型**：注入/悬浮窗服务均在主进程；百度 `com.baidu.location.f` 跑 `:remote` 子进程且同样执行 LocationApplication——`isMainProcess()` 守卫保证 Koin 与 MockLocationManager 仅主进程初始化（曾在子进程误判 isAlive=false → 回滚开关 + 跨进程停服 + 清 provider，进地图页即触发）。
- **注册阶段失败 ≠ 注入阶段失败**：注册失败（`addTestProvider` 抛异常，部分 ROM 在系统位置关闭期间会拒绝，或 mock 选择竞态/瞬时 binder 失败）**不立即停服**——前台服务照常运行，注入循环内按 1s 重试注册（期间恢复条件后自动恢复注入）；**系统位置关闭期间由 `MockCheck.isLocationEnabled` 判定后 `RegistrationRetry.reset()` 清零计数、永不放弃**（用户可逆操作，防止位置重开时真实定位暴露导致"跳位置"）；仅系统位置开启下连续 15 次（约 15s）失败才 Toast + `MockLocationManager.onMockProvidersFailed()` 回滚开关 + stopSelf，不留「运行中」通知却永不注入的假运行态。注入阶段的 `SecurityException` 则容忍重试（见上）。注册异常类型 ROM 差异大，`ensureProviders`/`enableIfDisabled` 一律按宽捕获处理。
- 注册任一步失败立即回滚双 provider；销毁时无条件清理，注册与销毁加锁防止清理后再注册。注入发现 provider 丢失时复位注册状态，由重试循环恢复。
- 双 provider 原因：部分应用只读 GPS、部分融合 Network，双注入保证一致；GMS 设备额外尽力注册 fused（`FusedLocationProviderClient` 走融合通道，不 mock 则旁路替身），失败可容忍不参与成败判定。注册后 `kickProvider` disable→enable 翻转广播可用性变化，唤醒启动前已休眠的定位订阅方。每 5s 健康检查（系统位置开启时）**只在注入未落地时介入**（工作正常时做启用态翻转只会扰动订阅，表现为"开始正常几秒后失效"）：先 `injectionLanded`（mock 标记 + 坐标比对），未落地才 `reenableIfNeeded` 修复禁用态/检测注销，修复后复检仍失败才连续计数告警。

#### 注入域时间机制一览（全部由注入线程单一 tick 驱动，互为条件、不并发竞争）

| 机制 | 常量/值 | 触发前提 | 作用 | 持续后的表现 |
|---|---|---|---|---|
| 注入循环 tick | `intervalMs` 10–100ms（设置项，默认 100） | 服务运行 | 注入 GPS/Network(/fused) | — |
| 息屏降频 | `SCREEN_OFF_MIN_INTERVAL_MS` 1000ms | 息屏 | tick 钳制到 ≥1s 省电 | 注入变慢但不中断 |
| 注册重试 | `REG_RETRY_INTERVAL_MS` 1000ms 节流 | 未注册且系统位置开启 | 尝试 `ensureProviders` | — |
| 注册放弃 | `REG_MAX_FAILURES` 15 次 × 1s ≈ **15s** | 系统位置**开启**下连续注册失败 | 回滚开关 + 停服（模拟应用被取消选择等不可自愈场景） | 开关弹回关 + Toast |
| 位置关闭等待 | `RegistrationRetry.reset()` 每 tick | 未注册且系统位置关闭 | 清零失败计数、永不放弃（可逆等待） | 通知显示"等待系统位置" |
| 健康检查 | `PROVIDER_ENABLE_CHECK_MS` 5000ms | **已注册**且系统位置开启 | 落地校验（只读）；未落地才启用态修复 | — |
| 未落地告警 | `INEFFECTIVE_STRIKES` 3 次 × 5s ≈ **15s** | 健康检查连续未落地 | Toast 提示旁路/失效（整个服务周期一次） | 通知仍"运行中"，需按提示排查 |

- 两个"15s"互斥不叠加：注册放弃作用于**未注册**路径，未落地告警作用于**已注册**路径。
- 健康检查按 `elapsedRealtime` 计时，息屏降频时自动变为每 5 个 tick 一次（仍是真实 5s）。
- 位置关闭等待期不注册、不检查，仅保活 tick 与等待文案通知。

UI/地图域定时（互不相干）：悬浮球长按 2s（`LONG_PRESS_MS`，进度环 33ms 刷新）、地图页退出延迟销毁 300ms、OSM 中心回报防抖 120ms、百度反地理编码 3s 超时、蓝点定位 5s 一次（`setScanSpan(5000)`，页面离开前台即停）。

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
- `onCenterChanged`/`onZoomChanged` 双回调：百度程序化移动（`moveCamera`/`moveToMyLocation`）直接回报精确目标中心并抑制动画期间抖动；Finish 在手势结束时全量回报，程序化落点后只补报缩放（比例尺依赖）——不回写 BD09→WGS 往返换算值，避免米级漂移覆盖已选点精确坐标；OSM 经 MapListener 120ms 防抖回报。
- 蓝点更新不自动移动相机，保留初始选中点/历史点；点击「我的位置」时才将 BD09 定位结果转成 WGS84 并回报。地图生命周期监听页面 ON_RESUME/ON_PAUSE，后台停止定位客户端。
- 百度懒初始化：`BaiduSdkInitializer.ensureInit(context, privacyAgreed, customKey)` 幂等，仅在工厂创建百度 Adapter 时调用，先于 MapView 创建；`setAgreePrivacy(true)` + `initialize` + `setCoordType(BD09LL)`。自定义 Key 经 `MapConfig.baiduKey`（MapScreen 从 DataStore 读取）传入，非空时 `SDKInitializer.setApiKey` 先于 initialize 覆盖内置 Key；SDK 进程级单次初始化，改 Key 重启应用生效。
- 百度显示 `wgs2bd09`、回调 `bd092wgs`；`programmaticMove` 标志抑制程序化相机移动期间的回调抖动，手势起始（`REASON_GESTURE`）强制复位——程序化动画无回调时（已在目标点/缩放极限）标志等不到 Finish 复位，不复位会导致拖动不再回报中心点。
- osmdroid：WGS84 直通；`Configuration` 缓存指向应用私有目录（免存储权限）；中心点回调 120ms 防抖。
- UI（Compose）：`AndroidView` 包 MapView + 中心十字准星 Canvas 覆盖层 + 底部坐标面板 + 右侧缩放按钮 + 标题栏下方比例尺（`MapScaleBar.kt`，墨卡托米/像素公式实时换算），两源共用。入场统一默认 zoom 17（约 200m 级）并居中目标点（选中点/`pid`）；不设近景级别——米级坐标系换算误差（BD09 往返、SDK 蓝点换算常数差异）在近景下肉眼可见。

### 2.5 悬浮窗手势（FloatingButtonView + FloatingControlService）
- `TYPE_APPLICATION_OVERLAY` + NOT_FOCUSABLE|NOT_TOUCH_MODAL，rawX/rawY 差值拖动 + `updateViewLayout`。
- ACTION_DOWN 起 2s 计时（33ms 进度环刷新）；移动超 touchSlop → 判定拖动并取消长按；2s 计满 → 震动 + `tryToggleFromOverlay()`。
- FloatingControlService 为 specialUse FGS（manifest 声明 PROPERTY_SPECIAL_USE_FGS_SUBTYPE）；仅在虚拟位置运行中存在，停止注入即随 reconcile 消失。

### 2.6 状态管理与 DI
- `MockLocationManager`（:service:mock 进程级单例）：以 `(悬浮窗开关, 运行开关, 注入频率)` 的 DataStore 组合流为唯一事实源，`reconcileOnChange` collect 后统一启停 MockLocationService/FloatingControlService；进程重启校正残留 mockEnabled。UI 与悬浮窗只调 `start(point)`/`stop()`/`tryToggleFromOverlay()`。`start`：运行中重发 intent 换点；服务已死则**直接重启**（服务被系统杀死后 mockEnabled 残留 true，仅写开关会被 distinctUntilChanged 吞掉导致点击无响应）。FGS 启动失败（后台限制）经 `startServiceSafe` **回滚对应服务的开关**（启动成功才写运行开关），悬浮窗失败不误关虚拟位置。
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
- **出包命令**：正式包直接 `./gradlew release`（`app/build.gradle.kts` 的快捷任务，= assembleRelease + 自动输出**项目级** `build/outputs/named/云游_<版本>_<yyyyMMdd_HHmmss>_release.apk` 下划线命名副本，版本号见 `appVersionName`）。
- **APK 体积**（release ≈13.5MB，debug ≈31MB 仅自测）：三项手段——① `packaging.jniLibs.useLegacyPackaging = true` 让 so 在 APK 内压缩存储（百度 map so 12.6MB→5.3MB；代价是安装后 so 解压、磁盘占用略增）；② release `isShrinkResources = true`；③ `androidResources.localeFilters += "zh"` 仅保留中文（AGP9 用 localeFilters，旧版 resourceConfigurations 已换名）。debug 大是因不混淆 + dex 未裁剪，交付一律用 release。
- **本地数据保护**：`allowBackup=false`；旧版备份规则与 API 31+ 云备份/设备迁移规则均排除数据库、文件、偏好和外部应用数据，点位与设置不参与系统备份或迁移。
- **地图返回卡顿**：退出地图页时 `onDestroy` 的 GL/JNI 同步释放（百度 MapView）发生在返回动画期间会掉帧；`rememberMapAdapter` 的 onDispose 将 pause+destroy 延迟 300ms 到动画结束后执行（主线程 Handler），期间地图保持渲染。
- **lintVital 阻断 release**：release 编译自动跑 lintVital，`ACCESS_MOCK_LOCATION` 会触发 MockLocation fatal 检查（lint 默认该权限仅限 debug 构建）；已在 manifest 该条目上加 `tools:ignore="MockLocation"` 定向豁免。

## 5. 待办与已知边界

- [ ] 百度 Key 申请后填 `local.properties` 的 `BAIDU_MAP_KEY`（绑定包名 com.chan.location + 签名 SHA1）。
- [ ] 百度 so 仅 arm64-v8a：x86_64 模拟器请切 osmdroid 源。
- [ ] 注入频率改后需重发 intent 生效：设置页改动后若服务运行中，由 MockLocationManager reconcile 的 intervalMs 变化自动重发。
- [ ] 手动验收清单见 `REQUIREMENTS.md` 第 8 节。
