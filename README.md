# 云游

> 身不动，而行千里。

**云游**是一款 Android 地图选点式虚拟定位工具：在地图上选定任意位置，通过系统「模拟位置信息」机制（Mock Location）持续注入该坐标，使设备对外报告的位置变为所选点。**免 Root、无 Xposed**，仅需在开发者选项中将本应用选为「模拟位置信息应用」。

> ⚠️ **使用限制**：本应用仅供开发者测试、调试与学习用途。虚拟定位可能违反目标应用/平台的服务条款，因使用产生的一切后果由使用者自行承担。完整声明见应用内「隐私政策」（首次启动强制展示）。

## 功能特性

- **双地图源选点**：百度地图 / 开源地图（osmdroid），交互一致，屏幕中心十字准星选点
- **虚拟位置启停**：首页卡片一键启停，运行中通知实时显示坐标
- **历史 / 收藏**：自动记录使用过的点位，支持收藏、多选批量删除、坐标复制
- **悬浮球控制**：全局悬浮按钮，长按 2 秒切换启停，可拖动
- **精度调节**：注入频率 10–100ms 可调；息屏自动降频省电
- **比例尺**：地图实时比例尺；入场统一约 200m 级视角并居中目标点
- **反地理编码命名**：保存点位自动反查地名（百度反地理编码 → 系统 Geocoder 双层兜底）
- **WiFi 闪回防护**：检测并引导关闭系统 WiFi 扫描类定位，防止虚拟位置被真实位置覆盖

## 技术栈

| 项 | 选型 |
|---|---|
| 构建 | AGP 9.0.1 / Gradle 9.2.1 / Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3，仅中文 |
| 架构 | 11 模块组件化（app 壳 + core/map/service/feature），手写 Koin 装配 |
| 持久化 | Room（点位）+ DataStore（设置与选中点快照） |
| 地图 | 百度地图 SDK（BD09 显示）/ osmdroid（WGS84 直通），接口抽象于 `map:api` |
| 兼容 | minSdk 26 / targetSdk 36，百度 so 仅 arm64-v8a |

## 构建与出包

```bash
# 调试包（自动复制下划线命名副本）
./gradlew assembleDebug
# 正式包一键出包（签名 + 混淆 + 项目级 build 目录副本）
./gradlew release
# 静态检查与测试
./gradlew ktlintFormat detekt :core:common:testDebugUnitTest
```

- APK 命名：`云游_版本_年月日_时分秒_构建类型.apk`，输出于项目级 `build/outputs/named/`。
- **百度 Key**：申请后写入 `local.properties`（已被 gitignore）的 `BAIDU_MAP_KEY`，经 manifestPlaceholders 注入，源码零硬编码；未配置时百度瓦片空白，可切换 OSM 源使用。
- **签名**：凭据同样存于 `local.properties`（`STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`），密库文件（*.jks）不入库；缺省时 debug 回退系统默认签名。
- x86_64 模拟器请切换「开源地图 (OSM)」源（百度 so 仅有 arm64）。

## 目录结构

```
:app              壳：MainActivity/导航/DI 装配/百度 Key 注入/出包任务
:core:common      坐标转换（WGS84↔GCJ02↔BD09）、权限与系统跳转、模拟权限探测
:core:data        Room + DataStore + Repository
:core:ui          主题与通用组件（启停校验闸门、隐私弹窗、空态等）
:map:api          MapAdapter 抽象（一律 WGS84 出入）
:map:baidu        百度实现（反地理编码 + 地图 + 蓝点）
:map:osm          osmdroid 实现
:service:mock     注入前台服务 / 悬浮窗服务 / 启停状态机 MockLocationManager
:feature:home     首页（卡片/历史/收藏/多选）
:feature:map      地图选点页（准星/锁定/保存/比例尺）
:feature:settings 设置页
build-logic       convention 插件（library / library+compose）
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [doc/REQUIREMENTS.md](doc/REQUIREMENTS.md) | 需求基准（功能/权限/交互细则） |
| [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md) | 架构与核心逻辑（依赖图/注入循环/坐标链路/模块归纳） |
| [doc/FEATURES.md](doc/FEATURES.md) | 功能模块文档（按模块的功能点、行为边界与代码入口） |

## 版本

版本号维护于 `app/build.gradle.kts`（`appVersionName` / `versionCode`），当前 **1.2.4 (7)**。
