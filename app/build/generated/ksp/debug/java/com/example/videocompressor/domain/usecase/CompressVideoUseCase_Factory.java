package com.example.videocompressor.domain.usecase;

import com.example.videocompressor.data.repository.VideoRepository;
import com.example.videocompressor.domain.compressor.VideoCompressor;
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
public final class CompressVideoUseCase_Factory implements Factory<CompressVideoUseCase> {
  private final Provider<VideoCompressor> compressorProvider;

  private final Provider<VideoRepository> repositoryProvider;

  public CompressVideoUseCase_Factory(Provider<VideoCompressor> compressorProvider,
      Provider<VideoRepository> repositoryProvider) {
    this.compressorProvider = compressorProvider;
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public CompressVideoUseCase get() {
    return newInstance(compressorProvider.get(), repositoryProvider.get());
  }

  public static CompressVideoUseCase_Factory create(Provider<VideoCompressor> compressorProvider,
      Provider<VideoRepository> repositoryProvider) {
    return new CompressVideoUseCase_Factory(compressorProvider, repositoryProvider);
  }

  public static CompressVideoUseCase newInstance(VideoCompressor compressor,
      VideoRepository repository) {
    return new CompressVideoUseCase(compressor, repository);
  }
}
