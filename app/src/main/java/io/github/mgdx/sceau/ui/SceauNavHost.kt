package io.github.mgdx.sceau.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.trust.TrustStoreRepository
import io.github.mgdx.sceau.ui.about.AboutScreen
import io.github.mgdx.sceau.ui.home.HomeScreen
import io.github.mgdx.sceau.ui.learn.ChipContentsScreen
import io.github.mgdx.sceau.ui.learn.IntroPreferences
import io.github.mgdx.sceau.ui.learn.IntroScreen
import io.github.mgdx.sceau.ui.reading.ReadingScreen
import io.github.mgdx.sceau.ui.result.ResultScreen
import io.github.mgdx.sceau.ui.trust.TrustStoreScreen

/** Routes de navigation de l'application. */
object Routes {
    const val HOME = "home"
    const val READING = "reading"
    const val RESULT = "result"
    const val TRUST = "trust"
    const val ABOUT = "about"
    const val INTRO = "intro"
    const val CHIP_CONTENTS = "chip_contents"
}

@Composable
fun SceauNavHost(
    session: SessionViewModel,
    trustStoreRepository: TrustStoreRepository,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val introPreferences = remember(context) { IntroPreferences(context) }
    // Introduction au premier lancement seulement (D35). Après une rotation ou la mort du
    // processus, la pile restaurée prime sur la destination de départ.
    val startDestination = remember(introPreferences) { if (introPreferences.isIntroSeen) Routes.HOME else Routes.INTRO }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            HomeScreen(
                session = session,
                onRead = { navController.navigate(Routes.READING) { launchSingleTop = true } },
                onOpenTrustStore = { navController.navigate(Routes.TRUST) { launchSingleTop = true } },
                onOpenAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
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
                onReplayIntro = { navController.navigate(Routes.INTRO) { launchSingleTop = true } },
                onOpenChipContents = { navController.navigate(Routes.CHIP_CONTENTS) { launchSingleTop = true } },
            )
        }
        composable(Routes.INTRO) { entry ->
            IntroScreen(
                onFinish = {
                    // Garde contre un double appui : un second appel dépilerait l'écran suivant.
                    if (entry.isResumed()) {
                        introPreferences.markIntroSeen()
                        if (navController.previousBackStackEntry != null) {
                            // Rejouée depuis À propos : retour à À propos.
                            navController.popBackStack()
                        } else {
                            // Premier lancement : l'accueil remplace l'introduction.
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.INTRO) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                },
                onOpenChipContents = { navController.navigate(Routes.CHIP_CONTENTS) { launchSingleTop = true } },
            )
        }
        composable(Routes.CHIP_CONTENTS) { entry ->
            ChipContentsScreen(onBack = { if (entry.isResumed()) navController.popBackStack() })
        }
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState == Lifecycle.State.RESUMED
