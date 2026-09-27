package com.swipeyt.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Null-safe navigation over untyped InnerTube JSON. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.contentOrNull

val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.intOrNull

val JsonElement?.array: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val JsonElement?.isNullOrMissing: Boolean get() = this == null || this is JsonNull

/** Concatenated text of a `{ runs: [{ text }] }` node. */
val JsonElement?.runsText: String?
    get() = this["runs"].array.mapNotNull { it["text"].string }.takeIf { it.isNotEmpty() }?.joinToString("")

/** Depth-first collection of every value stored under [key], at any nesting level. */
fun JsonElement?.findAll(key: String): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun walk(node: JsonElement?) {
        when (node) {
            is JsonObject -> node.forEach { (k, v) ->
                if (k == key) out += v
                walk(v)
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}

fun JsonElement?.findFirst(key: String): JsonElement? {
    var result: JsonElement? = null
    fun walk(node: JsonElement?): Boolean {
        when (node) {
            is JsonObject -> {
                node[key]?.let { result = it; return true }
                for (v in node.values) if (walk(v)) return true
            }
            is JsonArray -> for (v in node) if (walk(v)) return true
            else -> Unit
        }
        return false
    }
    walk(this)
    return result
}
