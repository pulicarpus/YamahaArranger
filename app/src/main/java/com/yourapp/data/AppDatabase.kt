package com.yourapp.yamahaarranger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [RegistrationMemoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun registrationMemoryDao(): RegistrationMemoryDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "yamaha_arranger.db")
            // Phase 3: replace with real Migrations once the reserved
            // VoiceLayer columns in RegistrationMemoryEntity are populated
            // and the schema needs its first real change.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideRegistrationMemoryDao(db: AppDatabase): RegistrationMemoryDao = db.registrationMemoryDao()
}
