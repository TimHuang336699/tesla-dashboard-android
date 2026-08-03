package com.tesla.dashboard.data.model

/**
 * Tesla 车型电池容量配置
 *
 * 根据车辆 VIN 或车型代码获取对应电池容量(kWh)，
 * 用于精确计算瞬时/区间电耗。
 */
object BatteryConfig {

    /**
     * Tesla 车型电池容量映射 (kWh)
     *
     * 数据来源: Tesla 官方规格参数
     */
    private val capacityByModel = mapOf(
        // Model S
        "model_s_75" to 75f,
        "model_s_85" to 85f,
        "model_s_90" to 90f,
        "model_s_100" to 100f,
        "model_s_plaid" to 100f,
        // Model 3
        "model_3_standard" to 50f,
        "model_3_long_range" to 75f,
        "model_3_performance" to 75f,
        // Model X
        "model_x_75" to 75f,
        "model_x_90" to 90f,
        "model_x_100" to 100f,
        "model_x_plaid" to 100f,
        // Model Y
        "model_y_standard" to 54f,
        "model_y_long_range" to 75f,
        "model_y_performance" to 75f,
        // Cybertruck
        "cybertruck_dual" to 123f,
        "cybertruck_tri" to 123f,
    )

    /** 默认电池容量(kWh)，无法识别车型时使用 */
    private const val DEFAULT_CAPACITY_KWH = 75f

    /**
     * 根据车型代码获取电池容量
     *
     * @param modelCode 车型代码(如 "model_3_long_range")
     * @return 电池容量 kWh，未匹配时返回默认值
     */
    fun getCapacityKWh(modelCode: String?): Float {
        if (modelCode.isNullOrBlank()) return DEFAULT_CAPACITY_KWH
        return capacityByModel[modelCode.lowercase()] ?: DEFAULT_CAPACITY_KWH
    }

    /**
     * 根据 VIN 推断车型代码
     *
     * Tesla VIN 第4-8位为车型描述段:
     * - 5YJSA = Model S
     * - 5YJ3 = Model 3
     * - 5YJX = Model X
     * - 7YJY = Model Y (部分)
     * - 7GK = Cybertruck
     *
     * 精确车型(电池容量版本)无法仅从 VIN 判断，
     * 需结合 Tesla API 返回的 car_type / option_codes。
     * 此方法仅做粗略推断，返回保守默认值。
     *
     * @param vin 17位车辆识别号
     * @return 推断的车型代码
     */
    fun inferModelFromVin(vin: String?): String? {
        if (vin.isNullOrBlank() || vin.length < 8) return null
        val segment = vin.substring(3, 8).uppercase()
        return when {
            segment.startsWith("SA") || segment.startsWith("SB") -> "model_s_100"
            segment.startsWith("3") || segment.startsWith("E3") -> "model_3_long_range"
            segment.startsWith("X") || segment.startsWith("SX") -> "model_x_100"
            segment.startsWith("Y") || segment.startsWith("EY") -> "model_y_long_range"
            segment.startsWith("GK") -> "cybertruck_dual"
            else -> null
        }
    }
}
