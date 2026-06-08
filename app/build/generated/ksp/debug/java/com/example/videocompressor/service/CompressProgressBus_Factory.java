package com.example.videocompressor.service;

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
public final class CompressProgressBus_Factory implements Factory<CompressProgressBus> {
  @Override
  public CompressProgressBus get() {
    return newInstance();
  }

  public static CompressProgressBus_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static CompressProgressBus newInstance() {
    return new CompressProgressBus();
  }

  private static final class InstanceHolder {
    private static final CompressProgressBus_Factory INSTANCE = new CompressProgressBus_Factory();
  }
}
