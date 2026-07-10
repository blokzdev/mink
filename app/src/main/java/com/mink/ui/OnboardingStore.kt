package com.mink.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mink_onboarding",
)

/**
 * Persists whether the user has seen the onboarding pager. Backed by
 * Preferences DataStore so the choice survives restarts and reads reactively.
 */
object OnboardingStore {
    private val SEEN = booleanPreferencesKey("seen")

    fun seenFlow(context: Context): Flow<Boolean> =
        context.applicationContext.onboardingDataStore.data.map { prefs ->
            prefs[SEEN] ?: false
        }

    suspend fun markSeen(context: Context) {
        context.applicationContext.onboardingDataStore.edit { prefs ->
            prefs[SEEN] = true
        }
    }
}
