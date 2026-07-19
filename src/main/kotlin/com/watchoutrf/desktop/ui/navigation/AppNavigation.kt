package com.watchoutrf.desktop.ui.navigation

import androidx.compose.runtime.*
import com.watchoutrf.desktop.ui.screens.home.HomeScreen
import com.watchoutrf.desktop.ui.screens.home.HomeViewModel
import com.watchoutrf.desktop.ui.screens.spectrum.SpectrumScreen
import com.watchoutrf.desktop.ui.screens.spectrum.SpectrumViewModel

enum class Screen {
    Home, Spectrum
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    
    // We will share ViewModels for now in this simple desktop state setup
    val homeViewModel = remember { HomeViewModel() }
    val spectrumViewModel = remember { SpectrumViewModel() }

    when (currentScreen) {
        Screen.Home -> {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSpectrum = { currentScreen = Screen.Spectrum }
            )
        }
        Screen.Spectrum -> {
            SpectrumScreen(
                viewModel = spectrumViewModel,
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
    }
}
