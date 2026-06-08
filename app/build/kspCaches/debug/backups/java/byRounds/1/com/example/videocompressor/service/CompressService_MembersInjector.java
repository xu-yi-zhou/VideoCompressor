package com.example.videocompressor.service;

import com.example.videocompressor.domain.usecase.CompressVideoUseCase;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class CompressService_MembersInjector implements MembersInjector<CompressService> {
  private final Provider<CompressVideoUseCase> compressUseCaseProvider;

  private final Provider<CompressProgressBus> progressBusProvider;

  public CompressService_MembersInjector(Provider<CompressVideoUseCase> compressUseCaseProvider,
      Provider<CompressProgressBus> progressBusProvider) {
    this.compressUseCaseProvider = compressUseCaseProvider;
    this.progressBusProvider = progressBusProvider;
  }

  public static MembersInjector<CompressService> create(
      Provider<CompressVideoUseCase> compressUseCaseProvider,
      Provider<CompressProgressBus> progressBusProvider) {
    return new CompressService_MembersInjector(compressUseCaseProvider, progressBusProvider);
  }

  @Override
  public void injectMembers(CompressService instance) {
    injectCompressUseCase(instance, compressUseCaseProvider.get());
    injectProgressBus(instance, progressBusProvider.get());
  }

  @InjectedFieldSignature("com.example.videocompressor.service.CompressService.compressUseCase")
  public static void injectCompressUseCase(CompressService instance,
      CompressVideoUseCase compressUseCase) {
    instance.compressUseCase = compressUseCase;
  }

  @InjectedFieldSignature("com.example.videocompressor.service.CompressService.progressBus")
  public static void injectProgressBus(CompressService instance, CompressProgressBus progressBus) {
    instance.progressBus = progressBus;
  }
}
