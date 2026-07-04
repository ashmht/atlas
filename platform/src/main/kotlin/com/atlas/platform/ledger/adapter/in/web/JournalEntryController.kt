package com.atlas.platform.ledger.adapter.`in`.web

import com.atlas.platform.ledger.application.PostEntryCommand
import com.atlas.platform.ledger.application.PostJournalEntryService
import com.atlas.platform.ledger.domain.Direction
import com.atlas.platform.ledger.domain.Posting
import com.atlas.platform.ledger.port.JournalEntryRepository
import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.Asset
import com.atlas.platform.shared.DomainError
import com.atlas.platform.shared.IdempotencyKey
import com.atlas.platform.shared.Money
import com.atlas.platform.shared.OrganizationId
import com.atlas.platform.shared.Result
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ledger/entries")
class JournalEntryController(
    private val service: PostJournalEntryService,
    private val entries: JournalEntryRepository,
) {

    /**
     * The organization is taken from the verified JWT's `org_id` claim, never
     * from a client-supplied header — a header would let any caller holding
     * ledger:write post into another tenant's ledger. Tenancy rides the token,
     * and the service layer additionally verifies every touched account belongs
     * to the commanding organization.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    fun post(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: PostEntryRequest,
    ): ResponseEntity<Any> {
        val organizationId = jwt.getClaimAsString("org_id")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return badRequest("token is missing a valid org_id claim")

        val command = try {
            PostEntryCommand(
                organizationId = OrganizationId(organizationId),
                idempotencyKey = IdempotencyKey(idempotencyKey),
                description = body.description,
                postings = body.postings.map { it.toDomain() },
            )
        } catch (e: IllegalArgumentException) {
            return unprocessable(e.message ?: "invalid request")     // bad enum / blank key / non-positive amount
        } catch (e: ArithmeticException) {
            return unprocessable("amount precision exceeds asset precision") // e.g. 7dp for USDC
        }

        return try {
            when (val r = service.post(command)) {
                is Result.Ok -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(EntryResponse(r.value.id.value, r.value.kind.name, r.value.postings.size))
                is Result.Err -> r.error.toResponse()
            }
        } catch (e: DataIntegrityViolationException) {
            // IDEMPOTENCY RACE — two first-attempts with the same key ran
            // concurrently; both passed the read check, the loser hit the
            // UNIQUE(org, idempotency_key) constraint and its transaction rolled
            // back. The winner's entry is now committed, so a fresh read (new
            // transaction) returns it: the caller observes exactly-once.
            val existing = entries.findByIdempotencyKey(command.organizationId, command.idempotencyKey)
                ?: throw e // constraint fired for a different reason — surface it
            // 201, matching the read-check replay path: an idempotent replay
            // returns the resource exactly as it was created (Stripe semantics),
            // so identical requests get identical responses on every branch.
            ResponseEntity.status(HttpStatus.CREATED)
                .body(EntryResponse(existing.id.value, existing.kind.name, existing.postings.size))
        } catch (e: ObjectOptimisticLockingFailureException) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "Conflict", "message" to "concurrent modification, retry with the same Idempotency-Key"))
        }
    }

    private fun badRequest(msg: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "BadRequest", "message" to msg))

    private fun unprocessable(msg: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "Validation", "message" to msg))
}

data class PostEntryRequest(val description: String, val postings: List<PostingDto>)

data class PostingDto(val accountId: UUID, val direction: String, val amount: String, val asset: String) {
    /** Throws IllegalArgumentException / ArithmeticException; mapped to 422 above, never a 500. */
    fun toDomain(): Posting {
        val assetEnum = runCatching { Asset.valueOf(asset) }
            .getOrElse { throw IllegalArgumentException("unknown asset: $asset") }
        val dir = runCatching { Direction.valueOf(direction) }
            .getOrElse { throw IllegalArgumentException("direction must be DEBIT or CREDIT") }
        return Posting(AccountId(accountId), dir, Money.of(amount, assetEnum))
    }
}

data class EntryResponse(val id: UUID, val kind: String, val postingCount: Int)

private fun DomainError.toResponse(): ResponseEntity<Any> {
    val status = when (this) {
        is DomainError.AccountNotFound -> HttpStatus.NOT_FOUND
        is DomainError.OptimisticLockConflict -> HttpStatus.CONFLICT
        is DomainError.InsufficientFunds,
        is DomainError.Unbalanced,
        is DomainError.AssetMismatch,
        is DomainError.Validation -> HttpStatus.UNPROCESSABLE_ENTITY
        is DomainError.ComplianceRejected -> HttpStatus.FORBIDDEN
    }
    return ResponseEntity.status(status).body(mapOf("error" to this::class.simpleName, "message" to message))
}
