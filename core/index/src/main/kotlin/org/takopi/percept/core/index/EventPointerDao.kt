package org.takopi.percept.core.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventPointerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEvent(row: EventPointerRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTimePrefixKeys(rows: List<TimePrefixKeyRow>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertActorPrefixKeys(rows: List<ActorPrefixKeyRow>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertChannelPrefixKeys(rows: List<ChannelPrefixKeyRow>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertParentEventIds(rows: List<ParentEventIdRow>)

    @Query("SELECT * FROM event_pointers WHERE eventId = :eventId")
    fun eventById(eventId: String): EventPointerRow?

    @Query("SELECT eventId FROM event_pointers WHERE eventCid = :eventCid")
    fun eventIdByEventCid(eventCid: String): String?

    @Query(
        """
        SELECT e.* FROM event_pointers e
        INNER JOIN time_prefix_keys k ON e.eventId = k.eventId
        WHERE k.prefixKey = :prefixKey
        ORDER BY e.timeYear, e.timeMonth, e.timeDay, e.timeHour, e.eventId
        """,
    )
    fun eventsByTimePrefix(prefixKey: String): List<EventPointerRow>

    @Query(
        """
        SELECT e.* FROM event_pointers e
        INNER JOIN actor_prefix_keys k ON e.eventId = k.eventId
        WHERE k.prefixKey = :prefixKey
        ORDER BY e.eventId
        """,
    )
    fun eventsByActorPrefix(prefixKey: String): List<EventPointerRow>

    @Query(
        """
        SELECT e.* FROM event_pointers e
        INNER JOIN channel_prefix_keys k ON e.eventId = k.eventId
        WHERE k.prefixKey = :prefixKey
        ORDER BY e.eventId
        """,
    )
    fun eventsByChannelPrefix(prefixKey: String): List<EventPointerRow>

    @Query(
        """
        SELECT e.* FROM event_pointers e
        INNER JOIN parent_event_ids p ON e.eventId = p.eventId
        WHERE p.parentEventId = :parentEventId
        ORDER BY e.eventId
        """,
    )
    fun childrenOf(parentEventId: String): List<EventPointerRow>

    @Query("UPDATE event_pointers SET dispatchState = :dispatchState WHERE eventId IN (:eventIds)")
    fun updateDispatchState(eventIds: List<String>, dispatchState: String): Int
}
