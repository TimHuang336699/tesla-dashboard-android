package com.tesla.dashboard.data.source.tesla

import com.google.gson.annotations.SerializedName
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesla Fleet API 数据源 Provider — 基于 Retrofit + OkHttp
 *
 * 负责填充 VehicleData 中的 Tesla 相关字段:
 * batterySOC, batteryRange, insideTemp, outsideTemp, gear, odometer, isTeslaConnected
 *
 * 轮询机制:
 * - 每 15 秒调用一次 Tesla Fleet API 的 vehicle_data 端点
 * - 成功获取数据后 isTeslaConnected = true,失败则 = false
 * - 未配置 vin/accessToken 时 isAvailable = false,不发起任何请求
 *
 * 温度单位: Tesla API 返回的 inside_temp / outside_temp 为摄氏度(°C),无需转换
 *
 * Hilt 注入说明:
 * 本类通过 @Inject constructor 注入(无参),供 Hilt 依赖图使用。
 * 建议在提供此类的 Module 中添加 @Singleton 作用域。
 * vin 和 accessToken 为运行时可配置属性,注入后通过属性赋值设置。
 */
@Singleton
class TeslaApiProvider @Inject constructor() : VehicleDataSource {

    /** Tesla 车辆识别号(VIN),配置后开始轮询 */
    @Volatile
    var vin: String? = null

    /** Tesla API 访问令牌(Bearer Token),配置后开始轮询 */
    @Volatile
    var accessToken: String? = null

    /** Tesla API 是否已连接(成功获取数据后为 true) */
    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    /** Retrofit API 实例(懒加载,首次使用时创建) */
    private val api: TeslaFleetApi by lazy { createApi() }

    /**
     * 观察 Tesla 车辆数据流
     *
     * 使用 flow { } + delay 实现轮询:
     * - 每 15 秒请求一次 Tesla Fleet API
     * - 成功时发射包含 Tesla 字段的 VehicleData,isTeslaConnected = true
     * - 失败时发射 isTeslaConnected = false 的 VehicleData
     * - 未配置 vin/token 时不发起请求,isAvailable = false
     */
    override fun observeData(): Flow<VehicleData> = flow {
        while (true) {
            val currentVin = vin
            val currentToken = accessToken

            if (currentVin.isNullOrBlank() || currentToken.isNullOrBlank()) {
                // 未配置 vin/token,标记不可用,不发起请求
                _isAvailable.value = false
            } else {
                // 已配置,发起 API 请求
                try {
                    val response = api.getVehicleData(
                        vin = currentVin,
                        authorization = "Bearer $currentToken"
                    )

                    if (response.isSuccessful) {
                        val vehicleData = response.body()?.response?.let {
                            mapToVehicleData(it)
                        }
                        if (vehicleData != null) {
                            _isAvailable.value = true
                            emit(vehicleData)
                        } else {
                            // 响应体为空,视为失败
                            _isAvailable.value = false
                            emit(VehicleData(isTeslaConnected = false))
                        }
                    } else {
                        // HTTP 错误(如 401 Unauthorized, 404 Not Found 等)
                        _isAvailable.value = false
                        emit(VehicleData(isTeslaConnected = false))
                    }
                } catch (e: CancellationException) {
                    // 协程取消,向上传播,不在此捕获
                    throw e
                } catch (e: Exception) {
                    // 网络异常等
                    _isAvailable.value = false
                    emit(VehicleData(isTeslaConnected = false))
                }
            }

            // 等待 15 秒后继续轮询
            delay(POLLING_INTERVAL_MS)
        }
    }

    /**
     * 将 Tesla API 响应映射为 VehicleData
     *
     * 只填充 Tesla 相关字段,其余字段保持默认值。
     */
    private fun mapToVehicleData(
        data: TeslaVehicleResponse.VehicleData
    ): VehicleData {
        return VehicleData(
            batterySOC = data.chargeState?.batteryLevel,
            batteryRange = data.chargeState?.batteryRange,
            insideTemp = data.climateState?.insideTemp,
            outsideTemp = data.climateState?.outsideTemp,
            gear = data.driveState?.shiftState,
            odometer = data.vehicleState?.odometer,
            isTeslaConnected = true
        )
    }

    /**
     * 创建 Retrofit API 实例
     * 配置 OkHttp 超时时间和日志拦截器
     */
    private fun createApi(): TeslaFleetApi {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_READ_S, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(TeslaFleetApi::class.java)
    }

    override suspend fun start() {
        // 轮询的生命周期由 observeData() Flow 的收集管理
        // 此方法可用于预检查配置状态
        if (vin.isNullOrBlank() || accessToken.isNullOrBlank()) {
            _isAvailable.value = false
        }
    }

    override suspend fun stop() {
        _isAvailable.value = false
    }

    companion object {
        /** Tesla Fleet API 基础 URL(中国区) */
        private const val BASE_URL = "https://fleet-api.prd.cn.vn.cloud.tesla.cn/api/1/"

        /** 轮询间隔(ms) */
        private const val POLLING_INTERVAL_MS = 15_000L

        /** 连接超时(秒) */
        private const val TIMEOUT_CONNECT_S = 10L

        /** 读取超时(秒) */
        private const val TIMEOUT_READ_S = 15L
    }
}

/**
 * Tesla Fleet API Retrofit 接口
 *
 * 定义 vehicle_data 端点,获取车辆完整状态数据。
 * 完整 URL: https://fleet-api.prd.cn.vn.cloud.tesla.cn/api/1/vehicles/{vin}/vehicle_data
 */
interface TeslaFleetApi {

    /**
     * 获取车辆数据
     *
     * @param vin 车辆识别号
     * @param authorization 认证头,格式: Bearer {access_token}
     * @return 包含 charge_state, climate_state, drive_state, vehicle_state 的完整响应
     */
    @GET("vehicles/{vin}/vehicle_data")
    suspend fun getVehicleData(
        @Path("vin") vin: String,
        @Header("Authorization") authorization: String
    ): Response<TeslaVehicleResponse>
}

/**
 * Tesla Fleet API vehicle_data 端点响应数据模型
 *
 * JSON 响应结构:
 * {
 *   "response": {
 *     "charge_state": { "battery_level": 65, "battery_range": 213.4, ... },
 *     "climate_state": { "inside_temp": 21.5, "outside_temp": 18.0, ... },
 *     "drive_state": { "shift_state": "D", ... },
 *     "vehicle_state": { "odometer": 12345.6, ... }
 *   }
 * }
 *
 * 温度字段(inside_temp / outside_temp)为摄氏度(°C),无需转换。
 */
data class TeslaVehicleResponse(
    @SerializedName("response") val response: VehicleData?
) {
    /** 车辆完整状态数据 */
    data class VehicleData(
        @SerializedName("charge_state") val chargeState: ChargeState?,
        @SerializedName("climate_state") val climateState: ClimateState?,
        @SerializedName("drive_state") val driveState: DriveState?,
        @SerializedName("vehicle_state") val vehicleState: VehicleState?
    )

    /** 充电状态 */
    data class ChargeState(
        /** 电池电量百分比 0-100 */
        @SerializedName("battery_level") val batteryLevel: Int?,
        /** 电池续航里程(km) */
        @SerializedName("battery_range") val batteryRange: Float?
    )

    /** 空调状态 */
    data class ClimateState(
        /** 车内温度(°C,摄氏度,无需转换) */
        @SerializedName("inside_temp") val insideTemp: Float?,
        /** 车外温度(°C,摄氏度,无需转换) */
        @SerializedName("outside_temp") val outsideTemp: Float?
    )

    /** 驾驶状态 */
    data class DriveState(
        /** 档位: P / R / N / D(车辆静止时可能为 null) */
        @SerializedName("shift_state") val shiftState: String?
    )

    /** 车辆状态 */
    data class VehicleState(
        /** 总里程表(km) */
        @SerializedName("odometer") val odometer: Float?
    )
}
