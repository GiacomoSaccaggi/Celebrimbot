package com.github.giacomosaccaggi.celebrimbot.model

data class CelebrimbotTask(
    val id: Int,
    val action: String, // "read_psi", "write_code", "run_terminal"
    val target: String? = null,
    val instruction: String? = null,
    val command: String? = null
)

data class CelebrimbotPlan(
    val tasks: List<CelebrimbotTask>
)
