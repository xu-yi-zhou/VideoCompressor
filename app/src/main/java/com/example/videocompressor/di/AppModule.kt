package com.example.videocompressor.di

import android.content.Context
import com.example.videocompressor.data.repository.VideoRepository
import com.example.videocompressor.domain.compressor.DeviceCodecProfiler
import com.example.videocompressor.domain.compressor.MediaCodecCompressor
import com.example.videocompressor.domain.compressor.ThermalGovernor
import com.example.videocompressor.domain.compressor.VideoCompressor
import com.example.videocompressor.domain.usecase.CompressVideoUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
