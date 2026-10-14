package com.wavestudio.app.di

import android.content.Context
import com.wavestudio.app.data.db.AppDatabase
import com.wavestudio.app.data.db.dao.ProjectDao
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.data.repository.ProjectRepositoryImpl
import com.wavestudio.app.audio.AudioIO
import com.wavestudio.app.audio.AudioIOImpl
import com.wavestudio.app.engine.NativeAudioEngine
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.editor.UndoStack
import com.wavestudio.app.editor.Clipboard
import com.wavestudio.app.audio.plugins.PluginManager
import com.wavestudio.app.audio.plugins.PluginManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        AppDatabase.create(ctx)

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()

    @Provides
    @Singleton
    fun provideProjectRepository(dao: ProjectDao): ProjectRepository =
        ProjectRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideNativeEngine(): NativeAudioEngine = NativeAudioEngine()

    @Provides
    @Singleton
    fun provideEngineController(engine: NativeAudioEngine): AudioEngineController =
        AudioEngineController(engine)

    @Provides
    @Singleton
    fun provideAudioIO(@ApplicationContext ctx: Context): AudioIO =
        AudioIOImpl(ctx)

    @Provides
    @Singleton
    fun providePluginManager(@ApplicationContext ctx: Context): PluginManager =
        PluginManagerImpl(ctx)

    @Provides
    @Singleton
    fun provideUndoStack(): UndoStack = UndoStack()

    @Provides
    @Singleton
    fun provideClipboard(): Clipboard = Clipboard()
}
