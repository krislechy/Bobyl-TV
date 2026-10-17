package com.bobyl.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try { com.bobyl.tv.data.CustomSubtitles(this@MainActivity).ensureDirectory() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                android.widget.Toast.makeText(this@MainActivity,
                    "Папка субтитров недоступна. Проверьте доступ к файлам в настройках Бобыля.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.bobyl.tv.data.PageCache.configure(getSharedPreferences("${packageName}_preferences", MODE_PRIVATE).getInt("cacheMinutes", 60))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { SkeletonLoadingTheme { BobylTvApp() } }
    }
}
