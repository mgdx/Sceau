package io.github.mgdx.sceau.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.about.AboutScreen
import io.github.mgdx.sceau.ui.home.HomeScreen
import io.github.mgdx.sceau.ui.reading.ReadingScreen
import io.github.mgdx.sceau.ui.result.ResultScreen
import io.github.mgdx.sceau.ui.scan.MrzScanScreen
import io.github.mgdx.sceau.ui.trust.TrustStoreScreen

/** Routes de navigation de l'application. */
object Routes {
    const val HOME = "home"
    const val READING = "reading"
    const val RESULT = "result"
    const val TRUST = "trust"
    const val ABOUT = "about"
    const val SCAN = "scan"
}

@Composable
fun SceauNavHost(
    session: SessionViewModel,
    trustStoreRepository: TrustStoreRepository,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                session = session,
                onRead = { navController.navigate(Routes.READING) { launchSingleTop = true } },
                onOpenTrustStore = { navController.navigate(Routes.TRUST) { launchSingleTop = true } },
                onOpenAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
                onScanMrz = { navController.navigate(Routes.SCAN) { launchSingleTop = true } },
            )
        }
        composable(Routes.READING) {
            ReadingScreen(
                session = session,
                onDone = {
                    navController.navigate(Routes.RESULT) {
                        popUpTo(Routes.READING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCancel = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                session = session,
                onClear = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.TRUST) {
            TrustStoreScreen(
                repository = trustStoreRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                repository = trustStoreRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SCAN) {
            MrzScanScreen(
                session = session,
                // Sans effet si l'écran a déjà été quitté (retour pendant l'annonce du succès).
                onDone = { navController.popBackStack(Routes.SCAN, inclusive = true) },
            )
        }
    }
}
