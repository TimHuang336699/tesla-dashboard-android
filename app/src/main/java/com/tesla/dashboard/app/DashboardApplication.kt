package com.tesla.dashboard.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application 入口类
 *
 * 使用 @HiltAndroidApp 注解触发 Hilt 的代码生成,
 * 自动创建并管理应用级别的依赖注入容器。
 *
 * Hilt 会在编译期生成 Hilt_DashboardApplication 类,
 * 并通过 Application 的生命周期完成组件初始化。
 *
 * AndroidManifest.xml 中已配置 android:name=".app.DashboardApplication"。
 */
@HiltAndroidApp
class DashboardApplication : Application()
