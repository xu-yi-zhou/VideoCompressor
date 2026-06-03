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
public final class EncoderDetector_Factory implements Factory<EncoderDetector> {
  @Override
  public EncoderDetector get() {
    return newInstance();
  }

  public static EncoderDetector_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static EncoderDetector newInstance() {
    return new EncoderDetector();
  }

  private static final class InstanceHolder {
    private static final EncoderDetector_Factory INSTANCE = new EncoderDetector_Factory();
  }
}
