package com.tesla.dashboard.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tesla.dashboard.data.local.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题管理器
 *
 * 负责日夜主题切换逻辑，监听 SettingsRepository 中的主题模式设置，
 * 并通过 [AppCompatDelegate] 应用对应的日/夜模式。
 *
 * ## 主题模式
 * - "dark": 强制深色模式
 * - "light": 强制浅色模式
 * - "system": 跟随系统设置(默认)
 *
 * ## 使用方式
 * 在 Application onCreate 中注入并调用 [observeTheme] 开始监听。
 * UI 层可通过 [isDarkMode] 判断当前模式，调整自定义 View 配色。
 *
 * @param context 应用上下文
 * @param settingsRepository 设置仓库
 */
@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用级协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前是否暗色模式(供 UI 层读取) */
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    /**
     * 开始监听主题设置并自动切换
     *
     * 在 Application.onCreate 中调用。
     */
    fun observeTheme() {
        scope.launch {
            settingsRepository.themeModeFlow.collect { mode ->
                applyTheme(mode)
            }
        }
    }

    /**
     * 应用主题模式
     *
     * @param mode 主题模式字符串 ("dark" / "light" / "system")
     */
    private fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "dark" -> {
                _isDarkMode.value = true
                AppCompatDelegate.MODE_NIGHT_YES
            }
            "light" -> {
                _isDarkMode.value = false
                AppCompatDelegate.MODE_NIGHT_NO
            }
            else -> {
                // system — 根据当前系统设置判断
                val currentNightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                _isDarkMode.value = currentNightMode ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
