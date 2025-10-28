package com.example.bot.notifications

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON based DSL for audience segmentation.
 *
 * Example:
 * ```json
 * {"op":"AND","items":[
 *   {"field":"club_id","op":"IN","args":[1,2]},
 *   {"field":"opt_in","op":"=","args":[true]},
 *   {"field":"lang","op":"IN","args":["ru","en"]},
 *   {"field":"last_visit_days","op":"<=","args":[60]}
 * ]}
 * ```
 */
@Serializable
data class SegmentNode(
    /** Operator: AND/OR/NOT for logical nodes, comparison for leaves. */
    val op: String,
    /** Children for logical operators. */
    val items: List<SegmentNode> = emptyList(),
    /** Field name for leaf nodes. */
    val field: String? = null,
    /** Arguments for the operator. */
    val args: List<JsonElement> = emptyList(),
)

/** Identifier of a saved segment. */
@JvmInline
value class SegmentId(val value: Long)

/** Identifier of user. */
@JvmInline
value class UserId(val value: Long)
