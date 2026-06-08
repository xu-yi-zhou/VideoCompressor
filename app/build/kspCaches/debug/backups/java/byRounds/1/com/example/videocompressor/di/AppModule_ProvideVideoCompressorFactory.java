package com.example.videocompressor.di;

import com.example.videocompressor.domain.compressor.DeviceCodecProfiler;
import com.example.videocompressor.domain.compressor.ThermalGovernor;
import com.example.videocompressor.domain.compressor.VideoCompressor;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast"
})
public final class AppModule_ProvideVideoCompressorFactory implements Factory<VideoCompressor> {
  private final Provider<DeviceCodecProfiler> profilerProvider;

  private final Provider<ThermalGovernor> thermalGovernorProvider;

  public AppModule_ProvideVideoCompressorFactory(Provider<DeviceCodecProfiler> profilerProvider,
      Provider<ThermalGovernor> thermalGovernorProvider) {
    this.profilerProvider = profilerProvider;
    this.thermalGovernorProvider = thermalGovernorProvider;
  }

  @Override
  public VideoCompressor get() {
    return provideVideoCompressor(profilerProvider.get(), thermalGovernorProvider.get());
  }

  public static AppModule_ProvideVideoCompressorFactory create(
      Provider<DeviceCodecProfiler> profilerProvider,
      Provider<ThermalGovernor> thermalGovernorProvider) {
    return new AppModule_ProvideVideoCompressorFactory(profilerProvider, thermalGovernorProvider);
  }

  public static VideoCompressor provideVideoCompressor(DeviceCodecProfiler profiler,
      ThermalGovernor thermalGovernor) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideVideoCompressor(profiler, thermalGovernor));
  }
}
