package com.example.videocompressor.ui.viewmodel;

import android.content.Context;
import com.example.videocompressor.data.repository.VideoRepository;
import com.example.videocompressor.domain.usecase.CompressVideoUseCase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class CompressViewModel_Factory implements Factory<CompressViewModel> {
  private final Provider<CompressVideoUseCase> compressUseCaseProvider;

  private final Provider<VideoRepository> repositoryProvider;

  private final Provider<Context> contextProvider;

  public CompressViewModel_Factory(Provider<CompressVideoUseCase> compressUseCaseProvider,
      Provider<VideoRepository> repositoryProvider, Provider<Context> contextProvider) {
    this.compressUseCaseProvider = compressUseCaseProvider;
    this.repositoryProvider = repositoryProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public CompressViewModel get() {
    return newInstance(compressUseCaseProvider.get(), repositoryProvider.get(), contextProvider.get());
  }

  public static CompressViewModel_Factory create(
      Provider<CompressVideoUseCase> compressUseCaseProvider,
      Provider<VideoRepository> repositoryProvider, Provider<Context> contextProvider) {
    return new CompressViewModel_Factory(compressUseCaseProvider, repositoryProvider, contextProvider);
  }

  public static CompressViewModel newInstance(CompressVideoUseCase compressUseCase,
      VideoRepository repository, Context context) {
    return new CompressViewModel(compressUseCase, repository, context);
  }
}
