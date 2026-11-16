package com.saitateppei.opencloudflac.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        SyncFolderEntity::class,
        TrackEntity::class,
        DownloadTaskEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        SyncDirCacheEntity::class,
        LogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun trackDao(): TrackDao
    abstract fun downloadDao(): DownloadTaskDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun syncDao(): SyncDao
    abstract fun logDao(): LogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db").build()
    }
}
