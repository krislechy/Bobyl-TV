package com.bobyl.tv.data.database

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
abstract class RoomTestBase {
    protected lateinit var context: Context
    internal lateinit var database: BobylDatabase
    private lateinit var databaseName: String
    protected fun io(block: suspend () -> Unit): Unit = runBlocking { withContext(Dispatchers.IO) { block() } }
    @Before fun openDatabase() = io {
        context = RuntimeEnvironment.getApplication()
        databaseName = "isolated-${UUID.randomUUID()}.db"
        database = BobylDatabase.build(context, databaseName)
        database.openHelper.writableDatabase
        Unit
    }
    protected fun reopen() {
        database.close()
        database = BobylDatabase.build(context, databaseName)
        database.openHelper.writableDatabase
    }
    @After fun closeDatabase() = io {
        database.close()
        context.deleteDatabase(databaseName)
        Unit
    }
}
