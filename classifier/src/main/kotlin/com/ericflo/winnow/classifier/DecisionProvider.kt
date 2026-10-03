package com.ericflo.winnow.classifier

/**
 * Anything that can answer typed [Choice] questions about a state with probability
 * distributions.
 *
 * The interface follows the System One decision shape that TypeSafe's Jev speaks
 * natively, so a Jev endpoint plugs in with no translation. Nothing here depends on Jev:
 * a generic LLM, an on-device model, a rules engine or a test fake can implement it, and
 * the rest of the app only ever sees this interface.
 */
interface DecisionProvider {
    val descriptor: ProviderDescriptor

    /**
     * Answers every question in [request]. Returns a distribution for each question key.
     *
     * @throws ProviderException when the provider cannot answer (network, auth, bad output).
     */
    suspend fun decide(request: DecisionRequest): DecisionResponse
}

data class ProviderDescriptor(
    /** Stable id, recorded with every verdict, e.g. `systemone:typesafe`. */
    val id: String,
    val displayName: String,
    /** Where message content goes when this provider runs. The privacy gate checks it. */
    val dataHandling: DataHandling,
)

enum class DataHandling {
    /** Nothing leaves the phone. */
    ON_DEVICE,

    /** Sent to a remote service that retains nothing (zero data retention). */
    REMOTE_ZERO_RETENTION,

    /** Sent to a remote service that may log or retain it. */
    REMOTE,
}

class ProviderException(
    message: String,
    /** True for transient failures (timeouts, 429, 5xx) worth retrying later. */
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)
