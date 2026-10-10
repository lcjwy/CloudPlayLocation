package com.chan.location

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
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
        // 百度定位 SDK 声明了 :remote 子进程服务，其进程创建同样走本 Application：
        // 不区分进程会让 MockLocationManager 在子进程内再次初始化——子进程里服务
        // 静态 isAlive 恒为 false，init 误判"服务已死"回滚运行开关并 Toast，随后
        // 子进程的 reconcile 收集器跨进程 stopService 杀掉真服务、清理 TestProvider
        // （表现为进入地图页后 Toast"已自动停止"、虚拟位置随即失效）
        if (!isMainProcess()) return
        startKoin {
            androidLogger()
            androidContext(this@LocationApplication)
            modules(appModule)
        }
        MockLocationManager.init(this, get())
    }

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // API 26–27 无 Application.getProcessName，经 ActivityManager 反查
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
                ?: packageName
        }
}
