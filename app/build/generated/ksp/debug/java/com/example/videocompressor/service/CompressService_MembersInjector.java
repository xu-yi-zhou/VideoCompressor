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

  public CompressService_MembersInjector(Provider<CompressVideoUseCase> compressUseCaseProvider) {
    this.compressUseCaseProvider = compressUseCaseProvider;
  }

  public static MembersInjector<CompressService> create(
      Provider<CompressVideoUseCase> compressUseCaseProvider) {
    return new CompressService_MembersInjector(compressUseCaseProvider);
  }

  @Override
  public void injectMembers(CompressService instance) {
    injectCompressUseCase(instance, compressUseCaseProvider.get());
  }

  @InjectedFieldSignature("com.example.videocompressor.service.CompressService.compressUseCase")
  public static void injectCompressUseCase(CompressService instance,
      CompressVideoUseCase compressUseCase) {
    instance.compressUseCase = compressUseCase;
  }
}
