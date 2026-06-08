package com.example.videocompressor.di;

import com.example.videocompressor.domain.compressor.DeviceCodecProfiler;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class AppModule_ProvideDeviceCodecProfilerFactory implements Factory<DeviceCodecProfiler> {
  @Override
  public DeviceCodecProfiler get() {
    return provideDeviceCodecProfiler();
  }

  public static AppModule_ProvideDeviceCodecProfilerFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static DeviceCodecProfiler provideDeviceCodecProfiler() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideDeviceCodecProfiler());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideDeviceCodecProfilerFactory INSTANCE = new AppModule_ProvideDeviceCodecProfilerFactory();
  }
}
