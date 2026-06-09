/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.di

import android.content.Context
import com.xuyizhou.videocompressor.data.repository.VideoRepository
import com.xuyizhou.videocompressor.domain.compressor.DeviceCodecProfiler
import com.xuyizhou.videocompressor.domain.compressor.MediaCodecCompressor
import com.xuyizhou.videocompressor.domain.compressor.ThermalGovernor
import com.xuyizhou.videocompressor.domain.compressor.VideoCompressor
import com.xuyizhou.videocompressor.domain.usecase.CompressVideoUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 全局依赖注入模块，安装于 [dagger.hilt.components.SingletonComponent]。
 *
 * 以单例形式提供应用核心依赖：[com.xuyizhou.videocompressor.data.repository.VideoRepository]、
 * [com.xuyizhou.videocompressor.domain.compressor.VideoCompressor]（实现为 [com.xuyizhou.videocompressor.domain.compressor.MediaCodecCompressor]）、
 * [com.xuyizhou.videocompressor.domain.usecase.CompressVideoUseCase]、
 * [com.xuyizhou.videocompressor.domain.compressor.DeviceCodecProfiler] 以及
 * [com.xuyizhou.videocompressor.domain.compressor.ThermalGovernor]。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVideoRepository(
        @ApplicationContext context: Context
    ): VideoRepository = VideoRepository(context)

    @Provides
    @Singleton
    fun provideDeviceCodecProfiler(): DeviceCodecProfiler = DeviceCodecProfiler()

    @Provides
    @Singleton
    fun provideThermalGovernor(
        @ApplicationContext context: Context
    ): ThermalGovernor = ThermalGovernor(context)

    @Provides
    @Singleton
    fun provideVideoCompressor(
        profiler: DeviceCodecProfiler,
        thermalGovernor: ThermalGovernor
    ): VideoCompressor = MediaCodecCompressor(profiler, thermalGovernor)

    @Provides
    @Singleton
    fun provideCompressVideoUseCase(
        compressor: VideoCompressor,
        repository: VideoRepository
    ): CompressVideoUseCase = CompressVideoUseCase(compressor, repository)
}
