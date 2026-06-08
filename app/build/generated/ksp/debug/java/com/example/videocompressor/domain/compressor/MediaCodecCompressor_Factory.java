package com.example.videocompressor.domain.compressor;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class MediaCodecCompressor_Factory implements Factory<MediaCodecCompressor> {
  private final Provider<DeviceCodecProfiler> profilerProvider;

  private final Provider<ThermalGovernor> thermalProvider;

  public MediaCodecCompressor_Factory(Provider<DeviceCodecProfiler> profilerProvider,
      Provider<ThermalGovernor> thermalProvider) {
    this.profilerProvider = profilerProvider;
    this.thermalProvider = thermalProvider;
  }

  @Override
  public MediaCodecCompressor get() {
    return newInstance(profilerProvider.get(), thermalProvider.get());
  }

  public static MediaCodecCompressor_Factory create(Provider<DeviceCodecProfiler> profilerProvider,
      Provider<ThermalGovernor> thermalProvider) {
    return new MediaCodecCompressor_Factory(profilerProvider, thermalProvider);
  }

  public static MediaCodecCompressor newInstance(DeviceCodecProfiler profiler,
      ThermalGovernor thermal) {
    return new MediaCodecCompressor(profiler, thermal);
  }
}
