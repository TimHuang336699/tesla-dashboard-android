package com.tesla.dashboard.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.databinding.ActivityDashboardBinding
import com.tesla.dashboard.service.TripRecordingService
import com.tesla.dashboard.ui.history.HistoryActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dashboard 主界面 Activity
 *
 * 以横屏全屏沉浸式模式展示车辆实时数据仪表盘,包括:
 * - 速度(大号居中显示)
 * - 电量/续航(左侧)
 * - 温度(右侧)
 * - 档位/GPS状态(顶部)
 * - 里程/G力/位置(底部)
 * - Tesla 连接状态
 *
 * ## 沉浸式全屏
 * - 使用 [WindowInsetsControllerCompat] 隐藏状态栏和导航栏
 * - 设置 [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] 保持屏幕常亮
 * - 配合 themes.xml 中的全屏主题实现完全沉浸式体验
 *
 * ## ViewBinding
 * 通过 build.gradle.kts 中启用的 viewBinding = true,
 * 自动生成 [ActivityDashboardBinding] 供类型安全地访问布局视图。
 *
 * ## Hilt
 * @AndroidEntryPoint 使 Hilt 能在此 Activity 中进行依赖注入,
 * ViewModel 通过 by viewModels() 委托自动获取。
 *
 * ## 数据观察
 * 使用 [repeatOnLifecycle] 在 STARTED 状态下安全收集 StateFlow,
 * 避免 Activity 不可见时浪费资源。
 */
@AndroidEntryPoint
class DashboardActivity : AppCompatActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivityDashboardBinding

    /** Dashboard ViewModel,由 Hilt 自动提供 */
    private val viewModel: DashboardViewModel by viewModels()

    /**
     * Activity 创建入口
     *
     * 执行顺序:
     * 1. 配置全屏沉浸式窗口
     * 2. 初始化 ViewBinding
     * 3. 设置按钮点击监听
     * 4. 开始观察 ViewModel 数据
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全屏沉浸式配置
        setupImmersiveMode()

        // 2. 初始化 ViewBinding
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置按钮
        setupButtons()

        // 4. 观察数据
        observeViewModel()
    }

    /**
     * 配置全屏沉浸式模式
     *
     * - [WindowCompat.setDecorFitsSystemWindows](false): 内容延伸到系统栏区域
     * - [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]: 屏幕常亮(仪表盘场景必需)
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
     * 设置按钮点击监听
     *
     * - startButton: 调用 [DashboardViewModel.startTracking]
     * - stopButton: 调用 [DashboardViewModel.stopTracking]
     * - historyButton: 跳转到历史行程页面
     */
    private fun setupButtons() {
        binding.startButton.setOnClickListener {
            viewModel.startTracking()
        }

        binding.stopButton.setOnClickListener {
            viewModel.stopTracking()
        }

        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    /**
     * 观察 ViewModel 的 StateFlow
     *
     * 使用 [repeatOnLifecycle] 在 Activity STARTED 时开始收集,
     * 在 STOPPED 时自动取消,避免后台浪费资源。
     *
     * 同时收集两个流:
     * - [DashboardViewModel.vehicleData]: 车辆实时数据,更新 UI
     * - [DashboardViewModel.isTracking]: 跟踪状态,更新按钮和前台服务
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 收集车辆数据
                launch {
                    viewModel.vehicleData.collect { data ->
                        updateUI(data)
                    }
                }

                // 收集跟踪状态
                launch {
                    viewModel.isTracking.collect { isTracking ->
                        updateTrackingUI(isTracking)
                    }
                }
            }
        }
    }

    /**
     * 根据车辆实时数据更新所有 UI 元素
     *
     * 每次车辆数据流发射新值时调用,更新仪表盘上的所有数值显示。
     * 对于可能为 null 的 Tesla API 字段,使用 "--" 占位符。
     *
     * @param data 最新的车辆数据
     */
    private fun updateUI(data: VehicleData) {
        // ===== 顶部栏 =====

        // 档位(P/R/N/D),Tesla 未连接时显示 "--"
        binding.gearText.text = data.gear ?: "--"

        // GPS 状态
        binding.gpsStatusText.text = if (data.isGpsLocked) {
            getString(com.tesla.dashboard.R.string.gps_locked)
        } else {
            getString(com.tesla.dashboard.R.string.gps_searching)
        }
        // GPS 锁定时使用绿色,搜索中使用橙色
        binding.gpsStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (data.isGpsLocked) com.tesla.dashboard.R.color.accent_green
                else com.tesla.dashboard.R.color.accent_orange,
            ),
        )

        // ===== 中部 - 速度(大号居中) =====
        binding.speedText.text = data.speed.toInt().toString()

        // ===== 中部左侧 - 电量/续航 =====
        binding.socText.text = data.batterySOC?.let { "$it%" } ?: "--"
        binding.rangeText.text = data.batteryRange?.let { "${it.toInt()} km" } ?: "--"

        // ===== 中部右侧 - 温度 =====
        binding.tempText.text = formatTemperature(
            data.insideTemp,
            data.outsideTemp,
        )

        // ===== 底部 - 里程/G力/位置 =====

        // 本次行程里程
        binding.tripDistText.text = String.format("%.1f km", data.tripDistance)

        // 总里程表(Tesla API 字段,可能为 null)
        binding.odoText.text = data.odometer?.let { "${it.toInt()} km" } ?: "--"

        // G 力值
        binding.gForceText.text = String.format("%.2f G", data.gForce)

        // 经纬度(GPS 未锁定时显示 "--")
        binding.latText.text = if (data.isGpsLocked) {
            String.format("%.5f", data.latitude)
        } else {
            "--"
        }
        binding.lonText.text = if (data.isGpsLocked) {
            String.format("%.5f", data.longitude)
        } else {
            "--"
        }

        // 航向角
        binding.headingText.text = if (data.isGpsLocked) {
            "${data.heading.toInt()}°"
        } else {
            "--"
        }

        // ===== Tesla 连接状态 =====
        binding.teslaStatusText.text = if (data.isTeslaConnected) {
            getString(com.tesla.dashboard.R.string.tesla_connected)
        } else {
            getString(com.tesla.dashboard.R.string.tesla_disconnected)
        }
        binding.teslaStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (data.isTeslaConnected) com.tesla.dashboard.R.color.accent_green
                else com.tesla.dashboard.R.color.accent_orange,
            ),
        )
    }

    /**
     * 格式化温度显示
     *
     * 显示格式: "内 22° / 外 28°"
     * - 两个温度都有值时显示完整格式
     * - 仅有一个时显示对应部分
     * - 都没有时显示 "--"
     *
     * @param inside 车内温度(°C),null = 不可用
     * @param outside 车外温度(°C),null = 不可用
     * @return 格式化后的温度字符串
     */
    private fun formatTemperature(inside: Float?, outside: Float?): String {
        val insideStr = inside?.let { "${it.toInt()}°" }
        val outsideStr = outside?.let { "${it.toInt()}°" }

        return when {
            insideStr != null && outsideStr != null -> "$insideStr / $outsideStr"
            insideStr != null -> insideStr
            outsideStr != null -> outsideStr
            else -> "--"
        }
    }

    /**
     * 根据跟踪状态更新 UI 和前台服务
     *
     * - 更新 START/STOP 按钮的启用状态和透明度
     * - 启动或停止 [TripRecordingService] 前台服务
     *
     * @param isTracking 是否正在跟踪
     */
    private fun updateTrackingUI(isTracking: Boolean) {
        // 按钮状态: 跟踪中禁用 START、启用 STOP;反之亦然
        binding.startButton.isEnabled = !isTracking
        binding.stopButton.isEnabled = isTracking

        // 透明度反馈
        binding.startButton.alpha = if (isTracking) 0.4f else 1.0f
        binding.stopButton.alpha = if (isTracking) 1.0f else 0.4f

        // 前台服务: 跟踪时启动保活,停止时关闭
        if (isTracking) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TripRecordingService::class.java),
            )
        } else {
            stopService(Intent(this, TripRecordingService::class.java))
        }
    }
}
