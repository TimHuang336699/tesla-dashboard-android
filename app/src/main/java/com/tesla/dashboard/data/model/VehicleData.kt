package com.tesla.dashboard.data.model

/**
 * 统一车辆数据模型 — 融合 GNSS + Sensor + Tesla API 三个数据源
 *
 * 数据刷新频率分层:
 * - GNSS/Sensor 字段: 亚秒级实时更新(本地传感器,无网络延迟)
 * - Tesla API 字段: 10-30s 轮询(变化缓慢,秒级延迟可接受)
 */
data class VehicleData(

    // ===== GNSS 实时数据(始终可用) =====
    /** 车速 km/h — 仅来自 GNSS 测速,不取自 Tesla API(延迟过大) */
    val speed: Float = 0f,

    /** 纬度 */
    val latitude: Double = 0.0,

    /** 经度 */
    val longitude: Double = 0.0,

    /** 航向 角度 0-360 */
    val heading: Float = 0f,

    /** 海拔 米 */
    val altitude: Double = 0.0,

    /** 本次行程累计里程 km */
    val tripDistance: Float = 0f,

    /** GNSS 定位精度 米 */
    val gpsAccuracy: Float = 0f,

    /** GNSS 速度精度 km/h */
    val speedAccuracy: Float = 0f,

    // ===== 加速度传感器实时数据(始终可用) =====
    /** 纵向加速度 m/s² (前/后) */
    val accelLongitudinal: Float = 0f,

    /** 横向加速度 m/s² (左/右) */
    val accelLateral: Float = 0f,

    /** 合成 G 力 (约 1.0 = 1g 重力) */
    val gForce: Float = 0f,

    // ===== Tesla API 低频数据(可选,null = 未连接/不可用) =====
    /** 电池电量百分比 0-100 */
    val batterySOC: Int? = null,

    /** 电池续航里程 km */
    val batteryRange: Float? = null,

    /** 车内温度 °C */
    val insideTemp: Float? = null,

    /** 车外温度 °C */
    val outsideTemp: Float? = null,

    /** 档位: P / R / N / D */
    val gear: String? = null,

    /** 总里程表 km */
    val odometer: Float? = null,

    // ===== 状态标志 =====
    /** Tesla API 是否已连接 */
    val isTeslaConnected: Boolean = false,

    /** GNSS 是否已锁定 */
    val isGpsLocked: Boolean = false,
) {
    /**
     * 计算瞬时电耗 kWh/100km
     *
     * 需要调用方提供车辆电池容量(kWh)，以确保不同车型(如 Model 3 标准版 50kWh
     * 与 Cybertruck 123kWh)的电耗计算精度。
     *
     * 需要: 本次行程里程增量 + Tesla API 电量变化
     * 返回 null 表示数据不足无法计算
     *
     * @param prevData 上一帧车辆数据，用于获取前一次电池 SOC
     * @param distanceDeltaKm 本次行程里程增量 km
     * @param batteryCapacityKWh 车辆电池总容量 kWh
     * @return 电耗 kWh/100km，数据不足时返回 null；无耗电时返回 0f
     */
    fun computeConsumption(
        prevData: VehicleData,
        distanceDeltaKm: Float,
        batteryCapacityKWh: Float,
    ): Float? {
        if (!isTeslaConnected || prevData.batterySOC == null || batterySOC == null) return null
        if (distanceDeltaKm <= 0f) return null

        // 估算: 电量百分比下降 × 电池总容量 / 里程
        val socDelta = (prevData.batterySOC - batterySOC).coerceAtLeast(0)
        if (socDelta == 0) return 0f

        val energyUsedKWh = (socDelta / 100f) * batteryCapacityKWh
        return (energyUsedKWh / distanceDeltaKm) * 100f
    }

    /**
     * 计算瞬时电耗 kWh/100km(重载方法)
     *
     * 根据车型代码自动从 [BatteryConfig] 获取电池容量，无需调用方手动传入。
     * 适用于已知车型代码的场景；车型代码可由 [BatteryConfig.inferModelFromVin]
     * 推断或由 Tesla API 返回的 car_type 提供。
     *
     * @param prevData 上一帧车辆数据，用于获取前一次电池 SOC
     * @param distanceDeltaKm 本次行程里程增量 km
     * @param modelCode 车型代码(如 "model_3_long_range")，为空时使用默认容量
     * @return 电耗 kWh/100km，数据不足时返回 null；无耗电时返回 0f
     */
    fun computeConsumption(
        prevData: VehicleData,
        distanceDeltaKm: Float,
        modelCode: String?,
    ): Float? {
        val batteryCapacityKWh = BatteryConfig.getCapacityKWh(modelCode)
        return computeConsumption(prevData, distanceDeltaKm, batteryCapacityKWh)
    }
}
