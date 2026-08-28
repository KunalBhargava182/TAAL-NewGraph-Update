package com.musediagnostics.taal.app

import android.app.Application
import com.musediagnostics.taal.app.data.db.TaalDatabase

class TaalApplication : Application() {

    val database: TaalDatabase by lazy {
        TaalDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: TaalApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
