package com.chisadin.hudwz

import android.app.Application
import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.data.ProfileRepository
import com.chisadin.hudwz.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

import com.chisadin.hudwz.bubble.BubbleManager
import kotlinx.coroutines.launch

class HudApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        BubbleManager.installAutoHide(this)
        container.applicationScope.launch {
            container.settingsRepository.settings.collect { settings ->
                BubbleManager.sync(
                    this@HudApplication,
                    enabled = settings.bubbleEnabled,
                    layout = settings.bubbleLayout,
                    size = settings.bubbleSize,
                )
            }
        }
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(application)
    val profileRepository = ProfileRepository(application)
    val hudRepository = HudRepository()
}
