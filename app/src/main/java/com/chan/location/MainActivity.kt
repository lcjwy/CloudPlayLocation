package com.chan.location

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chan.location.core.data.PointRepository
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.ui.component.PrivacyPolicyDialog
import com.chan.location.core.ui.theme.LocationTheme
import com.chan.location.feature.home.HomeScreen
import com.chan.location.feature.map.MapScreen
import com.chan.location.feature.settings.SettingsScreen
import com.chan.location.map.api.MapAdapterFactory
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
    const val MAP = "map?pid={pid}"
    const val SETTINGS = "settings"

    /** 打开地图（不带定位点）；带占位符的 MAP 不可直接用于 navigate */
    const val MAP_PLAIN = "map"

    /** 携带点位 id 打开地图：定位到该点并确认是否启用 */
    fun mapWithPoint(pid: Long): String = "map?pid=$pid"
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
        PrivacyPolicyDialog(
            onAgree = { scope.launch { settings.setPrivacyAgreed(true) } },
            onDecline = { scope.launch { settings.setPrivacyAgreed(false) } },
            onDismiss = null,
        )
        return
    }

    val navController = rememberNavController()
    AppNavHost(navController, settings, points, mapAdapterFactory)
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    settings: SettingsRepository,
    points: PointRepository,
    mapAdapterFactory: MapAdapterFactory,
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                pointRepository = points,
                settingsRepository = settings,
                onAddPoint = { navController.navigate(Routes.MAP_PLAIN) },
                onOpenMap = { navController.navigate(Routes.MAP_PLAIN) },
                onLocateOnMap = { navController.navigate(Routes.mapWithPoint(it.id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.MAP,
            arguments =
                listOf(
                    navArgument("pid") {
                        type = NavType.StringType
                        nullable = true
                    },
                ),
        ) { entry ->
            MapScreen(
                pointRepository = points,
                settingsRepository = settings,
                mapAdapterFactory = mapAdapterFactory,
                onExit = { navController.popBackStack() },
                focusPointId = entry.arguments?.getString("pid")?.toLongOrNull(),
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
