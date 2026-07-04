package org.takopi.percept.core.trace

import org.takopi.percept.core.canonical.CList
import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.stringList

class MemoryEventIndex : EventIndex {
    private val events = linkedMapOf<String, CMap>()
    private val eventCidIndex = linkedMapOf<String, String>()
    private val timeIndex = linkedMapOf<String, MutableList<String>>()
    private val actorIndex = linkedMapOf<String, MutableList<String>>()
    private val channelIndex = linkedMapOf<String, MutableList<String>>()
    private val kindIndex = linkedMapOf<String, MutableList<String>>()
    private val parentIndex = linkedMapOf<String, MutableList<String>>()
    private val rootIndex = linkedMapOf<String, MutableList<String>>()
    private val payloadIndex = linkedMapOf<String, MutableList<String>>()

    override fun putEvent(eventPointer: CMap): CMap {
        val eventId = eventPointer.requiredString("eventId")
        val eventCid = eventPointer.requiredString("eventCid")
        val payloadCid = eventPointer.requiredString("payloadCid")
        val rootEventId = eventPointer.stringOrNull("rootEventId") ?: eventId

        if (events.containsKey(eventId)) {
            return cMap(
                "ok" to org.takopi.percept.core.canonical.CBool(false),
                "error" to CString("duplicate-event-id"),
                "eventId" to CString(eventId),
            )
        }
        if (eventCidIndex.containsKey(eventCid)) {
            return cMap(
                "ok" to org.takopi.percept.core.canonical.CBool(false),
                "error" to CString("duplicate-event-cid"),
                "eventCid" to CString(eventCid),
                "eventId" to CString(eventCidIndex.getValue(eventCid)),
            )
        }

        events[eventId] = eventPointer
        eventPointer.stringList("timePrefixKeys").forEach { appendUnique(timeIndex, it, eventId) }
        eventPointer.stringList("actorPrefixKeys").forEach { appendUnique(actorIndex, it, eventId) }
        eventPointer.stringList("channelPrefixKeys").forEach { appendUnique(channelIndex, it, eventId) }
        eventPointer.stringList("parentEventIds").forEach { appendUnique(parentIndex, it, eventId) }
        appendUnique(kindIndex, eventPointer.requiredString("valueKind"), eventId)
        appendUnique(rootIndex, rootEventId, eventId)
        appendUnique(payloadIndex, payloadCid, eventId)
        eventCidIndex[eventCid] = eventId

        return cMap(
            "ok" to org.takopi.percept.core.canonical.CBool(true),
            "eventId" to CString(eventId),
            "eventCid" to CString(eventCid),
            "payloadCid" to CString(payloadCid),
        )
    }

    override fun getEvent(eventId: String): CMap? = events[eventId]

    fun byTimePrefix(prefixKey: String): List<String> = timeIndex[prefixKey].orEmpty().toList()

    fun byActorPrefix(prefixKey: String): List<String> = actorIndex[prefixKey].orEmpty().toList()

    fun byChannelPrefix(prefixKey: String): List<String> = channelIndex[prefixKey].orEmpty().toList()

    fun byKind(valueKind: String): List<String> = kindIndex[valueKind].orEmpty().toList()

    fun byParent(parentEventId: String): List<String> = parentIndex[parentEventId].orEmpty().toList()

    fun byRoot(rootEventId: String): List<String> = rootIndex[rootEventId].orEmpty().toList()

    fun byPayloadCid(payloadCid: String): List<String> = payloadIndex[payloadCid].orEmpty().toList()

    fun byEventCid(eventCid: String): String? = eventCidIndex[eventCid]

    private fun CMap.requiredString(key: String): String =
        stringOrNull(key) ?: throw IllegalArgumentException("missing string field: $key")

    private fun CMap.stringList(key: String): List<String> {
        val list = entries[key] as? CList ?: return emptyList()
        return list.values.map {
            require(it is CString) { "field $key must contain only strings" }
            it.value
        }
    }

    private fun appendUnique(index: MutableMap<String, MutableList<String>>, key: String, value: String) {
        val bucket = index.getOrPut(key) { mutableListOf() }
        if (value !in bucket) bucket += value
    }
}
