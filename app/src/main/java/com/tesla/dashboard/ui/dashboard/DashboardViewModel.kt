package com.tesla.dashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.TripRepository
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.repository.VehicleDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard ViewModel
 *
 * 作为 UI 层与数据层之间的桥梁,负责:
 * 1. 暴露车辆实时数据流 [vehicleData] 供 UI 观察更新
 * 2. 管理 tracking 状态 [isTracking],协调数据源启停与行程记录
 * 3. 在行程进行中跟踪最高速度等统计信息
 *
 * ## 依赖注入
 * 通过 Hilt @HiltViewModel + @Inject constructor 自动注入:
 * - [VehicleDataRepository]: 车辆实时数据仓库(GNSS / Sensor / Tesla API 合并)
 * - [TripRepository]: 行程记录仓库(Room 数据库)
 *
 * ## StateFlow 设计
 * - [vehicleData]: 将 Repository 的 Flow 转换为 StateFlow,使用 WhileSubscribed(5000)
 *   策略,在 UI 不可见 5 秒后停止上游收集,节省电量
 * - [isTracking]: 使用 MutableStateFlow 管理跟踪状态,通过 asStateFlow() 暴露只读引用
 *
 * @param vehicleDataRepository 车辆数据仓库
 * @param tripRepository 行程记录仓库
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vehicleDataRepository: VehicleDataRepository,
    private val tripRepository: TripRepository,
) : ViewModel() {

    /**
     * 车辆实时数据流
     *
     * 从 [VehicleDataRepository.observeVehicleData] 获取合并后的 Flow,
     * 转换为 StateFlow 供 UI 层安全观察。
     *
     * - [SharingStarted.WhileSubscribed(5000)]: 当有订阅者时开始收集,
     *   最后一个订阅者取消后延迟 5 秒停止,避免配置变更时频繁重启数据流
     * - [VehicleData()]: 初始值使用默认空数据,UI 首次渲染时显示占位符
     */
    val vehicleData: StateFlow<VehicleData> = vehicleDataRepository.observeVehicleData()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = VehicleData(),
        )

    /**
     * 是否正在跟踪/记录行程
     *
     * - true: 数据源已启动,行程正在记录
     * - false: 数据源已停止,无活动行程
     *
     * UI 通过观察此状态控制按钮启用/禁用及前台服务启停。
     */
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // ===== 行程记录状态(仅在 tracking 期间有效) =====

    /** 当前行程的数据库 ID(null = 无活动行程) */
    private var currentTripId: Long? = null

    /** 行程开始时间(epoch millis) */
    private var tripStartTime: Long = 0L

    /** 行程开始时的 GPS 纬度 */
    private var tripStartLat: Double = 0.0

    /** 行程开始时的 GPS 经度 */
    private var tripStartLon: Double = 0.0

    /** 行程开始时的电量百分比(null = Tesla 未连接) */
    private var tripStartSOC: Int? = null

    /** 行程期间最高速度 km/h */
    private var tripMaxSpeed: Float = 0f

    /**
     * 初始化 — 仅设置内部观察,不自动启动数据源或行程记录。
     *
     * 在 init 中启动一个协程观察 [vehicleData],用于:
     * - 在行程进行中持续跟踪最高速度(tripMaxSpeed)
     *
     * 注意: 数据源(GNSS / Sensor / Tesla API)不会在 init 中启动,
     * 需等用户点击 START 按钮后调用 [startTracking] 才会启动。
     */
    init {
        viewModelScope.launch {
            vehicleData.collect { data ->
                // 仅在 tracking 期间更新最高速度
                if (_isTracking.value && data.speed > tripMaxSpeed) {
                    tripMaxSpeed = data.speed
                }
            }
        }
    }

    /**
     * 开始跟踪/记录行程
     *
     * 执行步骤:
     * 1. 启动车辆数据源(GNSS + Sensor + Tesla API)
     * 2. 获取当前位置和电量作为行程起点
     * 3. 在数据库创建新行程记录,保存返回的 tripId
     * 4. 更新 [isTracking] 为 true
     *
     * 若已经在 tracking 状态则直接返回,避免重复启动。
     * 异常时保持 isTracking=false,数据源可能已部分启动(由 Repository 内部处理回滚)。
     */
    fun startTracking() {
        if (_isTracking.value) return

        viewModelScope.launch {
            try {
                // 1. 启动数据源
                vehicleDataRepository.start()

                // 2. 获取当前数据快照作为行程起点
                val currentData = vehicleDataRepository.getPrevData() ?: VehicleData()
                tripStartTime = System.currentTimeMillis()
                tripStartLat = currentData.latitude
                tripStartLon = currentData.longitude
                tripStartSOC = currentData.batterySOC
                tripMaxSpeed = 0f

                // 3. 创建行程记录
                currentTripId = tripRepository.startTrip(
                    startTime = tripStartTime,
                    startLat = tripStartLat,
                    startLon = tripStartLon,
                    startSOC = tripStartSOC,
                )

                // 4. 更新状态
                _isTracking.value = true
            } catch (e: Exception) {
                // 启动失败,确保状态一致
                currentTripId = null
                _isTracking.value = false
            }
        }
    }

    /**
     * 停止跟踪/记录行程
     *
     * 执行步骤:
     * 1. 停止车辆数据源
     * 2. 获取当前位置和电量作为行程终点
     * 3. 计算行程统计(时长、平均速度)
     * 4. 更新数据库中的行程记录(endTrip)
     * 5. 更新 [isTracking] 为 false
     *
     * 若不在 tracking 状态则直接返回。
     * 异常时仍将 isTracking 设为 false,保证 UI 可恢复。
     */
    fun stopTracking() {
        if (!_isTracking.value) return

        viewModelScope.launch {
            try {
                // 1. 停止数据源
                vehicleDataRepository.stop()

                // 2. 获取终点数据
                val currentData = vehicleDataRepository.getPrevData() ?: VehicleData()
                val endTime = System.currentTimeMillis()
                val tripId = currentTripId

                // 3. 计算行程统计
                val durationSec = (endTime - tripStartTime) / 1000L
                val distanceKm = currentData.tripDistance
                val avgSpeed = if (durationSec > 0L) {
                    distanceKm / (durationSec / 3600f)
                } else {
                    0f
                }

                // 4. 更新行程记录
                if (tripId != null) {
                    tripRepository.endTrip(
                        tripId = tripId,
                        endTime = endTime,
                        endLat = currentData.latitude,
                        endLon = currentData.longitude,
                        distanceKm = distanceKm,
                        durationSec = durationSec,
                        avgSpeed = avgSpeed,
                        maxSpeed = tripMaxSpeed,
                        // TODO: 根据 tripStartSOC 和当前 SOC 计算平均电耗
                        avgConsumption = null,
                        endSOC = currentData.batterySOC,
                        // TODO: 序列化行程期间的 GPS 轨迹点
                        trackPointsJson = "[]",
                    )
                }
            } catch (e: Exception) {
                // 即使出错也要更新状态,保证 UI 可恢复
            } finally {
                // 5. 重置状态
                currentTripId = null
                _isTracking.value = false
            }
        }
    }

    /**
     * ViewModel 清理时确保数据源停止。
     *
     * 若用户在 tracking 期间直接退出应用(ViewModel 被销毁),
     * 尝试停止数据源以释放资源。
     */
    override fun onCleared() {
        super.onCleared()
        if (_isTracking.value) {
            // viewModelScope 此时即将取消,使用 GlobalScope 确保停止操作能执行
            kotlinx.coroutines.GlobalScope.launch {
                vehicleDataRepository.stop()
            }
        }
    }
}
