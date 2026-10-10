# 虚拟定位 App 需求文档

> 项目：`E:\Code\DemoCode\Android\Location`（包名 `com.chan.location`）
> 示例项目（移植来源）：`E:\Code\fromGit\android\Gogogo`
> 本文档为唯一需求基准，配套 `doc/ARCHITECTURE.md`（架构与核心逻辑）与 `doc/FEATURES.md`（功能模块文档）；项目概览与构建指引见根目录 `README.md`。实施前请通读各文档。

## 1. 项目背景与目标

基于 Gogogo 的成熟实现（百度地图 + Android 原生 Mock Location，免 Root、无 Xposed），在空白 Compose 项目上构建**地图选点式虚拟定位工具**：

- **应用名「云游」**（取"云游四方"意，身不动而行千里；用户可见处统一用此名，文档与代码描述沿用"虚拟定位/虚拟位置"指代功能本身）。
- 移植：百度 SDK 集成方式、TestProvider 注入逻辑（Java → Kotlin 重写）、坐标系转换、前台服务模式。
- 新增：双地图源（百度 + osmdroid）、历史/收藏、主页开关、单按钮悬浮窗、设置页、隐私政策流程。

**技术底座**：AGP 9.0.1 / Gradle 9.2.1 / Kotlin 2.0.21 / JDK 21 / Compose Material3 / minSdk 26 / targetSdk 36。仅中文（`values` 默认即中文，不做多语言）。

## 2. 坐标系约定（全局规则）

| 场景 | 坐标系 |
|---|---|
| 内部存储（Room/DataStore） | WGS84（真实 GPS 坐标） |
| 注入 TestProvider | WGS84 |
| 百度地图显示/相机 | BD09LL（经 `CoordUtils` 转换） |
| osmdroid 显示/相机 | WGS84（直接使用） |
| 经纬度输入弹窗 | 用户可选，默认 WGS84，可选 BD09（BD09 输入先转 WGS84 再入内部流程） |

默认视角（无上次选点时）：北京中心 `39.908722, 116.397499`（WGS84）。

## 3. 功能需求

### 3.1 主页（启动页，不直接进地图）
- 顶部"当前选中位置"卡片：显示名称 + 经纬度；右侧总开关控制虚拟位置启停。
  - **点击卡片 = 打开地图页并居中显示当前虚拟位置**（纯查看，不弹「使用该点」确认）；未选点时点击等同「添加点位」（先过 3.4 权限检查再进地图）。
- 顶部 Tab「历史 | 收藏」：
  - 历史列表为**全部点位**按 `lastUsedAt` 倒序（收藏条目仍保留在历史中，收藏只是标记不搬家）；收藏列表为 `isFavorite = true` 过滤（同一条目可同时出现在两个 Tab）。
  - 条目标题（位置名称）最多显示**两行**，超出省略；副标题为经纬度。
  - 列表项：**标题 = 位置名称**（空白名显示"未命名位置"）；副标题 = 经纬度一行，**点击即复制**到剪贴板。
  - **点击项 = 设为当前选中点并立即启用虚拟位置**（更新 `lastUsedAt`）。
  - 项上操作：地图定位（携带已记录经纬度跳转地图页，见 3.2）、收藏星标切换、删除。
  - **多选批量删除**：Tab 栏右侧多选按钮进入多选模式（条目变复选框，点击行切换勾选；顶栏提供退出/已选计数/全选/删除），删除前弹确认窗；切 Tab、删空列表自动退出多选。
- 空态（列表为空）：文案"暂无记录" + 「添加点位」按钮。
  - 点击按钮先做权限检查（见 3.4），全部通过后跳地图选点页。
- 开关/点击项启用前的校验链见 3.4；校验失败弹引导弹窗，不崩溃、不静默失败。

### 3.2 地图选点页
- 顶部标题栏：左侧返回按钮、标题「地图」、右侧「我的位置」按钮（蓝色，需定位权限且同意隐私时显示）。
- 地图：显示、拖动平移、双指缩放、双击放大、加减号缩放按钮。
- **比例尺**：标题栏下方右侧常驻（Compose 实现，两源共用），按中心纬度 + 缩放级别实时换算真实地物长度，显示值取 1/2/5×10ⁿ 整数（如 500 m / 200 m / 10 m）；缩放级别由 `MapAdapter.onZoomChanged` 回调驱动（拖动/双指/缩放按钮均触发）。
- **入场视角**：统一默认比例尺约 **200m 级**（zoom 17）并居中到目标点（有点位/选点居中该点，无则默认北京中心）；不使用更近的级别——米级坐标系换算误差在近景下会放大到肉眼可见（1.2.2 曾用 10m 级近景入场导致坐标显示偏差，已回退）。
- **屏幕中心固定十字准星**（Compose 覆盖层实现，两地图源共用），拖动地图即改变选点，底部实时显示中心点坐标（WGS84）。
- 「经纬度输入」按钮 → 弹窗：
  - 纬度/经度两个输入框，校验范围 ±90 / ±180，非法输入提示。
  - 坐标系选择：WGS84（默认）/ BD09。
  - 确定后相机动画移动到目标点，用户可继续拖动微调。
- **条目定位跳转**：从主页历史/收藏点「地图定位」→ 携带该点 id 打开本页并直接居中到其经纬度，地图就绪后弹窗确认「使用该点」（走 3.4 校验链，通过后更新 `lastUsedAt` 并启动注入）或「仅查看」。主页卡片点击进入时直接居中到当前选中点，不弹确认（该点已在用）。
- 「锁定虚拟位置」按钮：确认弹窗后，将当前中心点设为选中点并启动虚拟位置服务。
- 「保存」「收藏」按钮：各自弹确认框，可编辑名称（默认名反查链：**百度反地理编码（当前地图源）→ 系统 `Geocoder` → "纬度,经度" 兜底**）。**用户不确认不算记录，不落库**。反查两层均失败且名称留空时：重存同位置**保留原名称**（不被坐标串覆盖），仅新条目落坐标名。
- 返回退出（BackHandler）：若存在未保存的已选点 → 弹窗四选一「保存到历史 / 保存并收藏 / 不保存 / 取消」；无未保存选点直接退出。
- 地图源由设置决定，两源 UI 交互完全一致（差异封装在 MapAdapter 内）。

### 3.3 设置页（DataStore 持久化，修改即生效，不弹确认窗）
- **地图源**：百度 / osmdroid 单选，默认百度。未同意隐私政策时选百度 → 提示需先同意隐私政策。
- **百度地图 Key**：可输入自定义 Key 替换应用内置 Key（内置 Key 不显示值，仅显示"当前使用内置 Key"状态）。输入框默认以圆点掩码显示，点右侧眼睛图标切换明文；保存空白即清除恢复内置。经 `SDKInitializer.setApiKey` 运行时覆盖（先于 initialize），**重启应用后生效**（SDK 进程级单次初始化）。
- **注入频率**：10ms–100ms，步进 10ms 的离散刻度 Slider（默认 100ms）。说明文案："间隔越小定位越新鲜，但越耗电"。
- **悬浮窗开关**：开启时检查 `Settings.canDrawOverlays()`，未授权 → 引导跳 `ACTION_MANAGE_OVERLAY_PERMISSION`；关闭则移除悬浮窗并停止悬浮窗服务。
- **WiFi 闪回防护**：显示当前 WLAN / 系统「Wi-Fi 扫描」开关状态（回页面自动刷新），说明闪回原理并引导关闭系统扫描类开关：跳转 `android.settings.LOCATION_SCANNING_SETTINGS`（隐藏 action，机型不支持则回落到 `ACTION_LOCATION_SOURCE_SETTINGS`）。**无需断开 WiFi 联网**。
- **隐私政策**：查看全文（本地内置文案）+ 重新选择同意/不同意。
- **开发者选项**：跳转 `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`。
- **关于**：版本号、坐标系说明。

### 3.4 权限流程（最小化声明）
**运行时申请**（主页「添加」或开总开关时，一次性申请）：
- `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`（Android 12+ 需同时声明，适配精确/模糊选择）。
- `POST_NOTIFICATIONS`（Android 13+，前台服务通知可见性）。

**静态声明清单**（全部与功能直接相关，不得多引）：
```
ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION / POST_NOTIFICATIONS /
ACCESS_MOCK_LOCATION / INTERNET / FOREGROUND_SERVICE /
FOREGROUND_SERVICE_LOCATION / FOREGROUND_SERVICE_SPECIAL_USE / SYSTEM_ALERT_WINDOW
```
- `ACCESS_MOCK_LOCATION` 为废弃的签名级权限（运行时不可授予），但开发者选项「模拟位置信息应用」选择器**仅列出声明了它的应用**，必须声明否则应用不出现在选择器中。lint 的 MockLocation 检查会视其为 fatal 阻断 release 编译，已用 `tools:ignore="MockLocation"` 定向豁免（本应用核心功能即模拟定位，release 同样需要）。
- 明确**不引入** Gogogo 中的 `READ_PHONE_STATE`、`READ_EXTERNAL_STORAGE`、`REQUEST_INSTALL_PACKAGES` 等无关权限。
- 构建后核对 merged manifest；osmdroid AAR 合入的多余权限用 `tools:node="remove"` 剔除。

**启用虚拟位置前的校验链**（按序，任一失败弹引导弹窗）：
1. 位置权限已授予（未授予 → 申请）。
2. 本应用已被选为"模拟位置信息应用"：检测方式 = 试探性 `LocationManager.addTestProvider(GPS)` 成功即已授权（随后清理探测痕迹；**清理调用的异常不影响判定**——部分 ROM 在系统位置关闭时会对其抛异常），`SecurityException` 则未授权 → 引导弹窗跳开发者设置页（文案含"若已设置仍无法启动，请先开启系统位置信息"提示）。
3. （仅悬浮窗开启时）overlay 权限 → 跳 `ACTION_MANAGE_OVERLAY_PERMISSION`。
4. **WiFi 提醒（软提醒，不阻断）**：WLAN 开启时系统可能基于 WiFi 扫描算出真实位置导致虚拟位置"闪回"。启动前检测 `WifiManager.isWifiEnabled`，开启则 **Toast 提示后直接继续启动**（不弹窗）。设置页另有常驻「WiFi 闪回防护」区块，引导关闭系统的「Wi-Fi 扫描」等扫描类开关（无需断开 WiFi）。
- **不校验系统 GPS 开关**：推荐使用顺序 = 先启动虚拟位置，再手动开启系统位置（TestProvider 注入不依赖 GPS 预先开启）。

### 3.5 虚拟位置服务（前台）
- `MockLocationService`：前台服务，`foregroundServiceType="location"`。
  - 注册 GPS + Network 双 TestProvider：API 31+ 用 `ProviderProperties`（GPS: POWER_USAGE_HIGH/ACCURACY_FINE；Network: POWER_USAGE_LOW/ACCURACY_COARSE），API 26–30 用 `Criteria` 重载。注册后对双 provider 显式 **disable→enable 翻转**：系统位置已开启时注册不会产生可用性变化事件，启动前已订阅并休眠的系统定位服务不会重新订阅（"有时没唤醒"），翻转踢醒。GMS 设备额外尽力注册 fused provider（很多 App 走 `FusedLocationProviderClient`，只替身 GPS/Network 拦不住它——"部分应用仍显示真实位置"的根因）；fused 仅 API 31+ 且注册失败可容忍（无 GMS 的国产 ROM 无此 provider），不参与注册成败判定。
  - **注入落地自检**：服务自身常驻订阅 GPS 投递流（兼防 ROM 对无消费者 provider 休眠停摆），每 5s（系统位置开启时）校验收到的投递是否带 mock 标记且新鲜（10s 内）——连续约 15s 未落地说明注入被旁路或 provider 失效，Toast 提示关闭「Wi-Fi 扫描/提高定位精确度」并写日志，不再静默"运行中却不生效"。
  - HandlerThread 循环按**设置频率（10–100ms，默认 100ms）**依次调用 `setTestProviderLocation`（GPS 与 Network 各一次）。
  - **耗电优化**：息屏后注入间隔钳制到 ≥1s（亮屏立即恢复设定值）；百度蓝点定位 5s 一次且地图页离开前台即停止，避免后台持续 GPS/WiFi 扫描。
  - Location 字段：accuracy、altitude（默认 55.0）、bearing、speed、`System.currentTimeMillis()`、`elapsedRealtimeNanos()`、extras（`satellites=7`）。
  - 通知：显示当前坐标，点击回 MainActivity；停止时 removeTestProvider 双 provider + stopForeground。
  - **异常提示**：设置/注入过程异常必须反馈用户，不静默——首页卡片启动路径的异常（注册权限缺失、前台服务启动失败、后台启动受限）即时 Toast；循环注入的 `SecurityException` 容忍重试（系统位置可不开，开启后自动恢复），Toast **整个服务周期仅一次**（防 GPS/Network 单边交替失败刷屏）。
  - **失败不留假运行态**：注册 TestProvider 失败 → 服务不立即终止，循环内按 1s 重试（部分 ROM 在系统位置关闭期间拒绝注册，开启后自动恢复；瞬时失败同样自愈）；**系统位置关闭期间不计失败、永不放弃**（用户可逆操作——若放弃停服，位置重开时真实定位直接暴露，表现为"跳位置"），等待期间通知改示"等待系统位置"文案（状态翻转时更新）；仅系统位置开启下连续约 15s 失败 → Toast + 回滚运行开关 + 停止服务，不保留"运行中"通知；虚拟位置与悬浮窗两个前台服务启动失败（含管理器侧 `startForegroundService` 被拒、服务内 `startForeground` 抛异常、悬浮窗加窗被拒）时**各自回滚各自的开关**并提示对应文案，悬浮窗失败不误关虚拟位置。
  - **关闭必须关净（停止链路加固）**：`stop()` 直停两个服务并兜底清理 TestProvider，不依赖 reconcile 收集器存活与 `onDestroy` 清理成功——系统位置关闭时部分 ROM 注销/注入 provider 抛类型不一的异常（不止 `SecurityException`），任何一环异常外漏都会杀死注入线程或收集器，留下压制真实定位的残留（"已关闭仍在生效"）；注入与注销异常逐 provider 全量吞掉，App 启动时再无条件清理一次进程残留。
  - **注入循环必须自愈**：tick 整体异常兜底（任何异常只记日志、不杀线程），调度前检测注入线程死亡并重建（线程被异常杀死后 looper 未 quit，post 静默失效且无人消费——服务存活、开关显示运行中却永不注入，直到进程重启才恢复，表现为"一天只能设置成功一次"）。
  - **进程模型（关键约束）**：百度定位 SDK 声明 `:remote` 子进程服务，其进程创建同样执行本应用 Application——**Koin 与 MockLocationManager 必须只在主进程初始化**。否则子进程内服务静态 `isAlive` 恒为 false，init 误判"服务已死"回滚开关并 Toast，子进程 reconcile 收集器跨进程 stopService 杀掉真服务、清理 TestProvider（表现为"进入地图页 Toast'已自动停止'后虚拟位置失效"、"偶发启动即被停"）。
  - **启用态周期校验**：每 5s 校验双 provider 启用状态（系统位置开启前提下）——系统位置总开关往返后部分 ROM 把已注册 test provider 留在禁用态，注入不抛异常但无人消费（"运行中却不生效"，隔夜进程重启才恢复）；发现禁用即重新启用，provider 被注销（IAE）则复位注册状态走重注册。
- `FloatingControlService`：前台服务 `foregroundServiceType="specialUse"`，在**虚拟位置运行中且悬浮窗开关开启**时运行（停止注入即随服务消失；注入服务被系统杀死后仍可经长按恢复启动），持有悬浮按钮，最小化通知（静音渠道）。
- 设备重启后**不自动恢复**虚拟位置（不引入开机广播权限）；主页保留选中点显示，开关置关。
- **可接受的停止/恢复路径**（防"虚拟位置一直无法使用"的兜底手段）：
  - **杀后台**（系统回收、一键清理、划卡移除）：前台服务随进程终止，虚拟位置即停——这是有效的紧急停止手段。**下次打开 App 自动恢复**：清理残留 TestProvider 后按持久化的选中点重启注入并提示"曾被系统中断，已自动恢复运行"；无选中点或启动失败回落为校正开关为关（并提示）。
  - **悬浮球长按 2 秒**：立即停止虚拟位置（服务直停 + TestProvider 兜底清理），悬浮按钮随之消失；再长按可重新启动（显式停止写 mockEnabled=false，不会被自动恢复覆盖）。
  - 两者均为有效恢复手段：任何"卡死不生效"的状态，杀后台重开（自动恢复到可用状态）或长按停止后重新开启即可。

### 3.6 悬浮窗（单图标按钮）
- 一个图标按钮，`TYPE_APPLICATION_OVERLAY`（flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`），全局可拖动（rawX/rawY 差值 + `updateViewLayout`）。
- **长按 ≥2 秒切换虚拟位置启/关**：长按期间按钮显示进度环（0–2s）；手指移动超过 `touchSlop` 判定为拖动并取消长按计时。

### 3.7 数据存储
- **Room**（KSP）`PointEntity`：
  ```
  id: Long (PK, 自增)
  name: String
  wgsLat / wgsLng: Double     // 主坐标，WGS84
  bdLat / bdLng: Double       // 冗余存储，供百度侧直接用
  isFavorite: Boolean
  createdAt: Long             // 毫秒时间戳
  lastUsedAt: Long
  ```
  - 保存时按 WGS84 坐标（4 位小数，约 11m 精度）去重：已存在则刷新 `lastUsedAt`（收藏操作只改 `isFavorite`，不新建条目）。
- **DataStore Preferences**：地图源、注入频率、悬浮窗开关、隐私同意状态、当前选中点快照（冗余存坐标，防止 Room 条目被删）+ 启用状态标志。

### 3.8 隐私政策与定位 SDK
- 首次启动弹隐私政策（本地内置文案）：**同意** → 允许懒初始化百度 SDK（`SDKInitializer.setAgreePrivacy(true)` + `LocationClient.setAgreePrivacy(true)` + `SDKInitializer.initialize` + `setCoordType(BD09LL)`），地图可用百度源并显示真实位置蓝点 + 「回到当前位置」；**不同意** → 百度 SDK 完全不初始化（百度地图与百度定位均不可用，地图源强制 osmdroid），无真实定位，初始视角用默认城市中心。
- 政策文案必含条款：① 权限与地图数据用途（不上传位置）；② 数据仅存本机；③ **代码 90% 以上由 AI 生成**的来源声明；④ **虚拟位置风险与免责**（可被识别/可能违反平台条款/影响真实定位功能，一切后果使用者自负，开发者免责）；⑤ **使用限制**（仅供开发者测试调试与学习，勿滥用、勿分发/二次打包/商用，违规引发法律问题保留追究一切法律责任的权利）。
- 百度初始化必须**懒加载**（不能在 Application 无条件初始化）：同意后按 DataStore 持久化状态/当次选择初始化，且必须在首个百度 MapView 创建之前完成。
- 任何时候：有上次选点 → 初始视角为上次选点；否则按上述隐私分支。

## 4. 模块化架构（组件化）

```
:app                  壳：Application、MainActivity、NavHost、AppContainer(手写DI)、百度Key配置
:core:common          坐标转换 CoordUtils、权限/系统设置跳转工具
:core:data            Room + DataStore + Repository + 领域模型
:core:ui              主题 + 通用组件（空态、确认弹窗、坐标文本）
:map:api              MapAdapter 接口（WGS84 出入）、地图源枚举
:map:baidu            百度实现（jar/so 放本模块 libs，内部做 BD09 转换）
:map:osm              osmdroid 实现
:service:mock         MockLocationService、FloatingControlService、悬浮窗视图、启停控制器
:feature:home         主页
:feature:map          地图选点页（仅依赖 :map:api，不依赖具体实现）
:feature:settings     设置页
build-logic           轻量 convention plugins（android-library / compose-feature）
```

- 依赖规则：feature 模块之间不互相依赖；`:feature:*` → `:core:*` + `:map:api`（home/settings 额外允许 → `:service:mock` 使用启停控制器）；`:app` 负责组装，把具体 `MapAdapter` 实现注入 `:feature:map`。
- 手写 DI（AppContainer），不引入 Hilt；Room 使用 KSP（版本须匹配 Kotlin 2.0.21）。
- 新增依赖：navigation-compose、lifecycle-runtime-compose/viewmodel-compose（顺带升级现有 2.6.1）、room(runtime/ktx/compiler)、kotlinx-coroutines-android、datastore-preferences、osmdroid-android（Maven Central，无需新增仓库）。
- 百度 jar/so 复制到 `:map:baidu/libs`：`implementation files(...)` + `jniLibs.srcDirs`；`abiFilters "arm64-v8a"`（**仅 64 位，x86_64 模拟器请切 osmdroid 源**）；proguard：`-keep class com.baidu.** {*;}`。

## 5. 百度 Key 配置与安全

- Key 存 `local.properties`（键 `BAIDU_MAP_KEY`，已被 .gitignore 排除）→ 各构建读取后经 `:app` 的 `manifestPlaceholders` 注入 manifest `<meta-data com.baidu.lbsapi.API_KEY>`；源码与版本库零硬编码。
- release 开启混淆。**文档如实说明：客户端无法绝对防逆向**，此方案防的是源码泄露与仓库泄露；如需更强保护后续可上 NDK 加固（不在本期范围）。
- 占位 Key 期间（未申请）：百度瓦片不显示、定位不可用，属预期，代码逻辑可正常跑通；设置页切 osmdroid 可完整体验。
- Key 申请（用户后续操作）：百度开放平台 → 创建应用 → 类型 Android SDK → 填新包名 `com.chan.location` + 调试/发布 SHA1。

## 6. 移植清单（Gogogo → 新项目）

| 来源（`E:\Code\fromGit\android\Gogogo\app\src\main\...`） | 去向 | 说明 |
|---|---|---|
| `libs/BaiduLBS_Android.jar` + `libs/arm64-v8a/*.so` | `:map:baidu/libs` | SDK 本体，原样复制 |
| `java/com/zcshou/utils/MapUtils.java` | `:core:common` CoordUtils.kt | 全部转换函数：bd2wgs/wgs2bd09/bd09togcj02/gcj02towgs84，纯数学移植 |
| `java/com/zcshou/service/ServiceGo.java` | `:service:mock` MockLocationService.kt | TestProvider 注册、循环注入、Location 字段填充、前台通知；频率改为读设置 |
| `java/com/zcshou/utils/GoUtils.java` | `:core:common` 权限工具 | `isAllowMockLocation`（试探法）、各类系统设置跳转 |
| `java/com/zcshou/gogogo/GoApplication.java` | 参考 | 百度隐私 + 初始化顺序（改为懒加载） |
| `java/com/zcshou/gogogo/MainActivity.java` 的 `startGoLocation/doGoLocation` | 参考 | 启停时序、校验链顺序 |
| `java/com/zcshou/joystick/JoyStick.java` 的窗口管理部分 | 参考 | WindowManager 参数、拖动实现（按钮与长按逻辑为全新实现） |

## 7. 实施顺序

1. 建 `doc/`（本文档与 ARCHITECTURE.md）。
2. 搭 `build-logic` + 全部空模块与依赖关系，`:app` 可编译。
3. Gradle 细节：新增依赖、百度 jar/so、abiFilters、proguard、app_name 改「虚拟定位」。
4. Manifest：最小权限、两个 service、百度 meta-data（占位 Key）与 `com.baidu.location.f` 声明。
5. `:core:common` → `:core:data` → `:core:ui`。
6. `:service:mock`（注入服务、悬浮窗、启停控制器）。
7. `:map:api` / `:map:baidu` / `:map:osm` / `:feature:map`。
8. `:feature:home`、`:feature:settings`、首启隐私弹窗（`:app` 组装）。
9. `gradlew assembleDebug` 全模块通过 + merged manifest 权限核对。
10. 补全 `doc/ARCHITECTURE.md` 的「模块归纳」章节。

## 8. 验收清单

- [ ] 首启隐私弹窗：同意 → 百度源可用 + 真实位置蓝点；不同意 → 强制 osmdroid + 默认北京视角。
- [ ] 主页：空态文案与「添加点位」按钮；两个 Tab 列表；点击项立即启用；星标/删除可用。
- [ ] 权限链：拒位置权限可再申请；模拟位置应用未选 → 弹窗跳开发者设置；GPS 未开 → 跳位置设置。
- [ ] 地图页：拖动十字选点、缩放、经纬度输入跳转（含非法输入校验、坐标系选择）、条目定位跳转（确认使用/仅查看）、主页条目经纬度点击复制、锁定、保存/收藏确认、退出四选一弹窗；不确认不落库。
- [ ] 服务：开关 ON 出现前台通知（显示坐标）；设置页频率 10–100ms 步进 10 生效（改后无需重启服务，下次注入即用新值或重启注入循环）。
- [ ] 悬浮窗：全局拖动；长按 2s 进度环后启/关；拖动不误触长按。
- [ ] 坐标准确性：地图锁定某明显地标，用其他地图 App 对比无偏移（验证 WGS84/BD09 转换）。
- [ ] 权限核对：merged manifest 无 `READ_PHONE_STATE` 等无关权限。
- [ ] `gradlew assembleDebug` 全部模块编译通过；仅中文文案。

### 定位与隐私回归

- [ ] 运行中连续使用不同历史点位，GPS 与 Network 均继续注入，无真实位置跳回。
- [ ] 百度打开历史点位后首次蓝点回调不覆盖中心；点击「我的位置」后蓝点与中心重合。
- [ ] GPS 注册成功、Network 注册失败时立即清理两个 provider；重试耗尽或中途停止后真实定位恢复。
- [ ] 地图页按 Home 或进入系统设置时暂停蓝点定位，返回后恢复。
- [ ] 检查合并 manifest 的 allowBackup=false，旧版备份及新版云备份/设备迁移规则排除点位数据库与设置文件。
- [ ] `:core:common:testDebugUnitTest` 与 `:service:mock:testDebugUnitTest` 通过权限探测隔离和注册回滚测试。

## 9. 风险与边界

| 风险 | 说明与对策 |
|---|---|
| 百度 Key 未配置 | 瓦片空白/定位失败属预期；逻辑可跑通；申请后填 `local.properties` 即生效 |
| 百度 so 仅 arm64-v8a | x86_64 模拟器上百度源不可用，切 osmdroid；真机基本均为 arm64 |
| targetSdk 34+ FGS 限制 | location 型前台服务启动前必须已授予位置权限，校验链已覆盖 |
| 注入频率过低（10ms） | 高频循环耗电增加；默认 100ms，UI 有说明文案 |
| 重启不恢复 | 不引入开机广播（权限最小化），重启后开关置关，选中点保留 |
