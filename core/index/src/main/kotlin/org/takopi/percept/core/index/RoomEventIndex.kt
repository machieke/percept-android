package org.takopi.percept.core.index

import org.takopi.percept.core.canonical.CBool
import org.takopi.percept.core.canonical.CList
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.trace.EventIndex
import java.nio.charset.StandardCharsets

class RoomEventIndex(
    private val database: EventPointerDatabase,
) : EventIndex {
    private val dao = database.eventPointerDao()

    override fun putEvent(eventPointer: CMap): CMap {
        val eventId = eventPointer.requiredString("eventId")
        val eventCid = eventPointer.requiredString("eventCid")
        val payloadCid = eventPointer.requiredString("payloadCid")
        val rootEventId = eventPointer.requiredString("rootEventId")

        if (dao.eventById(eventId) != null) {
            return cMap(
                "ok" to CBool(false),
                "error" to CString("duplicate-event-id"),
                "eventId" to CString(eventId),
            )
        }
        val existingEventId = dao.eventIdByEventCid(eventCid)
        if (existingEventId != null) {
            return cMap(
                "ok" to CBool(false),
                "error" to CString("duplicate-event-cid"),
                "eventCid" to CString(eventCid),
                "eventId" to CString(existingEventId),
            )
        }

        val timePath = eventPointer.longList("timePath")
        require(timePath.size == 4) { "timePath must have [year, month, day, hour]" }
        val row = EventPointerRow(
            eventId = eventId,
            eventCid = eventCid,
            payloadCid = payloadCid,
            timeYear = timePath[0].toInt(),
            timeMonth = timePath[1].toInt(),
            timeDay = timePath[2].toInt(),
            timeHour = timePath[3].toInt(),
            actorPath = eventPointer.stringList("actorPath").joinToString("/"),
            channelPath = eventPointer.stringList("channelPath").joinToString("/"),
            valueKind = eventPointer.requiredString("valueKind"),
            rootEventId = rootEventId,
            inputEventIds = eventPointer.stringList("inputEventIds").joinToString("\n"),
            outputArtifactIds = eventPointer.stringList("outputArtifactIds").joinToString("\n"),
            pointerJson = canonicalBytes(eventPointer).toString(StandardCharsets.UTF_8),
        )

        database.runInTransaction {
            dao.insertEvent(row)
            dao.insertTimePrefixKeys(
                eventPointer.stringList("timePrefixKeys").map { TimePrefixKeyRow(it, eventId) },
            )
            dao.insertActorPrefixKeys(
                eventPointer.stringList("actorPrefixKeys").map { ActorPrefixKeyRow(it, eventId) },
            )
            dao.insertChannelPrefixKeys(
                eventPointer.stringList("channelPrefixKeys").map { ChannelPrefixKeyRow(it, eventId) },
            )
            dao.insertParentEventIds(
                eventPointer.stringList("parentEventIds").map { ParentEventIdRow(it, eventId) },
            )
        }

        return cMap(
            "ok" to CBool(true),
            "eventId" to CString(eventId),
            "eventCid" to CString(eventCid),
            "payloadCid" to CString(payloadCid),
        )
    }

    override fun getEvent(eventId: String): CMap? {
        val row = dao.eventById(eventId) ?: return null
        return cMap(
            "eventId" to CString(row.eventId),
            "eventCid" to CString(row.eventCid),
            "payloadCid" to CString(row.payloadCid),
            "rootEventId" to CString(row.rootEventId),
        )
    }

    fun eventsByTimePrefix(prefixKey: String): List<EventPointerRow> =
        dao.eventsByTimePrefix(prefixKey)

    fun eventsByActorPrefix(prefixKey: String): List<EventPointerRow> =
        dao.eventsByActorPrefix(prefixKey)

    fun eventsByChannelPrefix(prefixKey: String): List<EventPointerRow> =
        dao.eventsByChannelPrefix(prefixKey)

    fun childrenOf(parentEventId: String): List<EventPointerRow> =
        dao.childrenOf(parentEventId)

    fun updateDispatchState(eventIds: List<String>, dispatchState: DispatchState): Int =
        dao.updateDispatchState(eventIds, dispatchState.name)

    private fun CMap.requiredString(key: String): String {
        val value = entries[key]
        require(value is CString) { "missing string field: $key" }
        return value.value
    }

    private fun CMap.stringList(key: String): List<String> {
        val value = entries[key]
        require(value is CList) { "missing list field: $key" }
        return value.values.map {
            require(it is CString) { "field $key must contain only strings" }
            it.value
        }
    }

    private fun CMap.longList(key: String): List<Long> {
        val value = entries[key]
        require(value is CList) { "missing list field: $key" }
        return value.values.map {
            require(it is CLong) { "field $key must contain only integers" }
            it.value
        }
    }
}
