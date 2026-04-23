package com.github.giacomosaccaggi.celebrimbot.model

data class CelebrimbotTask(
    val id: Int,
    val action: String,
    val target: String? = null,
    val instruction: String? = null,
    val command: String? = null,
    val query: String? = null,
    val pattern: String? = null,
    val extension: String? = null,
    val worker: String? = null  // "frodo" for intelligent worker, null/omitted = Samwise
)

data class CelebrimbotPlan(
    val tasks: List<CelebrimbotTask>
)
