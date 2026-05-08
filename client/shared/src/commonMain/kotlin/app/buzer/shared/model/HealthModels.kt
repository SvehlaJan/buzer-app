package app.buzer.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class HealthLevel {
    ok,
    warning,
    error
}

@Serializable
data class HealthStatus(
    val key: String,
    val level: HealthLevel,
    val message: String
)
