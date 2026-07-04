package org.takopi.percept.core.trace

import org.takopi.percept.core.canonical.CList
import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.CNull
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.CanonicalValue
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.canonical.contentId
import org.takopi.percept.core.canonical.parseUtcTime
import org.takopi.percept.core.canonical.prefixKeys
import org.takopi.percept.core.canonical.sha256Hex
import org.takopi.percept.core.canonical.stringList
import org.takopi.percept.core.canonical.timePrefixKeys
import org.takopi.percept.core.da.DAStore

data class IngestedEvent(
    val eventId: String,
    val eventCid: String,
    val payloadCid: String,
    val envelope: CMap,
    val pointer: CMap,
    val ack: CMap,
)

interface EventIndex {
    fun putEvent(eventPointer: CMap): CMap
    fun getEvent(eventId: String): CMap?
}

class EventIngestor(
    private val da: DAStore,
    private val index: EventIndex,
) {
    fun logChildEvent(
        rawPayload: CMap,
        observedAt: String,
        actorPath: List<String>,
        channelPath: List<String>,
        valueKind: String,
        parentEventIds: List<String>,
        rootEventId: String? = null,
        inputEventIds: List<String> = emptyList(),
        outputArtifactIds: List<String> = emptyList(),
        provenance: CMap? = null,
    ): IngestedEvent {
        val resolvedRootEventId = rootEventId ?: parentEventIds.firstOrNull()?.let { parentId ->
            index.getEvent(parentId)?.stringOrNull("rootEventId")
        }
        return ingestEvent(
            rawPayload = rawPayload,
            observedAt = observedAt,
            actorPath = actorPath,
            channelPath = channelPath,
            valueKind = valueKind,
            parentEventIds = parentEventIds,
            rootEventId = resolvedRootEventId,
            inputEventIds = inputEventIds,
            outputArtifactIds = outputArtifactIds,
            provenance = provenance ?: workerProvenance(),
        )
    }

    fun ingestEvent(
        rawPayload: CMap,
        observedAt: String,
        actorPath: List<String>,
        channelPath: List<String>,
        valueKind: String,
        contentType: String = "application/json",
        preview: String? = null,
        provenance: CMap? = null,
        parentEventIds: List<String> = emptyList(),
        rootEventId: String? = null,
        inputEventIds: List<String> = emptyList(),
        outputArtifactIds: List<String> = emptyList(),
    ): IngestedEvent {
        val payloadCid = da.putJson(rawPayload)
        val time = parseUtcTime(observedAt)
        val value = linkedMapOf<String, CanonicalValue>(
            "kind" to CString(valueKind),
            "contentType" to CString(contentType),
            "payloadCid" to CString(payloadCid),
        )
        if (preview != null) {
            value["preview"] = CString(preview)
        }
        val envelope = cMap(
            "kind" to CString("event-trace"),
            "schema" to CString("event-trace-v0.1"),
            "time" to time.toCanonicalValue(),
            "actorPath" to stringList(actorPath),
            "channelPath" to stringList(channelPath),
            "value" to CMap(value),
            "provenance" to (provenance ?: unknownProvenance()),
            "causal" to cMap(
                "parentEventIds" to stringList(parentEventIds),
                "rootEventId" to (rootEventId?.let(::CString) ?: CNull),
                "inputEventIds" to stringList(inputEventIds),
                "outputArtifactIds" to stringList(outputArtifactIds),
            ),
        )
        val eventBytes = canonicalBytes(envelope)
        val eventCid = da.putBytes(eventBytes, codec = "dag-json")
        val eventId = "event:${sha256Hex(eventBytes)}"
        val pointerRootEventId = rootEventId ?: eventId
        val pointer = cMap(
            "kind" to CString("event-pointer"),
            "schema" to CString("event-pointer-v0.1"),
            "eventId" to CString(eventId),
            "eventCid" to CString(eventCid),
            "payloadCid" to CString(payloadCid),
            "timePath" to CList(
                listOf(time.year, time.month, time.day, time.hour).map {
                    org.takopi.percept.core.canonical.CLong(it.toLong())
                },
            ),
            "timePrefixKeys" to stringList(timePrefixKeys(time)),
            "actorPath" to stringList(actorPath),
            "actorPrefixKeys" to stringList(prefixKeys(actorPath)),
            "channelPath" to stringList(channelPath),
            "channelPrefixKeys" to stringList(prefixKeys(channelPath)),
            "valueKind" to CString(valueKind),
            "parentEventIds" to stringList(parentEventIds),
            "rootEventId" to CString(pointerRootEventId),
            "inputEventIds" to stringList(inputEventIds),
            "outputArtifactIds" to stringList(outputArtifactIds),
        )
        val ack = index.putEvent(pointer)
        return IngestedEvent(eventId, eventCid, payloadCid, envelope, pointer, ack)
    }
}

fun artifactId(kind: String, artifact: CMap): String = contentId(kind, artifact)

private fun unknownProvenance(): CMap = cMap(
    "source" to CString("unknown"),
    "observedBy" to CString("omega-claw"),
    "ingestionPipeline" to CString("event-trace-v0"),
)

private fun workerProvenance(): CMap = cMap(
    "source" to CString("worker"),
    "observedBy" to CString("omega-claw"),
    "ingestionPipeline" to CString("event-trace-v0"),
)

internal fun CMap.stringOrNull(key: String): String? = when (val value = entries[key]) {
    is CString -> value.value
    CNull -> null
    null -> null
    else -> throw IllegalStateException("field $key is not a string")
}
