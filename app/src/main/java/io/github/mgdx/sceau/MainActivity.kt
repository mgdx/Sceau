package io.github.mgdx.sceau

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.mgdx.sceau.session.SessionViewModel
import io.github.mgdx.sceau.ui.SceauNavHost
import io.github.mgdx.sceau.ui.theme.SceauTheme

class MainActivity : ComponentActivity() {
    private val session: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val trustStoreRepository = (application as SceauApplication).trustStoreRepository
        setContent {
            SceauTheme {
                SceauNavHost(
                    session = session,
                    trustStoreRepository = trustStoreRepository,
                )
            }
        }
    }
}
