package com.atlas.platform.shared

import java.time.Instant
import java.util.UUID

/**
 * Strongly-typed identifiers. Passing a WalletId where an AccountId is expected
 * is a compile error, not a runtime surprise — this class of bug is expensive in
 * money movement.
 */
@JvmInline value class OrganizationId(val value: UUID) {
    companion object { fun new() = OrganizationId(UUID.randomUUID()) }
}
@JvmInline value class AccountId(val value: UUID) {
    companion object { fun new() = AccountId(UUID.randomUUID()) }
}
@JvmInline value class WalletId(val value: UUID) {
    companion object { fun new() = WalletId(UUID.randomUUID()) }
}
@JvmInline value class JournalEntryId(val value: UUID) {
    companion object { fun new() = JournalEntryId(UUID.randomUUID()) }
}

/**
 * Idempotency is a first-class concept, not a decorator. Every state-changing
 * command carries a caller-supplied key. The ledger deduplicates on (tenant, key)
 * and returns the original result on replay — at-least-once delivery from Kafka,
 * Temporal retries, and client retries all converge to exactly-once effect.
 */
@JvmInline value class IdempotencyKey(val value: String) {
    init { require(value.isNotBlank() && value.length <= 255) { "invalid idempotency key" } }
}

/** Ambient tenant + actor context, propagated via MDC and OTel baggage. */
data class RequestContext(
    val organizationId: OrganizationId,
    val actorId: String,
    val correlationId: String,
    val occurredAt: Instant = Instant.now(),
)

/**
 * Explicit domain errors instead of exceptions-as-control-flow for expected
 * failure modes. Reserved exceptions for truly exceptional / infrastructure faults.
 */
sealed interface DomainError {
    val message: String

    data class Unbalanced(override val message: String) : DomainError
    data class AccountNotFound(val id: AccountId) : DomainError { override val message = "account not found: ${id.value}" }
    data class AssetMismatch(override val message: String) : DomainError
    data class InsufficientFunds(override val message: String) : DomainError
    data class OptimisticLockConflict(override val message: String) : DomainError
    data class ComplianceRejected(override val message: String) : DomainError
    data class Validation(override val message: String) : DomainError
}

/** Minimal Result to keep the domain free of framework exception semantics. */
sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>
    data class Err(val error: DomainError) : Result<Nothing>

    fun <R> map(f: (T) -> R): Result<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }
    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw IllegalStateException(error.message)
    }
}
