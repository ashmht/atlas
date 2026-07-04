package com.atlas.platform.ledger.adapter.out.persistence

import com.atlas.platform.ledger.domain.Account
import com.atlas.platform.ledger.domain.AccountType
import com.atlas.platform.ledger.domain.Direction
import com.atlas.platform.ledger.domain.EntryKind
import com.atlas.platform.ledger.domain.JournalEntry
import com.atlas.platform.ledger.domain.Posting
import com.atlas.platform.ledger.port.AccountRepository
import com.atlas.platform.ledger.port.JournalEntryRepository
import com.atlas.platform.ledger.port.LedgerEvent
import com.atlas.platform.ledger.port.LedgerEventOutbox
import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.Asset
import com.atlas.platform.shared.IdempotencyKey
import com.atlas.platform.shared.JournalEntryId
import com.atlas.platform.shared.Money
import com.atlas.platform.shared.OrganizationId
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ---------- JPA entities (persistence model, distinct from the domain model) ----------

@Entity
@Table(name = "ledger_account")
class AccountEntity(
    @Id val id: UUID,
    @Column(name = "organization_id") val organizationId: UUID,
    val code: String,
    @Enumerated(EnumType.STRING) val type: AccountType,
    @Enumerated(EnumType.STRING) val asset: Asset,
    @Column(name = "allow_negative") val allowNegative: Boolean,
    @Column(name = "balance_amount") var balanceAmount: BigDecimal,
    @Version var version: Long = 0,
)

/**
 * The bidirectional mapping is load-bearing, not stylistic. A unidirectional
 * @OneToMany + @JoinColumn makes Hibernate persist a posting as INSERT followed
 * by an UPDATE that sets entry_id — and the ledger's immutability trigger
 * rejects UPDATE on ledger_posting, which would fail every post at flush. With
 * @ManyToOne ownership on the posting side (insertable, non-updatable),
 * Hibernate writes the FK in the INSERT itself and never issues an UPDATE.
 */
@Entity
@Table(name = "ledger_journal_entry")
class JournalEntryEntity(
    @Id val id: UUID,
    @Column(name = "organization_id") val organizationId: UUID,
    @Column(name = "idempotency_key") val idempotencyKey: String,
    val description: String,
    @Enumerated(EnumType.STRING) val kind: EntryKind,
    @Column(name = "reverses_entry_id") val reversesEntryId: UUID?,
    @Column(name = "effective_at") val effectiveAt: Instant,
    @OneToMany(mappedBy = "entry", cascade = [CascadeType.PERSIST], fetch = FetchType.EAGER)
    val postings: MutableList<PostingEntity> = mutableListOf(),
)

@Entity
@Table(name = "ledger_posting")
class PostingEntity(
    @Id val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    val entry: JournalEntryEntity,
    @Column(name = "account_id") val accountId: UUID,
    @Enumerated(EnumType.STRING) val direction: Direction,
    @Column(name = "amount") val amount: BigDecimal,
    @Enumerated(EnumType.STRING) val asset: Asset,
)

@Entity
@Table(name = "ledger_outbox")
class OutboxEntity(
    @Id val id: UUID,
    @Column(name = "aggregate_id") val aggregateId: UUID,
    @Column(name = "organization_id") val organizationId: UUID,
    @Column(name = "event_type") val eventType: String,
    // Map, not String: @JdbcTypeCode(JSON) on a String runs the value through
    // the Jackson format mapper, which serializes a String to a QUOTED JSON
    // string literal — the payload would land double-encoded ("{\"entryId\"...}")
    // and every downstream consumer would need a double parse. A Map serializes
    // to a genuine JSON object.
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload") val payload: Map<String, Any>,
    @Column(name = "created_at") val createdAt: Instant = Instant.now(),
    @Column(name = "published_at") var publishedAt: Instant? = null,
)

// ---------- Spring Data repositories ----------

@Repository
interface AccountJpa : JpaRepository<AccountEntity, UUID> {
    /**
     * Locks rows FOR UPDATE. The ORDER BY makes the database acquire locks in a
     * single canonical order for every transaction, which is what actually
     * prevents deadlocks — any application-side sort is irrelevant since the DB
     * executes this query. (Java's UUID.compareTo uses signed-long comparison
     * and does NOT agree with Postgres uuid ordering; relying on it would be a
     * trap, so the DB ordering is the only ordering.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id in :ids order by a.id")
    fun lockAll(@Param("ids") ids: Set<UUID>): List<AccountEntity>

    fun findByOrganizationIdAndCode(org: UUID, code: String): AccountEntity?
}

@Repository
interface JournalEntryJpa : JpaRepository<JournalEntryEntity, UUID> {
    fun findByOrganizationIdAndIdempotencyKey(org: UUID, key: String): JournalEntryEntity?
}

@Repository
interface OutboxJpa : JpaRepository<OutboxEntity, UUID> {
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(): List<OutboxEntity>
}

// ---------- Adapters mapping persistence <-> domain ----------

@Repository
class AccountRepositoryAdapter(private val jpa: AccountJpa) : AccountRepository {
    override fun lockAll(ids: Set<AccountId>): List<Account> =
        jpa.lockAll(ids.map { it.value }.toSet()).map(::toDomain)

    override fun findByCode(org: OrganizationId, code: String): Account? =
        jpa.findByOrganizationIdAndCode(org.value, code)?.let(::toDomain)

    override fun save(account: Account) {
        // Within the transaction the locked entity is already managed; findById
        // returns that same instance from the persistence context (no extra
        // query, lock retained). The @Version column increments on flush and
        // defends any write path that bypasses lockAll.
        val e = jpa.findById(account.id.value).orElseThrow()
        e.balanceAmount = account.balance.amount
        jpa.save(e)
    }

    override fun open(account: Account): Account {
        jpa.save(
            AccountEntity(
                id = account.id.value,
                organizationId = account.organizationId.value,
                code = account.code,
                type = account.type,
                asset = account.asset,
                allowNegative = account.allowNegative,
                balanceAmount = account.balance.amount,
                version = 0,
            )
        )
        return account
    }

    private fun toDomain(e: AccountEntity): Account = Account.rehydrate(
        id = AccountId(e.id),
        organizationId = OrganizationId(e.organizationId),
        code = e.code, type = e.type, asset = e.asset,
        allowNegative = e.allowNegative,
        // NUMERIC(38,8) comes back at scale 8; Money.of drops the zero tail and
        // throws (ArithmeticException) if a non-zero sub-precision digit ever
        // appears — which would indicate a corrupt write and must never be
        // rounded over silently.
        balance = Money.of(e.balanceAmount, e.asset),
        version = e.version,
    )
}

@Repository
class JournalEntryRepositoryAdapter(private val jpa: JournalEntryJpa) : JournalEntryRepository {
    override fun save(entry: JournalEntry) {
        val entity = JournalEntryEntity(
            id = entry.id.value,
            organizationId = entry.organizationId.value,
            idempotencyKey = entry.idempotencyKey.value,
            description = entry.description,
            kind = entry.kind,
            reversesEntryId = entry.reversesEntryId?.value,
            effectiveAt = entry.effectiveAt,
        )
        entry.postings.forEach {
            entity.postings += PostingEntity(
                id = UUID.randomUUID(),
                entry = entity,
                accountId = it.accountId.value,
                direction = it.direction,
                amount = it.amount.amount,
                asset = it.amount.asset,
            )
        }
        // saveAndFlush, not save: with default commit-time flushing a
        // UNIQUE(org, idempotency_key) violation fires inside the transaction
        // interceptor's commit and surfaces as TransactionSystemException, which
        // the API layer's idempotency-race handler cannot distinguish. Flushing
        // here makes Spring's exception translation produce a catchable
        // DataIntegrityViolationException.
        jpa.saveAndFlush(entity)
    }

    override fun findById(id: JournalEntryId): JournalEntry? =
        jpa.findById(id.value).map(::toDomain).orElse(null)

    override fun findByIdempotencyKey(org: OrganizationId, key: IdempotencyKey): JournalEntry? =
        jpa.findByOrganizationIdAndIdempotencyKey(org.value, key.value)?.let(::toDomain)

    private fun toDomain(e: JournalEntryEntity): JournalEntry = JournalEntry.rehydrate(
        id = JournalEntryId(e.id),
        organizationId = OrganizationId(e.organizationId),
        idempotencyKey = IdempotencyKey(e.idempotencyKey),
        description = e.description,
        postings = e.postings.map {
            Posting(AccountId(it.accountId), it.direction, Money.of(it.amount, it.asset))
        },
        kind = e.kind,
        reversesEntryId = e.reversesEntryId?.let(::JournalEntryId),
        effectiveAt = e.effectiveAt,
    )
}

/**
 * Transactional outbox adapter. enqueue() runs inside the caller's transaction,
 * so the event is committed atomically with the ledger mutation; the relay below
 * publishes committed rows to Kafka and marks them, giving at-least-once delivery
 * with exactly-once *effect* (consumers dedupe on entry id).
 */
@Component
class OutboxAdapter(
    private val jpa: OutboxJpa,
) : LedgerEventOutbox {
    override fun enqueue(event: LedgerEvent) {
        val (type, payload) = when (event) {
            is LedgerEvent.JournalEntryPosted -> "JournalEntryPosted" to mapOf(
                "entryId" to event.entryId.value.toString(),
                "organizationId" to event.organizationId.value.toString(),
                "description" to event.description,
                "postingCount" to event.postingCount,
            )
            is LedgerEvent.JournalEntryReversed -> "JournalEntryReversed" to mapOf(
                "entryId" to event.entryId.value.toString(),
                "organizationId" to event.organizationId.value.toString(),
                "reversesEntryId" to event.reversesEntryId.value.toString(),
            )
        }
        jpa.save(
            OutboxEntity(
                id = UUID.randomUUID(),
                aggregateId = event.entryId.value,
                organizationId = event.organizationId.value,
                eventType = type,
                payload = payload,
            )
        )
    }
}

@Component
class OutboxRelay(
    private val jpa: OutboxJpa,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Polling relay, publish-then-mark: a crash between broker ack and commit
     * repeats the publish — at-least-once by design; idempotent consumers
     * (dedupe on entry id) make that safe. Two failure modes are guarded here:
     *
     *   1. LOST EVENTS — KafkaTemplate.send() is asynchronous, so marking
     *      publishedAt before the send completes would let a broker outage leave
     *      rows marked published whose sends later fail: silent, permanent event
     *      loss, the one thing an outbox exists to prevent. Each send is
     *      confirmed (blocking get with timeout) BEFORE the row is marked.
     *
     *   2. HEAD-OF-LINE POISON — failures are isolated per row so a single row
     *      that always fails cannot roll back the whole batch, unmark successful
     *      sends, or block everything queued behind it. Successes stay marked;
     *      the failed row is logged and retried next poll.
     *
     * Single-writer semantics come from ShedLock in the production profile; at
     * this stage the deployment runs one relay replica.
     */
    @Scheduled(fixedDelayString = "\${atlas.outbox.poll-ms:500}")
    @Transactional
    fun relay() {
        val batch = jpa.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()
        var sent = 0
        for (row in batch) {
            try {
                kafka.send(
                    "atlas.ledger.journal-entry.v1",
                    row.aggregateId.toString(),
                    objectMapper.writeValueAsString(row.payload),
                ).get(10, java.util.concurrent.TimeUnit.SECONDS) // confirm broker ack
                row.publishedAt = Instant.now()
                sent++
            } catch (e: Exception) {
                log.warn("outbox publish failed for {} — will retry next poll: {}", row.id, e.message)
            }
        }
        if (sent > 0) log.info("relayed {}/{} outbox events", sent, batch.size)
    }
}
