package com.strybok.kmp.internal

import com.strybok.kmp.CreatedLink
import com.strybok.kmp.LinkCampaign
import com.strybok.kmp.ResolvedDeepLink
import com.strybok.kmp.StrybokValidationIssue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val emptyPayload = JsonObject(emptyMap())

/** Parses a 200 resolve response; `null` when it is malformed or its route is unsafe. */
internal fun parseResolveResponse(
    url: String,
    body: String,
    isDeferred: Boolean = false,
    isProbabilisticMatch: Boolean = false,
): ResolvedDeepLink? {
    val json = try {
        Json.parseToJsonElement(body)
    } catch (_: IllegalArgumentException) { // SerializationException
        return null
    }
    if (json !is JsonObject) return null
    val linkId = json.string("linkId") ?: return null
    val appId = json.string("appId") ?: return null
    val routePath = json.string("routePath") ?: return null
    if (!routePath.startsWith("/") || routePath.startsWith("//")) return null
    val payload = json["payload"] as? JsonObject ?: emptyPayload
    return ResolvedDeepLink(
        url = url,
        linkId = linkId,
        appId = appId,
        routePath = routePath,
        payload = payload.mapValues { it.value.toPlainValue() },
        payloadJson = payload.toString(),
        campaign = parseCampaign(json["campaign"]),
        isDeferred = isDeferred,
        isProbabilisticMatch = isProbabilisticMatch,
    )
}

/**
 * Parses a 200 answer of the redeem or match endpoint: `deferred` must be `true`, `url` a link
 * of this app, the rest as for resolving; with [probabilistic] also `matched` =
 * `"probabilistic"`. `null` for anything else. Unknown fields are ignored.
 */
internal fun parseDeferredResponse(body: String, allowedHosts: Set<String>, probabilistic: Boolean): ResolvedDeepLink? {
    val json = parseObject(body) ?: return null
    val deferred = (json["deferred"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    if (deferred != true) return null
    if (probabilistic && json.string("matched") != "probabilistic") return null
    val url = json.string("url") ?: return null
    // An answer that points anywhere but at a link of this app is dropped. The service offers
    // deferred links for stored links only, so `url` must have a single slug path even with the
    // `parameterLinks` option.
    if (parseLinkUrl(url, allowedHosts) == null) return null
    return parseResolveResponse(url, body, isDeferred = true, isProbabilisticMatch = probabilistic)
}

/** Keeps the five string fields; anything else, or no object at all, is not an error. */
private fun parseCampaign(json: JsonElement?): LinkCampaign {
    if (json !is JsonObject) return LinkCampaign.EMPTY
    val campaign = LinkCampaign(
        source = json.string("source"),
        medium = json.string("medium"),
        campaign = json.string("campaign"),
        term = json.string("term"),
        content = json.string("content"),
    )
    return if (campaign.isEmpty) LinkCampaign.EMPTY else campaign
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toPlainValue() }
    is JsonObject -> mapValues { it.value.toPlainValue() }
}

/**
 * Body of the `app_open` event. The API rejects any other field.
 *
 * [campaign] is what resolve returned, unchanged, and is left out when it is empty.
 */
internal fun appOpenEventBody(
    linkId: String,
    platform: String,
    sdkVersion: String,
    campaign: LinkCampaign = LinkCampaign.EMPTY,
): String =
    buildJsonObject {
        put("eventId", Uuid.random().toString()) // Random UUID v4, lower-case.
        put("linkId", linkId)
        put("type", "app_open")
        put("platform", platform)
        put("sdkVersion", sdkVersion.take(32))
        if (!campaign.isEmpty) {
            put(
                "campaign",
                buildJsonObject {
                    campaign.source?.let { put("source", it) }
                    campaign.medium?.let { put("medium", it) }
                    campaign.campaign?.let { put("campaign", it) }
                    campaign.term?.let { put("term", it) }
                    campaign.content?.let { put("content", it) }
                },
            )
        }
    }.toString()

/** A payload value that JSON cannot express. The message never quotes payload content. */
internal class NotJsonException(message: String) : Exception(message)

private const val MAX_PAYLOAD_DEPTH = 64

/**
 * Body of `POST /v1/public/links`. Arguments that were not given are left out: the API rejects
 * unknown fields, and SDKs do not send `null`.
 *
 * @throws NotJsonException when [payload] holds something that is not plain JSON data.
 */
internal fun createLinkBody(
    routePath: String,
    payload: Map<String, Any?>?,
    title: String?,
    description: String?,
    imageUrl: String?,
): String = buildJsonObject {
    put("routePath", routePath)
    if (payload != null) put("payload", payload.toJsonElement(0))
    if (title != null) put("title", title)
    if (description != null) put("description", description)
    if (imageUrl != null) put("imageUrl", imageUrl)
}.toString()

private fun Any?.toJsonElement(depth: Int): JsonElement {
    if (depth > MAX_PAYLOAD_DEPTH) throw NotJsonException("payload is nested too deeply")
    return when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int, is Long, is Short, is Byte -> JsonPrimitive(this as Number)
        is Double -> if (isFinite()) JsonPrimitive(this) else throw notFinite()
        is Float -> if (isFinite()) JsonPrimitive(this) else throw notFinite()
        is Map<*, *> -> JsonObject(
            buildMap {
                this@toJsonElement.forEach { (key, value) ->
                    if (key !is String) throw NotJsonException("payload contains a map whose keys are not strings")
                    put(key, value.toJsonElement(depth + 1))
                }
            },
        )
        is Iterable<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
        is Array<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
        else -> throw NotJsonException(
            "payload contains a value that is not JSON data (${this::class.simpleName}); " +
                "use strings, booleans, numbers, null, lists and maps with string keys",
        )
    }
}

private fun notFinite() = NotJsonException("payload contains a number that is not finite")

/** Parses a 200/201 create-link response; `null` unless it has everything a created link needs. */
internal fun parseCreateLinkResponse(body: String): CreatedLink? {
    val json = parseObject(body) ?: return null
    val url = json.string("url") ?: return null
    if (!url.startsWith("https://", ignoreCase = true)) return null
    return CreatedLink(
        url = url,
        slug = json.string("slug") ?: return null,
        routePath = json.string("routePath") ?: return null,
        linkId = json.string("linkId") ?: return null,
    )
}

/** `error.message` and `error.details.issues` of an error answer, as far as they are present. */
internal fun parseValidationError(body: String): Pair<String?, List<StrybokValidationIssue>> {
    val error = parseObject(body)?.get("error") as? JsonObject ?: return null to emptyList()
    val issues = ((error["details"] as? JsonObject)?.get("issues") as? JsonArray).orEmpty().mapNotNull { issue ->
        val message = (issue as? JsonObject)?.string("message") ?: return@mapNotNull null
        StrybokValidationIssue(issue.string("path") ?: "", message)
    }
    return error.string("message")?.takeIf { it.isNotBlank() } to issues
}

/**
 * `error.code` and `error.details.reason` of an error answer. The reason is only passed on when
 * it looks like one of the service's reason words, never free text.
 */
internal fun parseAttestationError(body: String): Pair<String?, String?> {
    val error = parseObject(body)?.get("error") as? JsonObject ?: return null to null
    val reason = (error["details"] as? JsonObject)?.string("reason")
        ?.takeIf { it.length in 1..32 && it.all { char -> char in 'a'..'z' || char == '_' } }
    return error.string("code") to reason
}

/** Seconds from a `Retry-After` header; `null` when it is missing or not a number of seconds. */
internal fun parseRetryAfterSeconds(header: String?): Long? {
    val value = header?.trim().orEmpty()
    if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
    return value.toLongOrNull()
}

private fun parseObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (_: IllegalArgumentException) { // SerializationException
    null
}
