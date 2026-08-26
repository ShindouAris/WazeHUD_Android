package com.chisadin.hudwz.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

internal val Context.hudDataStore by preferencesDataStore(name = "hud_preferences")
