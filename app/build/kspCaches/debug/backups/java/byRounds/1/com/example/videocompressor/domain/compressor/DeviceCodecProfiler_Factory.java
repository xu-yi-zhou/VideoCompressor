package com.example.videocompressor.domain.compressor;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class DeviceCodecProfiler_Factory implements Factory<DeviceCodecProfiler> {
  @Override
  public DeviceCodecProfiler get() {
    return newInstance();
  }

  public static DeviceCodecProfiler_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static DeviceCodecProfiler newInstance() {
    return new DeviceCodecProfiler();
  }

  private static final class InstanceHolder {
    private static final DeviceCodecProfiler_Factory INSTANCE = new DeviceCodecProfiler_Factory();
  }
}
