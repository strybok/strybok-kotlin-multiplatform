package com.strybok.kmp.internal

import com.strybok.kmp.ResolvedDeepLink
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
internal fun parseResolveResponse(url: String, body: String): ResolvedDeepLink? {
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
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toPlainValue() }
    is JsonObject -> mapValues { it.value.toPlainValue() }
}

/** Body of the `app_open` event. The API rejects any other field. */
internal fun appOpenEventBody(linkId: String, platform: String, sdkVersion: String): String =
    buildJsonObject {
        put("eventId", Uuid.random().toString()) // Random UUID v4, lower-case.
        put("linkId", linkId)
        put("type", "app_open")
        put("platform", platform)
        put("sdkVersion", sdkVersion.take(32))
    }.toString()
