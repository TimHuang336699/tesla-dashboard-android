package com.tesla.dashboard.di

import android.content.Context
import com.tesla.dashboard.data.local.AppDatabase
import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.data.source.gnss.GnssProvider
import com.tesla.dashboard.data.source.sensor.SensorProvider
import com.tesla.dashboard.data.source.tesla.TeslaApiProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 *
 * - DataSourceModule: 将三个 Provider 绑定到 VehicleDataSource 接口,使用 @Named 限定符区分
 * - DatabaseModule:   提供 Room 数据库和 DAO
 */

// ===== 数据源绑定 =====

@Module
@InstallIn(SingletonComponent::class)
abstract class DataSourceModule {

    @Binds
    @Named("gnss")
    @Singleton
    abstract fun bindGnssProvider(impl: GnssProvider): VehicleDataSource

    @Binds
    @Named("sensor")
    @Singleton
    abstract fun bindSensorProvider(impl: SensorProvider): VehicleDataSource

    @Binds
    @Named("tesla")
    @Singleton
    abstract fun bindTeslaApiProvider(impl: TeslaApiProvider): VehicleDataSource
}

// ===== Room 数据库 =====

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
}
