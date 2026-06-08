package com.example.videocompressor.domain.compressor;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class ThermalGovernor_Factory implements Factory<ThermalGovernor> {
  private final Provider<Context> contextProvider;

  public ThermalGovernor_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ThermalGovernor get() {
    return newInstance(contextProvider.get());
  }

  public static ThermalGovernor_Factory create(Provider<Context> contextProvider) {
    return new ThermalGovernor_Factory(contextProvider);
  }

  public static ThermalGovernor newInstance(Context context) {
    return new ThermalGovernor(context);
  }
}
