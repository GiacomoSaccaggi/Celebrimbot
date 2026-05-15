package com.github.giacomosaccaggi.celebrimbot.model

/**
 * The verdict of Treebeard, the Ent Reviewer.
 *
 * Treebeard is never hasty. He reads the original quest, inspects every
 * scroll that was forged, and delivers a slow but thorough judgement on
 * whether the Fellowship's work truly satisfies the user's desire.
 *
 * @param isComplete true if the executed plan fully satisfies the original request.
 * @param reasoning  Treebeard's detailed explanation in his slow, deliberate Entish tone.
 * @param additionalRequestsForPlanner specific missing tasks to send back to the planner
 *        when [isComplete] is false. Empty when [isComplete] is true.
 */
data class TreebeardReviewResult(
    val isComplete: Boolean,
    val reasoning: String,
    val additionalRequestsForPlanner: List<String> = emptyList()
)
