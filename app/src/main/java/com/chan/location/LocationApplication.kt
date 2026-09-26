package com.chan.location

import android.app.Application
import com.chan.location.di.appModule
import com.chan.location.service.mock.MockLocationManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class LocationApplication :
    Application(),
    KoinComponent {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@LocationApplication)
            modules(appModule)
        }
        MockLocationManager.init(this, get())
    }
}
