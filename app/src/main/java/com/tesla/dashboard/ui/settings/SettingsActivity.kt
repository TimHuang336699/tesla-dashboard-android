package com.tesla.dashboard.ui.settings

import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.data.source.tesla.TeslaApiProvider
import com.tesla.dashboard.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 电池车型选项数据类
 *
 * @property displayName 用户可见的车型显示名称
 * @property code 车型代码,对应 [com.tesla.dashboard.data.model.BatteryConfig] 中的 key
 */
private data class BatteryModelOption(val displayName: String, val code: String)

/**
 * 设置页面 Activity — 苹果式简约设计
 *
 * 以横屏全屏沉浸式模式展示 Tesla Dashboard 的配置项,包括:
 * - Tesla API 配置(VIN / Access Token / 区域)
 * - 车辆信息(车型选择,用于电池容量查询)
 * - 外观(主题模式选择)
 *
 * ## 沉浸式全屏
 * 与 [com.tesla.dashboard.ui.dashboard.DashboardActivity] 一致,
 * 使用 [WindowInsetsControllerCompat] 隐藏状态栏和导航栏,保持屏幕常亮。
 *
 * ## ViewBinding
 * 通过 build.gradle.kts 中启用的 viewBinding = true,
 * 自动生成 [ActivitySettingsBinding] 供类型安全地访问布局视图。
 *
 * ## Hilt
 * @AndroidEntryPoint 使 Hilt 能在此 Activity 中进行依赖注入:
 * - ViewModel 通过 by viewModels() 委托自动获取
 * - [TeslaApiProvider] 通过 @Inject 字段注入,用于保存时直接同步 VIN/Token
 *
 * ## 数据流
 * 使用 [repeatOnLifecycle] 在 STARTED 状态下安全收集 [SettingsViewModel.uiState],
 * 首次收到已持久化的设置值时填充表单,后续由用户手动编辑,
 * 点击保存按钮后统一提交所有修改。
 *
 * @property teslaApiProvider Tesla API 数据源,保存时直接同步 VIN/Token
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivitySettingsBinding

    /** 设置页面 ViewModel,由 Hilt 自动提供 */
    private val viewModel: SettingsViewModel by viewModels()

    /** Tesla API Provider,由 Hilt 字段注入,用于保存时直接同步 VIN/Token */
    @Inject
    lateinit var teslaApiProvider: TeslaApiProvider

    /**
     * 表单是否已填充标记
     *
     * 首次从 DataStore 加载到已保存的设置值时填充表单并置为 true,
     * 防止后续 Flow 发射覆盖用户正在编辑的内容。
     * Activity 重建时重置为 false。
     */
    private var isFormPopulated = false

    /**
     * 当前选中的车型代码
     *
     * 当用户从下拉菜单选择车型时更新,保存时使用此值。
     */
    private var selectedBatteryModel: String = ""

    /**
     * 可选车型列表(显示名称 ↔ 车型代码)
     *
     * 涵盖 Tesla 全系车型的各电池容量版本,
     * 代码与 [com.tesla.dashboard.data.model.BatteryConfig] 中的 key 一一对应。
     */
    private val batteryModelOptions = listOf(
        BatteryModelOption("Model S 75", "model_s_75"),
        BatteryModelOption("Model S 85", "model_s_85"),
        BatteryModelOption("Model S 90", "model_s_90"),
        BatteryModelOption("Model S 100", "model_s_100"),
        BatteryModelOption("Model S Plaid", "model_s_plaid"),
        BatteryModelOption("Model 3 标准版", "model_3_standard"),
        BatteryModelOption("Model 3 长续航", "model_3_long_range"),
        BatteryModelOption("Model 3 Performance", "model_3_performance"),
        BatteryModelOption("Model X 75", "model_x_75"),
        BatteryModelOption("Model X 90", "model_x_90"),
        BatteryModelOption("Model X 100", "model_x_100"),
        BatteryModelOption("Model X Plaid", "model_x_plaid"),
        BatteryModelOption("Model Y 标准版", "model_y_standard"),
        BatteryModelOption("Model Y 长续航", "model_y_long_range"),
        BatteryModelOption("Model Y Performance", "model_y_performance"),
        BatteryModelOption("Cybertruck 双电机", "cybertruck_dual"),
        BatteryModelOption("Cybertruck 三电机", "cybertruck_tri"),
    )

    /**
     * Activity 创建入口
     *
     * 执行顺序:
     * 1. 配置全屏沉浸式窗口
     * 2. 初始化 ViewBinding
     * 3. 设置车型下拉菜单
     * 4. 设置按钮和返回监听
     * 5. 开始观察 ViewModel 数据
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全屏沉浸式配置(与 Dashboard 一致)
        setupImmersiveMode()

        // 2. 初始化 ViewBinding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置车型下拉菜单
        setupBatteryModelDropdown()

        // 4. 设置按钮和返回监听
        setupClickListeners()

        // 5. 观察数据
        observeViewModel()
    }

    /**
     * 配置全屏沉浸式模式
     *
     * - [WindowCompat.setDecorFitsSystemWindows](false): 内容延伸到系统栏区域
     * - [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]: 屏幕常亮
     * - [WindowInsetsControllerCompat.hide]: 隐藏状态栏和导航栏
     * - [BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]: 滑动边缘时短暂显示系统栏后自动隐藏
     */
    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 设置车型下拉菜单(AutoCompleteTextView + ExposedDropdownMenu)
     *
     * 使用 ArrayAdapter 填充车型显示名称列表,
     * 并设置选中监听以跟踪当前选中的车型代码。
     */
    private fun setupBatteryModelDropdown() {
        val displayNames = batteryModelOptions.map { it.displayName }
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            displayNames,
        )
        binding.actvBatteryModel.setAdapter(adapter)

        // 选中监听: 记录对应的车型代码
        binding.actvBatteryModel.setOnItemClickListener { _, _, position, _ ->
            selectedBatteryModel = batteryModelOptions[position].code
        }
    }

    /**
     * 设置按钮点击监听
     *
     * - btnBack: 返回(finish)
     * - btnSave: 保存所有设置并关闭页面
     * - btnTestConnection: 使用当前输入的 VIN/Token 测试 Tesla API 连接
     */
    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        // 测试连接按钮
        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }
    }

    /**
     * 观察 ViewModel 的 UI 状态流
     *
     * 使用 [repeatOnLifecycle] 在 Activity STARTED 时开始收集,
     * 在 STOPPED 时自动取消。
     *
     * 首次收到已持久化的设置值时填充表单([isFormPopulated] 标记防止重复填充),
     * 后续发射不覆盖用户编辑。
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!isFormPopulated) {
                        populateForm(state)
                        isFormPopulated = true
                    }
                }
            }
        }
    }

    /**
     * 用已保存的设置值填充表单
     *
     * 从 [SettingsUiState] 读取各设置项,更新对应的 UI 控件:
     * - VIN / Token: 设置输入框文本
     * - 区域: 选中对应的 RadioButton
     * - 主题: 选中对应的 RadioButton
     * - 车型: 在下拉菜单中选中对应项
     *
     * @param state 设置 UI 状态
     */
    private fun populateForm(state: SettingsUiState) {
        // VIN
        binding.etVin.setText(state.vin)

        // Access Token
        binding.etAccessToken.setText(state.accessToken)

        // 区域
        when (state.region) {
            "cn" -> binding.rbRegionCn.isChecked = true
            "global" -> binding.rbRegionGlobal.isChecked = true
            "eu" -> binding.rbRegionEu.isChecked = true
            else -> binding.rbRegionCn.isChecked = true
        }

        // 主题
        when (state.themeMode) {
            "dark" -> binding.rbThemeDark.isChecked = true
            "light" -> binding.rbThemeLight.isChecked = true
            "system" -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        // 车型: 查找对应的显示名称并设置(不触发过滤)
        selectedBatteryModel = state.batteryModel
        val option = batteryModelOptions.find { it.code == state.batteryModel }
        if (option != null) {
            binding.actvBatteryModel.setText(option.displayName, false)
        }
    }

    /**
     * 保存所有设置
     *
     * 从表单读取所有输入值,调用 ViewModel 的 save 系列方法持久化,
     * 同时直接同步 VIN/Token 到 [TeslaApiProvider] 确保立即可用。
     * 保存完成后显示 Toast 提示并关闭页面。
     */
    private fun saveSettings() {
        // 读取表单值
        val vin = binding.etVin.text.toString()
        val token = binding.etAccessToken.text.toString()
        val region = getSelectedRegion()
        val themeMode = getSelectedThemeMode()
        val batteryModel = selectedBatteryModel

        // 通过 ViewModel 持久化(ViewModel 内部会同步 TeslaApiProvider)
        viewModel.saveVin(vin)
        viewModel.saveAccessToken(token)
        viewModel.saveRegion(region)
        viewModel.saveThemeMode(themeMode)
        viewModel.saveBatteryModel(batteryModel)

        // 直接同步 VIN/Token 到 TeslaApiProvider(双重保险,确保立即生效)
        teslaApiProvider.vin = vin.trim().ifBlank { null }
        teslaApiProvider.accessToken = token.trim().ifBlank { null }

        // 提示并关闭
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 测试 Tesla API 连接
     *
     * 使用当前输入框中的 VIN 和 Token(非已保存值),
     * 调用 ViewModel.testConnection 发起一次 API 请求,
     * 根据结果显示成功或失败的 Toast。
     *
     * 测试期间禁用按钮防止重复点击。
     */
    private fun testConnection() {
        val vin = binding.etVin.text.toString()
        val token = binding.etAccessToken.text.toString()

        // 禁用按钮,防止测试期间重复点击
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "…"

        viewModel.testConnection(vin, token) { success ->
            // 回到主线程更新 UI
            runOnUiThread {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.setText(R.string.settings_test_connection)

                val messageId = if (success) {
                    R.string.settings_connection_success
                } else {
                    R.string.settings_connection_failed
                }
                Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 获取当前选中的区域代码
     *
     * @return "cn" / "global" / "eu"
     */
    private fun getSelectedRegion(): String = when (binding.rgRegion.checkedRadioButtonId) {
        R.id.rbRegionCn -> "cn"
        R.id.rbRegionGlobal -> "global"
        R.id.rbRegionEu -> "eu"
        else -> "cn"
    }

    /**
     * 获取当前选中的主题模式
     *
     * @return "dark" / "light" / "system"
     */
    private fun getSelectedThemeMode(): String = when (binding.rgTheme.checkedRadioButtonId) {
        R.id.rbThemeDark -> "dark"
        R.id.rbThemeLight -> "light"
        R.id.rbThemeSystem -> "system"
        else -> "system"
    }
}
