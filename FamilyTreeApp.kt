package com.familytree.app

import android.app.Application
import android.provider.Settings
import com.familytree.app.data.FamilyTreeDatabase
import com.familytree.app.data.FamilyTreeRepository

class FamilyTreeApp : Application() {

    lateinit var repository: FamilyTreeRepository
        private set

    /** Stable per-device identifier, stamped onto records this device creates. */
    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        val db = FamilyTreeDatabase.getInstance(this)
        repository = FamilyTreeRepository(db.personDao(), db.relationshipDao())
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }
}
