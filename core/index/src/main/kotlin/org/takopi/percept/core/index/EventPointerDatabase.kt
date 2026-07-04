package org.takopi.percept.core.index

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventPointerRow::class,
        TimePrefixKeyRow::class,
        ActorPrefixKeyRow::class,
        ChannelPrefixKeyRow::class,
        ParentEventIdRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class EventPointerDatabase : RoomDatabase() {
    abstract fun eventPointerDao(): EventPointerDao
}
