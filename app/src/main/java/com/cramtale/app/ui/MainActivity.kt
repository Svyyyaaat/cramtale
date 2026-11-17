package com.cramtale.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cramtale.app.AppState
import com.cramtale.app.CramtaleApp
import com.cramtale.app.anki.AnkiTarget

enum class Screen(val title: String) { HOME("Cramtale"), TARGET("AnkiDroid"), PROBE("Anki probe"), DECK("Your deck"), CALIBRATE("Calibrate") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = (application as CramtaleApp).state
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    // bumped when the target changes, so the banner re-reads it
                    var version by remember { mutableIntStateOf(0) }
                    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                    Column(Modifier.safeDrawingPadding()) {
                        TargetBanner(state, version)
                        when (screen) {
                            Screen.HOME -> HomeScreen(state) { screen = it }
                            Screen.TARGET -> TargetScreen(state) { version++ }
                            Screen.PROBE -> ProbeScreen(state)
                            Screen.DECK -> DeckScreen(state)
                            Screen.CALIBRATE -> CalibrateScreen(state)
                        }
                    }
                }
            }
        }
    }
}

/** Always visible: which collection the app is connected to (PLAN.md §4.6). */
@Composable
fun TargetBanner(state: AppState, version: Int) {
    val t = remember(version) { state.target }
    val (text, color) = when {
        t == null -> "Not connected to AnkiDroid" to Color(0xFF757575)
        t.isReal -> "REAL COLLECTION · read-only · ${t.packageName}" to Color(0xFFC62828)
        else -> "COPY · read-only · ${t.packageName}" to Color(0xFF2E7D32)
    }
    Text(
        text, color = Color.White, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

internal fun isRealTarget(t: AnkiTarget?) = t?.isReal == true
