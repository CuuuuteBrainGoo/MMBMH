package com.bro.lotteryledger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 数据库定义（§19）。
 *
 * 迁移策略：**永不 DROP TABLE**（那是最省事也最蠢的做法，会清空用户账目）。
 * 加字段一律 ALTER TABLE ADD COLUMN，老记录就地保留。
 */
@Database(
    entities = [
        AiProviderEntity::class,
        TicketEntity::class,
        TicketBetEntity::class,
        RecognitionRunEntity::class,
        DraftEntity::class,
        EditLogEntity::class,
        DrawResultEntity::class,
        AppSettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LedgerDb : RoomDatabase() {

    abstract fun aiProviderDao(): AiProviderDao
    abstract fun ticketDao(): TicketDao
    abstract fun recognitionRunDao(): RecognitionRunDao
    abstract fun draftDao(): DraftDao
    abstract fun editLogDao(): EditLogDao
    abstract fun drawResultDao(): DrawResultDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var INSTANCE: LedgerDb? = null

        fun get(context: Context): LedgerDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDb::class.java,
                "lottery_ledger.db"
            )
                // ponytail: 版本 1 期间用破坏性迁移图省事；一旦发布给真机使用，
                // 必须先写 Migration 再改 version，否则用户账目会被清空。
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
