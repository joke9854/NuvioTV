package com.nuvio.tv.data.floppy

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Persists one snapshot payload per profile. A save writes items and cursor as one value. */
interface FloppySyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

@Singleton
class AndroidFloppySyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : FloppySyncStorage {
    override suspend fun load(profileId: Int): String? =
        factory.get(profileId, FEATURE).data.first()[SNAPSHOT]

    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw CancellationException("Floppy profile removed")
            preferences[SNAPSHOT] = payload
        }
    }

    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            preferences.remove(SNAPSHOT)
        }
    }

    private companion object {
        const val FEATURE = "floppy_sync"
        val SNAPSHOT = stringPreferencesKey("snapshot")
    }
}
