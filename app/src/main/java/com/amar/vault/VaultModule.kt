package com.amar.vault

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun provideVaultDatabase(@ApplicationContext context: Context): VaultDatabase {
        return VaultDatabase.get(context)
    }

    @Provides
    @Singleton
    fun provideVectorSearchManager(@ApplicationContext context: Context): VectorSearchManager {
        return VectorSearchManager.getInstance(context)
    }
}