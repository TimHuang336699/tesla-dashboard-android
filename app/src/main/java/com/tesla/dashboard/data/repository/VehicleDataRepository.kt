package com.tesla.dashboard.data.repository

import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 车辆数据统一仓库
 *
 * 负责将三个数据源(GNSS / Sensor / Tesla API)的 Flow 合并为统一的 [VehicleData] 流。
 *
 * ## 合并策略
 * - 以 GNSS 数据为基底(车速 / 位置 / 航向 / 海拔 / 里程 / 精度)
 * - 叠加 Sensor 数据(纵向加速度 / 横向加速度 / G 力)
 * - 叠加 Tesla API 数据(电量 / 续航 / 温度 / 档位 / 里程表) — 仅当 isTeslaConnected=true 时取用,
 *   否则对应字段保持 null,避免展示过期或无效的车辆状态
 *
 * ## 数据刷新频率
 * - GNSS / Sensor: 亚秒级实时更新(本地传感器,无网络延迟)
 * - Tesla API: 10-30s 轮询(变化缓慢,秒级延迟可接受)
 *
 * ## 电耗计算
 * 内部维护 [prevData] 缓存,保存上一次合并后的车辆数据。
 * 调用方可通过 [getPrevData] 获取缓存值,配合 [VehicleData.computeConsumption] 计算瞬时电耗。
 *
 * 使用 Hilt @Singleton 保证全局单例。
 *
 * @param gnssProvider GNSS 数据源(车速/位置/航向/海拔/里程)
 * @param sensorProvider 加速度传感器数据源(纵向/横向加速度/G力)
 * @param teslaApiProvider Tesla API 数据源(电量/温度/档位/里程表)
 */
@Singleton
class VehicleDataRepository @Inject constructor(
    @Named("gnss") private val gnssProvider: VehicleDataSource,
    @Named("sensor") private val sensorProvider: VehicleDataSource,
    @Named("tesla") private val teslaApiProvider: VehicleDataSource,
) {

    /**
     * 上一次合并后的车辆数据缓存,用于电耗计算(瞬时/区间)。
     * 使用 @Volatile 保证多线程可见性。
     */
    @Volatile
    private var prevData: VehicleData? = null

    /**
     * 观察合并后的车辆实时数据流
     *
     * 使用 [combine] 合并三个 Provider 的 Flow。任一 Provider 发出新数据时,
     * 都会基于三者最新值重新合并并发射。
     *
     * 注意: 各 Provider 的 observeData() 应发射初始值(如默认 [VehicleData]),
     * 否则 combine 会等待所有 Provider 首次发射后才产出数据。
     * 建议各 Provider 内部使用 MutableStateFlow 并以默认值初始化。
     *
     * @return 合并后的 [VehicleData] Flow
     */
    fun observeVehicleData(): Flow<VehicleData> = combine(
        gnssProvider.observeData(),
        sensorProvider.observeData(),
        teslaApiProvider.observeData(),
    ) { gnss, sensor, tesla ->
        // 以 GNSS 为基底,叠加 Sensor 和 Tesla 数据
        val merged = gnss.copy(
            // ===== Sensor 字段(直接取用) =====
            accelLongitudinal = sensor.accelLongitudinal,
            accelLateral = sensor.accelLateral,
            gForce = sensor.gForce,

            // ===== Tesla API 字段(仅当已连接时取用,否则保持 null) =====
            batterySOC = if (tesla.isTeslaConnected) tesla.batterySOC else null,
            batteryRange = if (tesla.isTeslaConnected) tesla.batteryRange else null,
            insideTemp = if (tesla.isTeslaConnected) tesla.insideTemp else null,
            outsideTemp = if (tesla.isTeslaConnected) tesla.outsideTemp else null,
            gear = if (tesla.isTeslaConnected) tesla.gear else null,
            odometer = if (tesla.isTeslaConnected) tesla.odometer else null,
            isTeslaConnected = tesla.isTeslaConnected,
        )

        // 更新 prevData 缓存,供后续电耗计算使用
        prevData = merged

        merged
    }

    /**
     * 获取上一次合并后的车辆数据(缓存值)
     *
     * 可用于电耗计算:配合 [VehicleData.computeConsumption] 方法,
     * 传入 prevData 和里程增量即可计算瞬时电耗 kWh/100km。
     *
     * @return 上一次的车辆数据,若尚未收到任何数据则返回 null
     */
    fun getPrevData(): VehicleData? = prevData

    // ===== 单源数据获取(可选) =====

    /**
     * 单独获取 GNSS 数据流
     * @return GNSS Provider 的原始数据流(仅含 GNSS 字段)
     */
    fun getGnssData(): Flow<VehicleData> = gnssProvider.observeData()

    /**
     * 单独获取 Sensor 数据流
     * @return Sensor Provider 的原始数据流(仅含加速度/G力字段)
     */
    fun getSensorData(): Flow<VehicleData> = sensorProvider.observeData()

    /**
     * 单独获取 Tesla API 数据流
     * @return Tesla API Provider 的原始数据流(仅含电量/温度/档位字段)
     */
    fun getTeslaData(): Flow<VehicleData> = teslaApiProvider.observeData()

    // ===== 生命周期控制 =====

    /**
     * 启动所有数据源
     *
     * 按顺序启动 GNSS → Sensor → Tesla API。
     * 若某个 Provider 启动失败会抛出异常,已启动的 Provider 不会被自动回滚,
     * 调用方应自行处理异常并调用 [stop] 清理资源。
     */
    suspend fun start() {
        gnssProvider.start()
        sensorProvider.start()
        teslaApiProvider.start()
    }

    /**
     * 停止所有数据源
     *
     * 按顺序停止 GNSS → Sensor → Tesla API,释放各 Provider 占用的资源。
     * 即使某个 Provider 停止失败,仍会尝试停止后续 Provider。
     */
    suspend fun stop() {
        gnssProvider.stop()
        sensorProvider.stop()
        teslaApiProvider.stop()
    }
}
