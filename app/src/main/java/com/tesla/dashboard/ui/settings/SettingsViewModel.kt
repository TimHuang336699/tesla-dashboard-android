package com.tesla.dashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.SettingsRepository
import com.tesla.dashboard.data.source.tesla.TeslaApiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面 UI 状态数据类
 *
 * 包含设置页面所有配置项的当前值,由 [SettingsViewModel] 通过
 * 合并 [SettingsRepository] 的多个 Flow 生成。
 *
 * @property vin Tesla 车辆识别号,空字符串表示未设置
 * @property accessToken Tesla API 访问令牌,空字符串表示未设置
 * @property region API 区域代码:"cn"(中国区) / "global"(全球) / "eu"(欧洲)
 * @property themeMode 主题模式:"dark"(深色) / "light"(浅色) / "system"(跟随系统)
 * @property batteryModel 车型代码(如 "model_3_long_range"),空字符串表示未设置
 */
data class SettingsUiState(
    val vin: String = "",
    val accessToken: String = "",
    val region: String = SettingsRepository.DEFAULT_REGION,
    val themeMode: String = SettingsRepository.DEFAULT_THEME_MODE,
    val batteryModel: String = "",
)

/**
 * 设置页面 ViewModel
 *
 * 作为设置页面 UI 层与数据层([SettingsRepository])之间的桥梁,负责:
 * 1. 暴露 [uiState] StateFlow,合并所有设置项供 UI 观察并填充表单
 * 2. 提供 save 系列方法,将用户修改持久化到 DataStore
 * 3. 当 VIN 或 Token 更新时,同步更新 [TeslaApiProvider] 的运行时属性,
 *    使 Tesla API 数据源能立即使用新的凭据进行轮询
 * 4. 提供 [testConnection] 方法,用指定 VIN/Token 测试 Tesla API 连接
 *
 * ## 依赖注入
 * 通过 Hilt @HiltViewModel + @Inject constructor 自动注入:
 * - [SettingsRepository]: 设置持久化仓库(DataStore)
 * - [TeslaApiProvider]: Tesla API 数据源,用于同步 VIN/Token
 *
 * ## TeslaApiProvider 同步策略
 * - [init] 中启动协程收集 vin/token Flow,首次发射时同步初始值到 TeslaApiProvider
 * - [saveVin] / [saveAccessToken] 中额外直接同步,确保保存后立即可用
 *
 * @param settingsRepository 设置仓库
 * @param teslaApiProvider Tesla API 数据源 Provider
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val teslaApiProvider: TeslaApiProvider,
) : ViewModel() {

    /**
     * 设置页面合并后的 UI 状态流
     *
     * 使用 [combine] 合并 [SettingsRepository] 的 5 个设置 Flow,
     * 任一设置项变化时重新发射完整的 [SettingsUiState]。
     *
     * - [SharingStarted.WhileSubscribed(5000)]: 有订阅者时收集,
     *   最后一个订阅者取消后延迟 5 秒停止,避免配置变更时频繁重启
     * - 初始值使用 [SettingsUiState] 默认值,UI 首次渲染时显示空表单
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.vinFlow,
        settingsRepository.accessTokenFlow,
        settingsRepository.regionFlow,
        settingsRepository.themeModeFlow,
        settingsRepository.batteryModelFlow,
    ) { vin, accessToken, region, themeMode, batteryModel ->
        SettingsUiState(
            vin = vin,
            accessToken = accessToken,
            region = region,
            themeMode = themeMode,
            batteryModel = batteryModel,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    /**
     * 初始化 — 同步已持久化的 VIN/Token 到 TeslaApiProvider
     *
     * 在 ViewModel 创建时启动两个协程:
     * - 收集 vinFlow,首次发射时将已保存的 VIN 同步到 [TeslaApiProvider.vin]
     * - 收集 accessTokenFlow,首次发射时将已保存的 Token 同步到 [TeslaApiProvider.accessToken]
     *
     * 这样即使应用启动后用户未进入设置页面,TeslaApiProvider 也能使用上次保存的凭据。
     * 后续每次保存新值时,[saveVin] / [saveAccessToken] 会再次同步。
     */
    init {
        viewModelScope.launch {
            settingsRepository.vinFlow.collect { vin ->
                teslaApiProvider.vin = vin.ifBlank { null }
            }
        }
        viewModelScope.launch {
            settingsRepository.accessTokenFlow.collect { token ->
                teslaApiProvider.accessToken = token.ifBlank { null }
            }
        }
    }

    /**
     * 保存 VIN 并同步到 TeslaApiProvider
     *
     * @param vin 用户输入的车辆识别号
     */
    fun saveVin(vin: String) {
        viewModelScope.launch {
            settingsRepository.saveVin(vin)
            // 立即同步到 TeslaApiProvider,无需等待 Flow 回流
            teslaApiProvider.vin = vin.trim().ifBlank { null }
        }
    }

    /**
     * 保存 Access Token 并同步到 TeslaApiProvider
     *
     * @param token 用户输入的 API 访问令牌
     */
    fun saveAccessToken(token: String) {
        viewModelScope.launch {
            settingsRepository.saveAccessToken(token)
            // 立即同步到 TeslaApiProvider,无需等待 Flow 回流
            teslaApiProvider.accessToken = token.trim().ifBlank { null }
        }
    }

    /**
     * 保存 API 区域
     *
     * @param region 区域代码:"cn" / "global" / "eu"
     */
    fun saveRegion(region: String) {
        viewModelScope.launch {
            settingsRepository.saveRegion(region)
        }
    }

    /**
     * 保存主题模式
     *
     * @param themeMode 主题模式:"dark" / "light" / "system"
     */
    fun saveThemeMode(themeMode: String) {
        viewModelScope.launch {
            settingsRepository.saveThemeMode(themeMode)
        }
    }

    /**
     * 保存车型代码
     *
     * @param batteryModel 车型代码(对应 BatteryConfig 中的 key)
     */
    fun saveBatteryModel(batteryModel: String) {
        viewModelScope.launch {
            settingsRepository.saveBatteryModel(batteryModel)
        }
    }

    /**
     * 测试 Tesla API 连接
     *
     * 使用指定的 VIN 和 Token 临时设置到 [TeslaApiProvider],
     * 然后收集 [TeslaApiProvider.observeData] 的首次发射,
     * 根据返回数据中的 isTeslaConnected 字段判断连接是否成功。
     *
     * 测试完成后,结果通过 [onResult] 回调返回到 UI 层(避免在 ViewModel 中直接操作 UI)。
     *
     * @param vin 待测试的 VIN
     * @param token 待测试的 Access Token
     * @param onResult 结果回调,true = 连接成功,false = 连接失败
     */
    fun testConnection(
        vin: String,
        token: String,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                // 临时设置凭据
                teslaApiProvider.vin = vin.trim().ifBlank { null }
                teslaApiProvider.accessToken = token.trim().ifBlank { null }

                // 收集首次发射(observeData 会立即发起一次 API 请求)
                val firstData = teslaApiProvider.observeData().first()
                onResult(firstData.isTeslaConnected)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }
}
