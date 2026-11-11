package com.nuvio.tv.data.floppy

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidFloppyAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : FloppyAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "floppy_auth", "com.nuvio.tv.floppy.credentials.v1")
    private val installPreferences = context.getSharedPreferences("floppy_install", Context.MODE_PRIVATE)

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
    override fun readInstallId(): String? = installPreferences.getString("install_id", null)
    override fun writeInstallId(value: String) {
        installPreferences.edit().putString("install_id", value).commit()
    }
}
