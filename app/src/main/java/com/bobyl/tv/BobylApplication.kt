package com.bobyl.tv

import android.app.Application
import com.bobyl.tv.data.database.AppStorage

class BobylApplication : Application() {
    internal val storage by lazy { AppStorage(this) }
    override fun onCreate() {
        super.onCreate()
        storage.start()
    }
}
