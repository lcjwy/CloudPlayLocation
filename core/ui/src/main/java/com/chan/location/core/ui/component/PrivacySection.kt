package com.chan.location.core.ui.component

/** 隐私政策分段（本地内置）。:app 首启弹窗与 :feature:settings 复查共用 */
data class PrivacySection(
    val title: String,
    val body: String,
    /** 重点段（免责/法律条款）：正文加粗加大显示 */
    val highlighted: Boolean = false,
)

val PrivacySections =
    listOf(
        PrivacySection(
            title = "1. 工作机制",
            body = "本应用基于 Android 系统「模拟位置信息」机制工作，需要在开发者选项中将本应用选为「模拟位置信息应用」。",
        ),
        PrivacySection(
            title = "2. 位置权限用途",
            body = "本应用请求定位权限仅用于在地图上展示「我的位置」蓝点，方便选点参考。应用不会上传、存储或共享您的真实位置。",
        ),
        PrivacySection(
            title = "3. 地图数据",
            body =
                "百度地图源：地图瓦片与逆地理编码由百度地图服务提供，使用时会向百度服务器发起网络请求，" +
                    "百度按其隐私政策处理相关数据。\n" +
                    "开源地图源（OSM）：瓦片由 OpenStreetMap 服务器提供。\n" +
                    "选择「不同意」隐私政策时，应用不会初始化百度地图相关组件，地图自动使用开源地图源。",
        ),
        PrivacySection(
            title = "4. 数据存储",
            body = "所有点位数据仅保存在本机数据库中，可随时删除；应用不包含任何数据上传功能。",
        ),
        PrivacySection(
            title = "5. 代码来源声明",
            body =
                "本应用 90% 以上的代码由人工智能（AI）自动生成，虽通过构建与静态检查验证，" +
                    "但未经逐行人工审查，可能存在缺陷或未知行为。",
            highlighted = true,
        ),
        PrivacySection(
            title = "6. 虚拟位置风险与免责声明",
            body =
                "本应用通过系统机制修改设备对外报告的地理位置，该行为可能被其他应用或平台识别，" +
                    "可能违反相关应用或平台的服务条款，并可能影响依赖真实定位的功能（如导航、出行、考勤、风控核验等）。\n" +
                    "因使用本应用产生的一切直接或间接后果（包括但不限于账号处置、财产损失、数据异常及法律责任），" +
                    "均由使用者自行承担，开发者不承担任何责任。",
            highlighted = true,
        ),
        PrivacySection(
            title = "7. 使用限制",
            body =
                "本应用仅供开发者测试、调试与学习用途，请遵守当地法律法规。\n" +
                    "请勿滥用本应用，请勿擅自分发、二次打包或用于商业用途。\n" +
                    "如因使用者违规使用引发法律问题，开发者保留依法追究使用者一切法律责任的权利。",
            highlighted = true,
        ),
    )
