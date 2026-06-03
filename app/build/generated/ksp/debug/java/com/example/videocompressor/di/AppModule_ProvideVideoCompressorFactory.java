package com.example.videocompressor.di;

import com.example.videocompressor.domain.compressor.VideoCompressor;
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
public final class AppModule_ProvideVideoCompressorFactory implements Factory<VideoCompressor> {
  @Override
  public VideoCompressor get() {
    return provideVideoCompressor();
  }

  public static AppModule_ProvideVideoCompressorFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static VideoCompressor provideVideoCompressor() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideVideoCompressor());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideVideoCompressorFactory INSTANCE = new AppModule_ProvideVideoCompressorFactory();
  }
}
