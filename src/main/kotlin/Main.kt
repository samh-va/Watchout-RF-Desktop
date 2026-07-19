package com.watchoutrf.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.watchoutrf.desktop.ui.navigation.AppNavigation
import com.watchoutrf.desktop.ui.theme.WatchoutRFTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Watchout RF Desktop",
        state = androidx.compose.ui.window.rememberWindowState(
            width = 1680.dp,
            height = 1050.dp
        )
    ) {
        WatchoutRFTheme {
            AppNavigation()
        }
    }
}
