package com.chisadin.hudwz

import android.app.Application
import com.chisadin.hudwz.data.HudRepository
import com.chisadin.hudwz.data.ProfileRepository
import com.chisadin.hudwz.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HudApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(application)
    val profileRepository = ProfileRepository(application)
    val hudRepository = HudRepository()
}
