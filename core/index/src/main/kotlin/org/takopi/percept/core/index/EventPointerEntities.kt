package org.takopi.percept.core.index

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event_pointers",
    indices = [
        Index(value = ["eventCid"], unique = true),
        Index(value = ["payloadCid"]),
        Index(value = ["rootEventId"]),
        Index(value = ["valueKind"]),
    ],
)
data class EventPointerRow(
    @PrimaryKey val eventId: String,
    val eventCid: String,
    val payloadCid: String,
    val timeYear: Int,
    val timeMonth: Int,
    val timeDay: Int,
    val timeHour: Int,
    val actorPath: String,
    val channelPath: String,
    val valueKind: String,
    val rootEventId: String,
    val inputEventIds: String,
    val outputArtifactIds: String,
    val pointerJson: String,
    val dispatchState: String = DispatchState.PENDING.name,
)

@Entity(
    tableName = "time_prefix_keys",
    primaryKeys = ["prefixKey", "eventId"],
    foreignKeys = [
        ForeignKey(
            entity = EventPointerRow::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId")],
)
data class TimePrefixKeyRow(val prefixKey: String, val eventId: String)

@Entity(
    tableName = "actor_prefix_keys",
    primaryKeys = ["prefixKey", "eventId"],
    foreignKeys = [
        ForeignKey(
            entity = EventPointerRow::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId")],
)
data class ActorPrefixKeyRow(val prefixKey: String, val eventId: String)

@Entity(
    tableName = "channel_prefix_keys",
    primaryKeys = ["prefixKey", "eventId"],
    foreignKeys = [
        ForeignKey(
            entity = EventPointerRow::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId")],
)
data class ChannelPrefixKeyRow(val prefixKey: String, val eventId: String)

@Entity(
    tableName = "parent_event_ids",
    primaryKeys = ["parentEventId", "eventId"],
    foreignKeys = [
        ForeignKey(
            entity = EventPointerRow::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId")],
)
data class ParentEventIdRow(val parentEventId: String, val eventId: String)

enum class DispatchState {
    PENDING,
    BUNDLED,
    ACKED,
}
