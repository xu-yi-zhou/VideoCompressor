package com.example.videocompressor.di;

import com.example.videocompressor.data.repository.VideoRepository;
import com.example.videocompressor.domain.compressor.VideoCompressor;
import com.example.videocompressor.domain.usecase.CompressVideoUseCase;
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
public final class AppModule_ProvideCompressVideoUseCaseFactory implements Factory<CompressVideoUseCase> {
  private final Provider<VideoCompressor> compressorProvider;

  private final Provider<VideoRepository> repositoryProvider;

  public AppModule_ProvideCompressVideoUseCaseFactory(Provider<VideoCompressor> compressorProvider,
      Provider<VideoRepository> repositoryProvider) {
    this.compressorProvider = compressorProvider;
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public CompressVideoUseCase get() {
    return provideCompressVideoUseCase(compressorProvider.get(), repositoryProvider.get());
  }

  public static AppModule_ProvideCompressVideoUseCaseFactory create(
      Provider<VideoCompressor> compressorProvider, Provider<VideoRepository> repositoryProvider) {
    return new AppModule_ProvideCompressVideoUseCaseFactory(compressorProvider, repositoryProvider);
  }

  public static CompressVideoUseCase provideCompressVideoUseCase(VideoCompressor compressor,
      VideoRepository repository) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideCompressVideoUseCase(compressor, repository));
  }
}
