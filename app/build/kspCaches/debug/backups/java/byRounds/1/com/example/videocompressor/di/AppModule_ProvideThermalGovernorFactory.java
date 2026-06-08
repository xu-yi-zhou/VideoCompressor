package com.example.videocompressor.di;

import android.content.Context;
import com.example.videocompressor.domain.compressor.ThermalGovernor;
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
public final class AppModule_ProvideThermalGovernorFactory implements Factory<ThermalGovernor> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideThermalGovernorFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ThermalGovernor get() {
    return provideThermalGovernor(contextProvider.get());
  }

  public static AppModule_ProvideThermalGovernorFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideThermalGovernorFactory(contextProvider);
  }

  public static ThermalGovernor provideThermalGovernor(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideThermalGovernor(context));
  }
}
