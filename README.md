# Tesla Dashboard Android

车载仪表盘 Android APP — 面向 Tesla 车主,以手机 GNSS + 加速度传感器为主数据源,可选接入 Tesla Fleet API 补充电量/温度/档位等车辆数据。

## 数据源架构

| 数据源 | 提供数据 | 刷新频率 | 说明 |
|--------|----------|----------|------|
| **GNSS** | 车速、位置、航向、海拔、里程 | 500ms | FusedLocationProvider,亚秒级实时 |
| **加速度传感器** | 纵向/横向加速度、G力 | ~20ms | TYPE_LINEAR_ACCELERATION,流畅动画 |
| **Tesla Fleet API**(可选) | 电量SOC、续航、温度、档位、里程表 | 15s 轮询 | 变化缓慢的数据,秒级延迟可接受 |

> **车速仅来自 GNSS**:Tesla API 轮询延迟秒级,不满足仪表盘实时性需求。

## 技术栈

- **语言**: Kotlin
- **平台**: Android 8.0+ (API 26)
- **架构**: MVVM + 数据源抽象层
- **依赖注入**: Hilt (Dagger)
- **数据库**: Room (行程历史记录)
- **异步**: Kotlin Coroutines + Flow
- **定位**: Google Play Services FusedLocationProvider
- **网络**: Retrofit + OkHttp (Tesla API)
- **UI**: Android View System + ViewBinding,横屏全屏沉浸式

## 项目结构

```
com.tesla.dashboard
├── app/                      # Application 入口 (@HiltAndroidApp)
├── di/                       # Hilt 依赖注入模块
│   └── AppModule.kt          # DataSourceModule + DatabaseModule
├── data/
│   ├── model/                # 数据模型 (VehicleData, Trip)
│   ├── source/               # 数据源抽象层
│   │   ├── VehicleDataSource.kt    # 统一接口
│   │   ├── gnss/GnssProvider.kt    # GNSS 定位测速
│   │   ├── sensor/SensorProvider.kt # 加速度传感器
│   │   └── tesla/TeslaApiProvider.kt # Tesla Fleet API
│   ├── repository/           # 数据融合层
│   │   └── VehicleDataRepository.kt # 多源合并 + 降级策略
│   └── local/                # Room 本地存储
│       ├── AppDatabase.kt
│       ├── TripRepository.kt
│       ├── dao/TripDao.kt
│       └── entity/TripEntity.kt
├── ui/
│   ├── dashboard/            # 仪表盘界面 (Activity + ViewModel)
│   └── history/              # 行程历史记录
├── service/                  # 前台服务 (行程记录保活)
│   └── TripRecordingService.kt
└── res/                      # 资源文件
    ├── layout/               # 布局 (activity_dashboard, activity_history, item_trip)
    ├── values/               # colors, strings, themes
    ├── drawable/             # 启动图标前景
    └── mipmap-anydpi-v26/    # 自适应启动图标
```

## 数据融合策略

```
VehicleDataRepository
├── GNSS (始终可用)     → 车速 / 位置 / 航向 / 海拔 / 里程
├── Sensor (始终可用)   → 加速度 / G力
└── Tesla API (可选)    → 电量 / 温度 / 档位 / 里程表
         │
         ├─ 已连接 → 合并 Tesla 数据
         └─ 未连接 → 对应字段显示 "未连接",其余正常工作
```

## 功能模块

1. **速度表** — 大尺寸主仪表,GNSS 实时测速
2. **电量 SOC** — 电池百分比 + 续航里程 (Tesla API)
3. **温度** — 车内/车外温度 (Tesla API)
4. **档位** — PRND 档位指示 (Tesla API)
5. **里程** — 本次行程 + 总里程累计
6. **GPS 定位** — 经纬度、航向、海拔
7. **加速度 G力** — 纵向/横向 G力实时显示
8. **电耗** — 基于 GNSS 里程 + Tesla 电量变化计算
9. **行程历史** — Room 数据库本地存储,可查看历史行程

## UI 设计

仪表盘采用 Tesla 风格深色科技感主题:

- **背景**: `#0A0E14` 深邃黑色
- **主强调色**: `#00D4FF` 青色霓虹（速度、电量等关键数值）
- **正向状态**: `#00FF88` 绿色（GPS 锁定、Tesla 已连接）
- **警告色**: `#FF3B3B` 红色（停止按钮）
- **提示色**: `#FF9500` 橙色（未连接状态）
- **速度显示**: 96sp 超大字号 + sans-serif-thin 极细字体
- **全屏沉浸**: 隐藏状态栏/导航栏 + 屏幕常亮

布局为横屏三列式:
```
┌──────────────────────────────────────┐
│ 档位    TESLA DASHBOARD    GPS 状态  │
├──────────────────────────────────────┤
│ BATTERY     │    88     │ TEMPERATURE│
│   65%       │   ──      │   22°/28°  │
│ RANGE       │   km/h    │            │
│   213 km    │           │            │
├──────────────────────────────────────┤
│ TRIP  │  ODO  │ G-FORCE              │
│ LAT   │  LON  │ HDG                  │
│ Tesla: 已连接    [HISTORY][START][STOP]│
└──────────────────────────────────────┘
```

## 构建

### 方式一：Android Studio

1. 使用 Android Studio Hedgehog (2023.1.1) 或更高版本打开项目
2. 等待 Gradle Sync 完成
3. 连接 Android 设备 (Android 8.0+)
4. 点击 Run

### 方式二：命令行

```bash
# 生成 gradle-wrapper.jar（首次构建前需要本地已安装 Gradle 8.5+）
gradle wrapper

# Debug 构建
./gradlew assembleDebug

# 安装到已连接的设备
./gradlew installDebug
```

> **注意**: `gradle-wrapper.jar` 为二进制文件，未包含在仓库中。首次构建前请运行 `gradle wrapper` 生成，或使用 Android Studio 打开项目自动生成。

## Tesla API 配置 (可选)

未配置 Tesla API 时,APP 降级为纯 GNSS 仪表盘,电量/温度/档位显示"未连接"。

配置步骤:
1. 在 [Tesla Developer Portal](https://developer.tesla.com) 注册应用
2. 获取 Client ID / Client Secret
3. 完成 OAuth 2.0 授权流程获取 Access Token
4. 在 APP 设置页输入 VIN 和 Access Token

> **中国区 API**: 代码中 `TeslaApiProvider.kt` 的 `BASE_URL` 已设置为 Tesla 中国区 Fleet API (`fleet-api.prd.cn.vn.cloud.tesla.cn`)。其他地区请修改为对应区域 API 地址。

## 权限说明

| 权限 | 用途 |
|------|------|
| ACCESS_FINE_LOCATION | GNSS 高精度定位测速 |
| ACCESS_COARSE_LOCATION | 大致位置(降级) |
| ACCESS_BACKGROUND_LOCATION | 行程记录期间后台定位 |
| INTERNET | Tesla API 网络请求 |
| FOREGROUND_SERVICE | 行程记录前台保活 |
| FOREGROUND_SERVICE_LOCATION | 前台定位服务类型 |
| POST_NOTIFICATIONS | 前台服务通知 (Android 13+) |
| WAKE_LOCK | 屏幕常亮 |

## License

Private project.
