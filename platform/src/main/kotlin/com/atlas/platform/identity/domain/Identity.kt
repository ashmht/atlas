package com.atlas.platform.identity.domain

import com.atlas.platform.shared.*

/**
 * Identity & Organizations — multi-tenant boundary and the RBAC model. Every
 * request is scoped to an OrganizationId; authorization is enforced by scope-based
 * authorities checked at the API edge (see JournalEntryController @PreAuthorize).
 */
enum class Role(val scopes: Set<String>) {
    VIEWER(setOf("ledger:read", "reports:read")),
    OPERATOR(setOf("ledger:read", "deposits:review", "settlement:submit")),
    TREASURER(setOf("ledger:read", "treasury:allocate")),
    SERVICE(setOf("ledger:read", "ledger:write", "settlement:submit")),
    ADMIN(setOf("ledger:read", "ledger:write", "deposits:review", "settlement:submit", "treasury:allocate", "org:admin"));
}

data class Organization(val id: OrganizationId, val name: String, val kycTier: Int)
data class Member(val organizationId: OrganizationId, val subject: String, val roles: Set<Role>) {
    val scopes: Set<String> get() = roles.flatMap { it.scopes }.toSet()
}
