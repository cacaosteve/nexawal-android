package com.nexatrode.nexawal.logic

data class LastKnownGoodDecision<T>(
    val value: T,
    val accepted: Boolean,
    val errorMessage: String? = null,
)

/** Prevents a transient decode failure from replacing valid UI state with an empty value. */
object LastKnownGoodPolicy {
    fun <T> choose(current: T, candidate: Result<T>): LastKnownGoodDecision<T> =
        candidate.fold(
            onSuccess = { LastKnownGoodDecision(value = it, accepted = true) },
            onFailure = {
                LastKnownGoodDecision(
                    value = current,
                    accepted = false,
                    errorMessage = it.message ?: it.javaClass.simpleName,
                )
            },
        )
}
