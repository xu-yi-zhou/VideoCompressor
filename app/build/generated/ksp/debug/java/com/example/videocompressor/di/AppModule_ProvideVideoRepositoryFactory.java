package com.example.videocompressor.di;

import android.content.Context;
import com.example.videocompressor.data.repository.VideoRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
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
public final class AppModule_ProvideVideoRepositoryFactory implements Factory<VideoRepository> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideVideoRepositoryFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VideoRepository get() {
    return provideVideoRepository(contextProvider.get());
  }

  public static AppModule_ProvideVideoRepositoryFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideVideoRepositoryFactory(contextProvider);
  }

  public static VideoRepository provideVideoRepository(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideVideoRepository(context));
  }
}
