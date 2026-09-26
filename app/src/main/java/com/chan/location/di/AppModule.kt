package com.chan.location.di

import com.chan.location.core.common.MapSource
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.data.buildPointRepository
import com.chan.location.map.api.MapAdapterFactory
import com.chan.location.map.baidu.BaiduMapAdapter
import com.chan.location.map.baidu.BaiduSdkInitializer
import com.chan.location.map.osm.OsmMapAdapter
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** 壳工程 DI 装配：core/map 实现保持框架无关，仅在 :app 交接给 Koin */
val appModule =
    module {
        single { SettingsRepository(androidContext()) }
        single { buildPointRepository(androidContext()) }
        // 地图实现选择：百度源要求已同意隐私且 SDK 初始化成功，否则回退 osmdroid
        single {
            MapAdapterFactory { context, config ->
                val useBaidu =
                    config.source == MapSource.BAIDU &&
                        config.privacyAgreed &&
                        BaiduSdkInitializer.ensureInit(
                            context.applicationContext,
                            config.privacyAgreed,
                        )
                if (useBaidu) BaiduMapAdapter(context, config) else OsmMapAdapter(context, config)
            }
        }
    }
