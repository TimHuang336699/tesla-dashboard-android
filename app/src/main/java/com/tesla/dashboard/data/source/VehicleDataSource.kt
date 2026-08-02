package com.tesla.dashboard.data.source

import com.tesla.dashboard.data.model.VehicleData
import kotlinx.coroutines.flow.Flow

/**
 * 车辆数据源统一接口
 *
 * 三个实现:
 * - GnssProvider:     车速/位置/航向/海拔/里程(实时,必选)
 * - SensorProvider:   加速度/G力(实时,必选)
 * - TeslaApiProvider: 电量/温度/档位(低频轮询,可选)
 *
 * 每个 Provider 只负责自己能获取的数据字段,其余保持默认值。
 * VehicleDataRepository 负责将多个 Provider 的数据合并为统一的 VehicleData。
 */
interface VehicleDataSource {

    /**
     * 获取实时数据流
     * 返回的 VehicleData 中,只有该 Provider 负责的字段有值,其余为默认值
     */
    fun observeData(): Flow<VehicleData>

    /** 数据源是否可用/已激活 */
    val isAvailable: Flow<Boolean>

    /** 启动数据源 */
    suspend fun start()

    /** 停止数据源 */
    suspend fun stop()
}
