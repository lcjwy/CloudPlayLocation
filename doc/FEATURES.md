# 云游 · 功能模块文档

> 按功能模块归纳：功能点、行为与边界、代码入口。需求细则见 `REQUIREMENTS.md`，实现原理见 `ARCHITECTURE.md`。坐标除特别说明外一律 WGS84。

## 1. 首页（:feature:home）

**代码入口**：`HomeScreen.kt`（装配与 HomeController）、`HomeComponents.kt`（列表与卡片）、`HomeSelectionState.kt`（多选状态）

- **选中位置卡片**：显示当前选中点名称（空名显示"未命名位置"）与经纬度；右侧开关启停虚拟位置（运行中/已停止）。
  - 点击开关：未选点 Toast"请先选择位置"；已选点走校验链（见 §6）后启动。
  - 点击卡片本体：已选点 → 进入地图查看当前虚拟位置（默认 200m 级视角居中该点）；未选点 → 与「添加点位」一致先过权限闸门。
- **历史 / 收藏双 Tab**：历史为**全部点位**按 lastUsedAt 倒序（同位置约 11m 内去重合并；收藏条目仍保留在历史中，收藏只是标记不搬家）；收藏为 isFavorite 子集，同一条目可同时出现在两个 Tab。
- **条目操作**：点击条目 = 设为选中并立即启用（校验链通过后）；「地图定位」携带点位 id 跳地图居中该点并弹"使用该点"确认；收藏星标切换；删除单条（无确认）；副标题经纬度点击复制。名称最多两行（超出省略）。
- **多选批量删除**：列表右上进入多选模式，支持全选/取消全选、删除前确认弹窗；切 Tab 自动退出多选，列表删空自动退出。
- **数据一致性**：`HomeData` 持有 `State<T>` 而非取值快照——remember 闭包捕获首帧实例后仍读到实时值（曾因值快照出现"已选点却提示请先选择"的过期读）。

## 2. 地图选点页（:feature:map）

**代码入口**：`MapScreen.kt`（装配/MapController）、`MapComponents.kt`（画布与控件）、`MapDialogs.kt`（弹窗族）、`MapScaleBar.kt`（比例尺）、`Dialogs.kt`（命名/坐标输入/反查）

- **选点交互**：屏幕中心固定十字准星，拖动即选点；底部面板实时显示中心坐标（WGS84）与"未保存"状态（与已提交点比较，1e-6 度内视为已保存）。
- **比例尺**：标题栏下方右侧，按中心纬度 + 缩放级别实时换算（墨卡托米/像素公式），显示值取 1/2/5×10ⁿ 整数；缩放级别经 `MapAdapter.onZoomChanged` 回调驱动，两地图源共用组件。
- **入场视角**：统一 zoom 17（约 200m 级）并居中目标点（选中点 / `pid` 定位点）；不设近景级别（米级坐标系换算误差近景可见）。程序化落点（`moveCamera`/回到我的位置）直接回报精确目标坐标，Finish 不回写 BD09 往返换算值。
- **经纬度输入**：±90/±180 范围校验；坐标系可选 WGS84（默认）/ BD09（自动转 WGS84）；确定后相机动画移动。
- **锁定虚拟位置**：名称弹窗（默认名走反查链）→ 确认后落历史 + 启动注入 + 提交为已保存。
- **保存 / 收藏**：各自弹窗可编辑名称；反查链 = 百度反地理编码（当前地图源）→ 系统 Geocoder → 坐标兜底；两层均失败重存同位置时保留原名称不被坐标串覆盖。**不确认不落库**。
- **退出保护**：有未保存选点时返回键/标题栏返回弹四选一（保存到历史 / 保存并收藏 / 不保存 / 取消）。
- **蓝点（真实位置）**：已同意隐私且有定位权限时显示；百度 5s 一次定位、页面离开前台即停（耗电优化）；「我的位置」按钮一键回位。
- **性能**：退出页时地图实例的 onPause/onDestroy 延迟 300ms，避开返回动画期间的 GL/JNI 同步释放掉帧。

## 3. 虚拟位置服务（:service:mock）

**代码入口**：`MockLocationService.kt`（前台注入服务）、`MockLocationManager.kt`（启停状态机）

- **注入机制**：前台服务（`foregroundServiceType="location"`）注册 GPS + Network 双 TestProvider（替身真实 provider，**运行期间压制真实定位并注入虚拟坐标**），GMS 设备额外尽力注册 fused provider（拦 `FusedLocationProviderClient` 旁路，失败可容忍）；HandlerThread 循环按设定频率（10–100ms）依次注入。Location 字段填全（accuracy/altitude/bearing/speed/time/elapsedRealtimeNanos/satellites=7）。
- **注入落地自检**：每 5s 比对 GPS lastKnown 与目标，连续约 15s 不一致 → Toast 提示关闭「Wi-Fi 扫描/提高定位精确度」（融合定位/Wi-Fi 扫描回填真实位置的典型症状），整个服务周期提示一次。
- **运行中通知**：显示当前坐标，点击回主页；仅坐标变化时更新（setOnlyAlertOnce）。
- **耗电优化**：息屏注入间隔自动钳制到 ≥1s，亮屏立即恢复设定值。
- **容错语义（关键）**：
  - 注入阶段异常（部分 ROM 系统位置关闭时类型不一，不止 `SecurityException`）→ **全量吞掉容忍重试不停止**（异常外漏会杀死注入线程致进程崩溃、TestProvider 残留），系统位置开启后自动恢复；Toast 整个服务周期仅一次（`apiErrorShown` 不复位，防 GPS/Network 单边交替失败刷屏）。
  - 注册阶段 `addTestProvider` 失败（模拟位置应用被取消选择）→ Toast + 回滚运行开关 + 停服，不留"运行中"通知却永不注入的假运行态；**系统位置关闭期间不计失败、永不放弃**（`MockCheck.isLocationEnabled` 判定 + `RegistrationRetry.reset()` 清零），位置重开后下一轮自动恢复注册与注入——若在关闭期间放弃停服，位置重开时真实定位直接暴露，表现为"跳位置"；等待期间通知改示"等待系统位置"文案。
  - 权限探测（`MockLocationAccess`）先定论后清理，且两个清理调用各自兜底——防止探测残留 TestProvider 全局压制真实 GPS。
  - 停止链路：`removeTestProvider` 逐 provider 独立吞掉全部异常（系统位置关闭时部分 ROM 抛类型不一）；`stop()` 直停双服务 + 兜底清理，不依赖收集器存活与 `onDestroy` 成功；App 启动无条件清理一次进程残留。
  - 循环自愈：tick 整体异常兜底（异常只记日志不杀线程）；`scheduleLoop` 检测注入线程死亡（`isAlive`）并重建——线程死后 post 静默失效，换点/重发 intent 无法恢复注入，表现为"设置成功一次后失效、进程重启才恢复"。
  - 启用态校验：每 5s（系统位置开启时）校验双 provider 启用状态，禁用即重新启用、注销（IAE）即复位重注册——防系统位置开关往返后 provider 留在禁用态（注入不报错但无人消费）。
- **状态机（MockLocationManager）**：以 DataStore 组合流（悬浮窗开关, 运行开关, 频率）为唯一事实源统一启停；进程重启校正残留开关（杀后台即停属官方可接受行为，校正时 Toast 提示"应用退出后虚拟位置已自动停止"）并无条件清理残留 TestProvider；换点/改频率直接重发 intent；FGS 启动失败（异常类型全量接住，防 reconcile 收集器被杀死后启停静默失效）按服务回滚各自开关（悬浮窗失败不误关虚拟位置）。

## 4. 悬浮窗（:service:mock）

**代码入口**：`FloatingControlService.kt`（承载服务）、`FloatingButtonView.kt`（按钮视图）

- 单图标按钮（56dp，十字准星图案），`TYPE_APPLICATION_OVERLAY` 全局显示，可拖动（范围钳制在屏幕内）。
- **长按 2 秒切换启停**：按住显示进度环，移动超 touchSlop 判定拖动并取消长按；触发经 `MockLocationManager.tryToggleFromOverlay` 统一校验，失败 Toast 原因。
- 仅虚拟位置运行中且悬浮窗开关开启时存在，停止注入即消失；服务 `START_NOT_STICKY`（进程被杀不自动复活，避免幽灵按钮）。
- 视图脱离窗口时清理挂起的长按/进度回调。

## 5. 设置页（:feature:settings）

**代码入口**：`SettingsScreen.kt`（装配）、`SettingsSections.kt`（各分区）

- **地图源**：百度 / 开源地图（OSM）；未同意隐私时选百度先引导同意。
- **注入频率**：10–100ms 刻度滑杆（步进 10ms），附耗电说明与档位文案（高刷新/均衡/省电）。
- **悬浮窗按钮**：未授权时跳系统"显示在其他应用上层"授权页，返回自动完成开启（OnResumeEffect）。
- **WiFi 闪回防护**：实时显示 WLAN 与系统 Wi-Fi 扫描状态，跳转扫描设置页（缺失机型回落位置信息页）；说明"关闭扫描无需断网"。
- **隐私政策**：随时复查全文（与首启同一弹窗组件）；显示当前同意状态。
- **关于**：云游 v版本号 · 坐标系说明；开发者选项快捷跳转。

## 6. 权限与引导（:core:ui）

**代码入口**：`MockStartFlow.kt`（MockStartGate 校验闸门）、`PrivacyDialog.kt` / `PrivacySection.kt`（隐私政策）

- **统一校验链**（首页/地图/悬浮窗共用）：位置权限（FINE 或 COARSE）→ 模拟位置应用已选（试探 `addTestProvider`，先定论后清理）→ 悬浮窗权限（按需）。失败逐项引导弹窗跳系统设置。
- **WiFi 软提醒**：启动时 WLAN 开启则 Toast 提示闪回风险（不阻断）。
- **隐私政策**：首启不可跳过（不同意则百度源不可用、强制 OSM、无蓝点）；分段展示，重点段（AI 生成声明/风险免责/使用限制）加粗加大；同意后百度 SDK 才懒初始化。

## 7. 数据存储（:core:data）

**代码入口**：`PointDao.kt` / `PointRepository.kt` / `SettingsRepository.kt`

- **Room 表 `points`**：id、name、wgsLat/wgsLng（主坐标）、bdLat/bdLng（冗余）、isFavorite、createdAt、lastUsedAt。同位置（约 11m）重存合并；批量删除空列表防御（IN () 非法 SQL）。
- **DataStore 键**：地图源、注入频率、悬浮窗开关、隐私同意、运行开关、选中点快照（名称+经纬度，防列表删除后丢失；重启不恢复运行仅恢复显示）。
- 仓库层对上层屏蔽 Room 类型；`IN (:ids)` 空列表由仓库层保证非空。

## 8. 地图双源抽象（:map:api / :map:baidu / :map:osm）

**代码入口**：`map/api/MapAdapter.kt`（接口）、`BaiduMapAdapter.kt`、`OsmMapAdapter.kt`、`AppModule.kt`（工厂）

- **接口约定**：出入参一律 WGS84，各实现内部完成显示坐标系转换（百度 wgs↔bd09）；`onCenterChanged`/`onZoomChanged` 双回调驱动准星坐标与比例尺；`currentZoom`/`moveCamera`/`zoomIn`/`zoomOut`/蓝点/反地理编码/生命周期。
- **百度实现**：显示层 BD09LL；程序化动画抑制中心回调防抖动，手势起始强制复位（`REASON_GESTURE`）防标志卡死，Finish 无条件补报；反地理编码 3s 超时兜底（AK 无效/断网时回调可能不触发）。
- **OSM 实现**：WGS84 直通；瓦片缓存指向应用私有目录（免存储权限）；中心回报 120ms 防抖。
- **工厂选择**（:app）：百度源要求已同意隐私且 SDK 初始化成功，否则回退 OSM；百度 Key 缺失仅瓦片空白不影响逻辑。
- **坐标转换**（:core:common `CoordUtils`）：全国统一近似算法（Gogogo 同源）；GCJ02 层境外直通，BD09 层全球应用（百度瓦片全球 BD09 语义）；单元测试 `CoordUtilsTest` 以往返一致性 + 物理合理性兜底。

## 9. 公共基础（:core:common / :core:ui / build-logic）

- `SystemIntents`：开发者选项/位置信息/WiFi 扫描/悬浮窗授权等系统页安全跳转（ActivityNotFound 兜底）。
- `MockCheck`：校验项与 WiFi 闪回检测（含 `isScanAlwaysAvailable` 容错）。
- `core:ui` 组件：ConfirmDialog、EmptyState、OnResumeEffect、PrivacyDialog、MockStartGate。
- build-logic：`location.android.library(.compose)` convention 插件统一 minSdk/Java 11/Compose 开关；根项目统一 ktlint（行宽 100）与 detekt（函数 ≤40 语句）。
