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
public final class MediaCodecCompressor_Factory implements Factory<MediaCodecCompressor> {
  @Override
  public MediaCodecCompressor get() {
    return newInstance();
  }

  public static MediaCodecCompressor_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static MediaCodecCompressor newInstance() {
    return new MediaCodecCompressor();
  }

  private static final class InstanceHolder {
    private static final MediaCodecCompressor_Factory INSTANCE = new MediaCodecCompressor_Factory();
  }
}
