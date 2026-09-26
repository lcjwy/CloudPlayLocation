package com.chan.location

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chan.location.core.data.PointRepository
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.ui.theme.LocationTheme
import com.chan.location.feature.home.HomeScreen
import com.chan.location.feature.map.MapScreen
import com.chan.location.feature.settings.SettingsScreen
import com.chan.location.map.api.MapAdapterFactory
import com.chan.location.ui.PrivacyDialog
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settingsRepository: SettingsRepository by inject()
    private val pointRepository: PointRepository by inject()
    private val mapAdapterFactory: MapAdapterFactory by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocationTheme {
                AppRoot(settingsRepository, pointRepository, mapAdapterFactory)
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val MAP = "map"
    const val SETTINGS = "settings"
}

@Composable
private fun AppRoot(
    settings: SettingsRepository,
    points: PointRepository,
    mapAdapterFactory: MapAdapterFactory,
) {
    val scope = rememberCoroutineScope()

    // null = 用户尚未选择隐私政策；initialValue=true 仅用于避免首帧闪现弹窗
    val privacy by settings.privacyAgreed.collectAsStateWithLifecycle(initialValue = true)

    if (privacy == null) {
        PrivacyDialog(
            onAgree = { scope.launch { settings.setPrivacyAgreed(true) } },
            onDecline = { scope.launch { settings.setPrivacyAgreed(false) } },
        )
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                pointRepository = points,
                settingsRepository = settings,
                onAddPoint = { navController.navigate(Routes.MAP) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.MAP) {
            MapScreen(
                pointRepository = points,
                settingsRepository = settings,
                mapAdapterFactory = mapAdapterFactory,
                onExit = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsRepository = settings,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
