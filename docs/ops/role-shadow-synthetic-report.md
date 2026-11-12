# Synthetic role shadow report — eight-role revision

**ACCEPTED product decisions; PROPOSED technical permissions. Synthetic only; no migration.**

Source revision: `5550b944990d8c1c93c0a5012d31db209f93ea6d`. Writes: **0**.
Source revision is the base; explicit SHA-256 metadata binds the local unpublished overlay.
Actual inactive implementation/DB evidence: [foundation report](role-assignment-foundation.md).
Snapshot before/after: `5661972be321b116c9de452a047a84a89ba8c2df90b386c926ad238ed1e74d11`.
CURRENT = exact raw authority evidence + bounded client tokens, not full API authorization.
Product duties carry ACCEPTED/PROPOSED separately. Every technical permission binding is PROPOSED; action labels are not new production codes.
Self actions always require the actor's own objects; club scopes never widen personal ownership.
Legacy assignments remain opaque and preserved; no automatic role mapping.

Technical legacy roles: CLUB_ADMIN, ENTRY_MANAGER, GLOBAL_ADMIN, GUEST, HEAD_MANAGER, MANAGER, OWNER, PROMOTER. Legacy scopes: GLOBAL/CLUB; CLIENT is invalid.

Product gates: verified business ownership/explicit finite assignments; no membership inheritance; DEC-010/011/020/021; list/pass separate from accepted promoter booking; accepted operation matrix; canonical night/booking/table; DEC-012/021/029; DEC-012 + explicit monetary/ledger/allocations contract; DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate; redacted roster/ledger/PII/history data contract; accepted appointment hierarchy; proposed exact capability/admission/revoke contract; server exact club/shift scope AND financial capability; no booking-to-finance inheritance; accepted source != financial terms; correction/rewards/UI contract PROPOSED; separate safe emergency contract PROPOSED; no GLOBAL bypass.

## network-owner-finite-clubs-creator-only-one

Synthetic user: `1`. **CURRENT:**

- Membership client 101: active=True, version=3.
- Membership token 101: `client.application.manage`, active=True.
- Existing immutable creator evidence: client/club `101/1001`, owner user `1`; preserved, not transferred.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.team.manage`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.team.manage`.
- client/club `101/1002` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/1.

**PROPOSED technical bindings (not applied; product statuses separate):**

- BUSINESS_OWNER scope `[101, 1001, 1]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.
- BUSINESS_OWNER scope `[101, 1002, 1]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 1]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `client.club.team.manage`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 1]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`.

## club-owner-creator

Synthetic user: `2`. **CURRENT:**

- Membership client 102: active=True, version=3.
- Existing immutable creator evidence: client/club `102/2001`, owner user `2`; preserved, not transferred.
- client/club `102/2001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.team.manage`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.team.manage`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- BUSINESS_OWNER scope `[102, 2001, 2]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[102, 2001, 2]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `client.club.team.manage`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`.

## club-manager-preparation-package

Synthetic user: `3`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`.
- Staff `101/1001/3`: CLUB_ADMIN, active=True, version=2, invitation=301, revoked-through=0.
- Invitation `301`: staff/CLUB_ADMIN/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 3]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 3]`: +∅; −∅; retained `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## floor-manager-name-is-not-package-mapping

Synthetic user: `4`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`.
- Staff `101/1001/4`: MANAGER, active=True, version=2, invitation=401, revoked-through=0.
- Invitation `401`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 4]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 4]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 4]` → MANAGER: would disable ∅; would create ∅; would reactivate ∅; hypothetical future actions lost `table.deposit.record`, `table.release`, `table.seat.booked`. Not executed; current schema does not admit those future action codes.

## entrance-manager-read-is-not-checkin

Synthetic user: `5`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `101/1001/5`: ENTRY_MANAGER, active=True, version=2, invitation=501, revoked-through=0.
- Invitation `501`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ENTRANCE_MANAGER scope `[101, 1001, 5]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 5]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 5]` → ENTRY_MANAGER: would disable ∅; would create ∅; would reactivate ∅; hypothetical future actions lost `entrance.arrival.record`, `night_pass.validate`. Not executed; current schema does not admit those future action codes.
- Old writer preview `[101, 1001, 5]` → CLUB_ADMIN: would disable ∅; would create `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`; would reactivate ∅; hypothetical future actions lost ∅. Not executed; current schema does not admit those future action codes.

## finance-manager-template-is-not-money

Synthetic user: `6`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`.
- Staff `101/1001/6`: CLUB_ADMIN, active=True, version=2, invitation=601, revoked-through=0.
- Invitation `601`: staff/CLUB_ADMIN/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FINANCE_MANAGER scope `[101, 1001, 6]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 6]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 6]` → CLUB_ADMIN: would disable ∅; would create ∅; would reactivate ∅; hypothetical future actions lost `shift.close`, `shift.revenue.write`. Not executed; current schema does not admit those future action codes.

## promoter-two-clubs-no-global

Synthetic user: `7`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- client/club `101/1002` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Legacy assignment `701`: PROMOTER/CLUB/3001; attached permissions: ∅. No automatic client mapping.
- Legacy assignment `702`: PROMOTER/CLUB/3002; attached permissions: ∅. No automatic client mapping.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- PROMOTER scope `[101, 1001, 7]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1002, 7]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope-change candidate, MANUAL ONLY: `{"legacy": [{"id": 701, "role_code": "PROMOTER", "scope_club_id": 3001, "scope_type": "CLUB", "user_id": 7}, {"id": 702, "role_code": "PROMOTER", "scope_club_id": 3002, "scope_type": "CLUB", "user_id": 7}], "target_scope": [101, 1001, 7]}`; legacy authority retained.
- Scope-change candidate, MANUAL ONLY: `{"legacy": [{"id": 701, "role_code": "PROMOTER", "scope_club_id": 3001, "scope_type": "CLUB", "user_id": 7}, {"id": 702, "role_code": "PROMOTER", "scope_club_id": 3002, "scope_type": "CLUB", "user_id": 7}], "target_scope": [101, 1002, 7]}`; legacy authority retained.
- Scope `[101, 1001, 7]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 7]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `LEGACY_MANUAL_MAPPING_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## two-independent-clients

Synthetic user: `8`. **CURRENT:**

- Membership client 101: active=True, version=3.
- Membership client 102: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`.
- client/club `102/2001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `101/1001/8`: MANAGER, active=True, version=2, invitation=801, revoked-through=0.
- Staff `102/2001/8`: ENTRY_MANAGER, active=True, version=2, invitation=802, revoked-through=0.
- Invitation `801`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Invitation `802`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 8]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- ENTRANCE_MANAGER scope `[102, 2001, 8]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 8]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.
- Scope `[102, 2001, 8]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## multiple-assignments-exact-tuple-only

Synthetic user: `9`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`.
- client/club `101/1002` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `101/1001/9`: MANAGER, active=True, version=2, invitation=901, revoked-through=0.
- Legacy assignment `901`: ENTRY_MANAGER/CLUB/3001; attached permissions: ∅. No automatic client mapping.
- Legacy assignment `902`: MANAGER/CLUB/3002; attached permissions: `support.view`, `support.reply`. No automatic client mapping.
- Invitation `901`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 9]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- ENTRANCE_MANAGER scope `[101, 1001, 9]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.
- CLUB_MANAGER scope `[101, 1002, 9]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 9]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `floor.tables.read`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 9]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`; −∅; retained `client.club.read`; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `LEGACY_MANUAL_MAPPING_REQUIRED`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## revoked-grants-old-writer-resurrection

Synthetic user: `10`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.layout.write`, `client.club.booking_rules.write`; tombstones: `client.club.profile.write`, `client.club.calendar.write`; current tokens with read prerequisite: `client.club.read`, `client.club.layout.write`, `client.club.booking_rules.write`.
- Staff `101/1001/10`: MANAGER, active=True, version=5, invitation=1001, revoked-through=1000.
- Invitation `1001`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 1/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 10]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 10]`: +`client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`; −∅; retained `client.club.booking_rules.write`, `client.club.layout.write`, `client.club.read`; restoration attempts `client.club.calendar.write`, `client.club.profile.write`.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION`.
- Old writer preview `[101, 1001, 10]` → MANAGER: would disable ∅; would create ∅; would reactivate `client.club.calendar.write`, `client.club.profile.write`; hypothetical future actions lost `entrance.arrival.record`. Not executed; current schema does not admit those future action codes.

## inactive-membership-active-stored-grants

Synthetic user: `11`. **CURRENT:**

- Membership client 101: active=False, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.modules.read`, `client.club.modules.write`; tombstones: ∅; current tokens with read prerequisite: ∅.
- Staff `101/1001/11`: CLUB_ADMIN, active=True, version=2, invitation=1101, revoked-through=0.
- Invitation `1101`: staff/CLUB_ADMIN/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 11]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `MEMBERSHIP_INACTIVE_OR_MISSING`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 11]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `MEMBERSHIP_INACTIVE_OR_MISSING`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## client-only-revoke-legacy-access-remains

Synthetic user: `12`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: ∅; tombstones: `client.club.read`; current tokens with read prerequisite: ∅.
- Staff `101/1001/12`: ENTRY_MANAGER, active=False, version=4, invitation=1201, revoked-through=1205.
- Legacy assignment `1201`: ENTRY_MANAGER/CLUB/3001; attached permissions: ∅. No automatic client mapping.
- Invitation `1201`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ENTRANCE_MANAGER scope `[101, 1001, 12]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `INACTIVE_STAFF_NEW_ADMISSION_REQUIRED`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 12]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `INACTIVE_STAFF_NEW_ADMISSION_REQUIRED`, `LEGACY_MANUAL_MAPPING_REQUIRED`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## duplicate-legacy-global-owner-no-network-adoption

Synthetic user: `13`. **CURRENT:**

- Legacy assignment `1301`: OWNER/GLOBAL/None; attached permissions: ∅. No automatic client mapping.
- Legacy assignment `1302`: OWNER/GLOBAL/None; attached permissions: ∅. No automatic client mapping.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- BUSINESS_OWNER scope `[101, 1001, 13]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `MEMBERSHIP_INACTIVE_OR_MISSING`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.
- BUSINESS_OWNER scope `[101, 1002, 13]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `MEMBERSHIP_INACTIVE_OR_MISSING`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope-change candidate, MANUAL ONLY: `{"legacy": [{"id": 1301, "role_code": "OWNER", "scope_club_id": null, "scope_type": "GLOBAL", "user_id": 13}, {"id": 1302, "role_code": "OWNER", "scope_club_id": null, "scope_type": "GLOBAL", "user_id": 13}], "target_scope": [101, 1001, 13]}`; legacy authority retained.
- Scope-change candidate, MANUAL ONLY: `{"legacy": [{"id": 1301, "role_code": "OWNER", "scope_club_id": null, "scope_type": "GLOBAL", "user_id": 13}, {"id": 1302, "role_code": "OWNER", "scope_club_id": null, "scope_type": "GLOBAL", "user_id": 13}], "target_scope": [101, 1002, 13]}`; legacy authority retained.
- Scope `[101, 1001, 13]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 13]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `LEGACY_GLOBAL_BYPASS_NOT_NETWORK_AUTHORITY`, `LEGACY_MANUAL_MAPPING_REQUIRED`, `MEMBERSHIP_INACTIVE_OR_MISSING`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`.

## negative-input-legacy-client-scope-invalid

Synthetic user: `14`. **CURRENT:**

- Membership client 101: active=True, version=3.
- Legacy assignment `1401`: MANAGER/CLIENT/None; attached permissions: ∅. No automatic client mapping.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 14]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope-change candidate, MANUAL ONLY: `{"legacy": [{"id": 1401, "role_code": "MANAGER", "scope_club_id": null, "scope_type": "CLIENT", "user_id": 14}], "target_scope": [101, 1001, 14]}`; legacy authority retained.
- Scope `[101, 1001, 14]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `INVALID_LEGACY_SCOPE_OR_ROLE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## old-staff-invitation-no-target-assignment

Synthetic user: `15`. **CURRENT:**

- Membership client 101: active=True, version=3.
- Invitation `1501`: staff/ENTRY_MANAGER/claimed; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ∅ — no explicit valid target; role-name matching is disabled.

**DIFFERENCE (hypothetical replacement only):**


**RISK:** `NO_EXPLICIT_TARGET_NO_AUTOMATIC_ROLE_ASSIGNMENT`.

## inactive-staff-active-grants-contradiction

Synthetic user: `16`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`, `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`.
- Staff `101/1001/16`: MANAGER, active=False, version=2, invitation=1601, revoked-through=1605.
- Invitation `1601`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 16]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `INACTIVE_STAFF_NEW_ADMISSION_REQUIRED`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 16]`: +∅; −`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `INACTIVE_STAFF_NEW_ADMISSION_REQUIRED`, `INACTIVE_STAFF_WITH_ACTIVE_GRANTS_NOT_AUTHORITY_DENIAL`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## active-write-with-inactive-read

Synthetic user: `17`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.profile.write`, `client.club.layout.write`, `client.club.calendar.write`, `client.club.booking_rules.write`; tombstones: `client.club.read`; current tokens with read prerequisite: ∅.
- Staff `101/1001/17`: MANAGER, active=True, version=2, invitation=1701, revoked-through=0.
- Invitation `1701`: staff/MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 17]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 17]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts `client.club.read`.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION`.

## same-technical-role-no-explicit-target

Synthetic user: `18`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `101/1001/18`: ENTRY_MANAGER, active=True, version=2, invitation=1801, revoked-through=0.
- Invitation `1801`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ∅ — no explicit valid target; role-name matching is disabled.

**DIFFERENCE (hypothetical replacement only):**


**RISK:** `NO_EXPLICIT_TARGET_NO_AUTOMATIC_ROLE_ASSIGNMENT`.

## guest-personal-data-only

Synthetic user: `19`. **CURRENT:**

- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- GUEST scope `SELF:19`: token intent ∅; action intent `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `GUEST_OWNERSHIP_STOREFRONT_AND_CUTOVER_GATES`, `SELF_CURRENT_AUTHORIZATION_UNVERIFIED`; policy gates ∅.

**DIFFERENCE (hypothetical replacement only):**

- Scope `SELF:19`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `GUEST_OWNERSHIP_STOREFRONT_AND_CUTOVER_GATES`, `SELF_CURRENT_AUTHORIZATION_UNVERIFIED`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## network-manager-finite-assigned-set

Synthetic user: `20`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- client/club `101/1002` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- NETWORK_MANAGER scope `[101, 1001, 20]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.roster.redacted.read`.
- NETWORK_MANAGER scope `[101, 1002, 20]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 20]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 20]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## cross-client-and-global-target-rejected

Synthetic user: `21`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 2001, 21]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `CLIENT_CLUB_MAPPING_MISMATCH`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 2001, 21]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `CLIENT_CLUB_MAPPING_MISMATCH`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_GLOBAL_OR_ALL_CLUBS_OR_IMPLICIT_SCOPE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## inactive-and-archived-client

Synthetic user: `22`. **CURRENT:**

- Membership client 103: active=True, version=3.
- Membership client 104: active=True, version=3.
- client/club `103/3003` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: ∅.
- client/club `104/4001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: ∅.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[103, 3003, 22]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `CLIENT_INACTIVE_OR_ARCHIVED`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- FLOOR_MANAGER scope `[104, 4001, 22]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `CLIENT_INACTIVE_OR_ARCHIVED`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[103, 3003, 22]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.
- Scope `[104, 4001, 22]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `CLIENT_INACTIVE_OR_ARCHIVED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## bigint-identity-not-rounded

Synthetic user: `9007199254740993`. **CURRENT:**

- Membership client 102: active=True, version=3.
- client/club `102/2001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `102/2001/9007199254740993`: ENTRY_MANAGER, active=True, version=2, invitation=900719925474099301, revoked-through=0.
- Invitation `900719925474099301`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ENTRANCE_MANAGER scope `[102, 2001, 9007199254740993]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[102, 2001, 9007199254740993]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## accepted-invitation-expiry-does-not-revoke

Synthetic user: `24`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Staff `101/1001/24`: ENTRY_MANAGER, active=True, version=2, invitation=2401, revoked-through=0.
- Invitation `2401`: staff/ENTRY_MANAGER/accepted; expires_at=2026-01-01T00:00:00Z; preserved, not translated.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- ENTRANCE_MANAGER scope `[101, 1001, 24]`: token intent ∅; action intent `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-010/011/020/021; list/pass separate from accepted promoter booking`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 24]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `entrance.arrival.record`, `entrance.denial.record`, `entrance.entries.search`, `night_pass.validate`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

## business-owner-single-club

Synthetic user: `51`. **CURRENT:**

- Membership client 101: active=True, version=1.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- BUSINESS_OWNER scope `[101, 1001, 51]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 51]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`.
- Future policy probe finance `[101, 1001, 51]` / `finance.closed_totals.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## same-business-owner-second-club-no-grant-inheritance

Synthetic user: `51`. **CURRENT:**

- Membership client 101: active=True, version=1.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- BUSINESS_OWNER scope `[101, 1001, 51]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.
- BUSINESS_OWNER scope `[101, 1002, 51]`: token intent ∅; action intent `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `separate safe emergency contract PROPOSED; no GLOBAL bypass`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 51]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 51]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `business.emergency_recovery`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.network_manager.assign`, `staff.network_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`, `VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER`.
- Future policy probe finance `[101, 1002, 51]` / `finance.closed_totals.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## optional-network-manager-finite-subset

Synthetic user: `20`. **CURRENT:**

- Membership client 101: active=True, version=3.
- client/club `101/1001` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- client/club `101/1002` stored active: `client.club.read`; tombstones: ∅; current tokens with read prerequisite: `client.club.read`.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- NETWORK_MANAGER scope `[101, 1001, 20]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`, `verified business ownership/explicit finite assignments; no membership inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `staff.club_manager.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 20]`: +∅; −`client.club.read`; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `network.aggregates.compare`, `night.aggregates.read`, `preparation.overview.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.club_manager.assign`, `staff.club_manager.revoke`, `staff.roster.redacted.read`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe finance `[101, 1001, 20]` / `finance.closed_totals.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1002, 20]` / `finance.closed_totals.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## club-manager-finance-same-club-only

Synthetic user: `52`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 52]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 52]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `MEMBERSHIP_INACTIVE_OR_MISSING`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe finance `[101, 1001, 52]` / `finance.report.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1002, 52]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[102, 2001, 52]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `MEMBERSHIP_INACTIVE_OR_MISSING`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## club-assignment-without-finance-capability

Synthetic user: `53`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- CLUB_MANAGER scope `[101, 1001, 53]`: token intent `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; action intent `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted appointment hierarchy; proposed exact capability/admission/revoke contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `calendar.materialize`, `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`, `configuration.delegate`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 53]`: +`client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.finance.read`, `client.club.finance.write`, `client.club.layout.write`, `client.club.modules.read`, `client.club.modules.write`, `client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `assignment.revoke.one`, `attribution.correction.approve`, `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `calendar.materialize`, `configuration.delegate`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `night.aggregates.read`, `preparation.overview.read`, `preparation.self.start`, `preparation.submit`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `staff.disable.club`, `staff.entrance_manager.assign`, `staff.finance_manager.assign`, `staff.floor_manager.assign`, `staff.promoter.assign`, `staff.roster.redacted.read`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe finance `[101, 1001, 53]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## manager-granular-configuration-delegation

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent `client.club.profile.write`, `client.club.read`; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `client.club.profile.write`, `client.club.read`, `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +`client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe configuration `[101, 1001, 54]` / `client.club.profile.write`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe configuration `[101, 1002, 54]` / `client.club.profile.write`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 54]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## promoter-assigned-three-of-five

Synthetic user: `55`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- PROMOTER scope `[101, 1001, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1002, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1003, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1003, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `FOREIGN_BOOKING_NOT_OWNED`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe booking.create `[101, 1001, 55]` / `booking.promoter.create`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.create `[101, 1002, 55]` / `booking.promoter.create`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.create `[101, 1003, 55]` / `booking.promoter.create`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.own.read `[101, 1001, 55]` / `booking.promoter.own.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.own.read `[101, 1001, 55]` / `booking.promoter.own.read`: **DENY_IN_MODEL**; reasons `FOREIGN_BOOKING_NOT_OWNED`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 55]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## promoter-fourth-and-fifth-club-denied

Synthetic user: `55`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- PROMOTER scope `[101, 1001, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1002, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1003, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1003, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `MEMBERSHIP_INACTIVE_OR_MISSING`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe booking.create `[101, 1004, 55]` / `booking.promoter.create`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.create `[101, 1005, 55]` / `booking.promoter.create`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.create `[102, 2001, 55]` / `booking.promoter.create`: **DENY_IN_MODEL**; reasons `MEMBERSHIP_INACTIVE_OR_MISSING`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## promoter-assignment-revoked

Synthetic user: `55`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- PROMOTER scope `[101, 1001, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1002, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.
- PROMOTER scope `[101, 1003, 55]`: token intent ∅; action intent `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `booking.promoter.create`, `booking.promoter.own.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1002, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.
- Scope `[101, 1003, 55]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `audit.own_operations.read`, `booking.promoter.create`, `booking.promoter.own.read`, `promoter.own_invitation.issue`, `promoter.own_list.append`, `promoter.own_list.create`, `promoter.own_list.import`, `promoter.own_lists.read`, `promoter.own_outcomes.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Future policy probe booking.create `[101, 1001, 55]` / `booking.promoter.create`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe booking.own.read `[101, 1001, 55]` / `booking.promoter.own.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## attribution-club-source-is-not-free

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": null,
    "source_kind": "CLUB",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": null,
    "source_kind": "CLUB",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 1
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": null,
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-promoter-is-not-payer

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "DEPOSIT",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "DEPOSIT",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 1,
    "bookings_created": 1,
    "seated": 1
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## same-promoter-source-different-financial-mode

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "CLUB_FUNDED",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "CLUB_FUNDED",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-foreign-promoter-body-denied

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED`, `INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": null,
    "source_kind": "CLUB",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": true
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "foreign-promoter-57",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED",
    "EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED",
    "INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": null,
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-revoked-promoter-new-selection-denied

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED`, `INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": null,
    "source_kind": "CLUB",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": true
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 3,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED",
    "EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED",
    "INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-56"
  ],
  "historical_assignment_preserved": null,
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-cancelled-history-preserved

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 0,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-no_show-history-preserved

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 0,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-promoter-revoke-keeps-history

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 0,
    "bookings_created": 1,
    "seated": 0
  },
  "eligible_promoter_assignment_ids": [
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## attribution-table-move-and-command-retry-no-double-count

Synthetic user: `54`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 54]`: token intent ∅; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 54]`: +∅; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED`, `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.

Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):

```json
{
  "CURRENT": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "DIFFERENCE": {
    "applied_corrections": 0,
    "grant_effects": [],
    "money_effects": [],
    "source_changed": false
  },
  "PROPOSED": {
    "booking_id": "00000000-0000-4000-8000-000000000055",
    "client_id": 101,
    "club_id": 1001,
    "financial_mode": "BILL",
    "origin": {
      "actor_user_id": 55,
      "channel": "SYNTHETIC",
      "recorded_at": "2026-11-10T12:00:00Z"
    },
    "promoter_assignment_id": "promoter-55",
    "source_kind": "PROMOTER",
    "version": 1
  },
  "RISK": [
    "ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"
  ],
  "candidate_origin_unchanged": true,
  "counts_proposed": {
    "active_bookings": 1,
    "arrived": 1,
    "bookings_created": 1,
    "seated": 1
  },
  "eligible_promoter_assignment_ids": [
    "promoter-55",
    "promoter-56"
  ],
  "historical_assignment_preserved": "promoter-55",
  "origin_preserved": true,
  "status": "PROPOSED"
}
```

## combined-staff-assignments-before-revoke

Synthetic user: `58`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 58]`: token intent `client.club.profile.write`, `client.club.read`; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `client.club.profile.write`, `client.club.read`, `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- FINANCE_MANAGER scope `[101, 1001, 58]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 58]`: +`client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 58]` → MANAGER: would disable ∅; would create `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; would reactivate ∅; hypothetical future actions lost `finance.report.read`, `table.seat.booked`. Not executed; current schema does not admit those future action codes.
- Future policy probe configuration `[101, 1001, 58]` / `client.club.profile.write`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 58]` / `finance.report.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## revoke-one-role-preserves-independent-finance

Synthetic user: `58`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 58]`: token intent `client.club.profile.write`, `client.club.read`; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `client.club.profile.write`, `client.club.read`, `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- FINANCE_MANAGER scope `[101, 1001, 58]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 58]`: +`client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 58]` → MANAGER: would disable ∅; would create `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; would reactivate ∅; hypothetical future actions lost `finance.report.read`, `table.seat.booked`. Not executed; current schema does not admit those future action codes.
- Future policy probe configuration `[101, 1001, 58]` / `client.club.profile.write`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 58]` / `finance.report.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## revoke-one-delegation-preserves-other-role

Synthetic user: `58`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 58]`: token intent `client.club.profile.write`, `client.club.read`; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `client.club.profile.write`, `client.club.read`, `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- FINANCE_MANAGER scope `[101, 1001, 58]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 58]`: +`client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 58]` → MANAGER: would disable ∅; would create `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; would reactivate ∅; hypothetical future actions lost `finance.report.read`, `table.seat.booked`. Not executed; current schema does not admit those future action codes.
- Future policy probe configuration `[101, 1001, 58]` / `client.club.profile.write`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 58]` / `finance.report.read`: **ALLOW_IN_MODEL**; reasons ∅. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.

## full-club-disable-distinct-from-one-revoke

Synthetic user: `58`. **CURRENT:**

- Membership client 101: active=True, version=1.
- Commands/audit projections preserved: 0/0.

**PROPOSED technical bindings (not applied; product statuses separate):**

- FLOOR_MANAGER scope `[101, 1001, 58]`: token intent `client.club.profile.write`, `client.club.read`; action intent `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `accepted operation matrix; canonical night/booking/table; DEC-012/021/029`, `accepted source != financial terms; correction/rewards/UI contract PROPOSED`, `redacted roster/ledger/PII/history data contract`.
- ACCEPTED product actions (technical permission still PROPOSED): `client.club.profile.write`, `client.club.read`, `floor.tables.read`, `table.seat.booked`, `table.seat.walk_in`.
- FINANCE_MANAGER scope `[101, 1001, 58]`: token intent ∅; action intent `audit.own_operations.read`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.security_service.record`, `table.top_up.record`; blockers `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`; policy gates `DEC-012 + explicit monetary/ledger/allocations contract`, `redacted roster/ledger/PII/history data contract`, `server exact club/shift scope AND financial capability; no booking-to-finance inheritance`.
- ACCEPTED product actions (technical permission still PROPOSED): `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`.

**DIFFERENCE (hypothetical replacement only):**

- Scope `[101, 1001, 58]`: +`client.club.profile.write`, `client.club.read`; −∅; retained ∅; restoration attempts ∅.
- Proposed action intents: `attribution.correction.request`, `attribution.seating.read`, `audit.own_operations.read`, `booking.arrival_retention.extend`, `booking.no_show`, `finance.closed_totals.read`, `finance.deposits.read`, `finance.ledger.read`, `finance.operations.read`, `finance.report.read`, `floor.tables.read`, `self.booking.cancel`, `self.booking.confirm`, `self.booking.hold`, `self.records.read`, `self.subscription.write`, `shift.bracelets.write`, `shift.close`, `shift.people.write`, `shift.revenue.write`, `storefront.view`, `table.deposit.record`, `table.release`, `table.sales.stop`, `table.sales.undo`, `table.seat.booked`, `table.seat.walk_in`, `table.security_service.record`, `table.top_up.record`. Applied additions/removals: ∅/∅.

**RISK:** `EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE`, `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`, `OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES`, `TECHNICAL_PERMISSION_CONTRACT_PROPOSED`.
- Old writer preview `[101, 1001, 58]` → MANAGER: would disable ∅; would create `client.club.booking_rules.write`, `client.club.calendar.write`, `client.club.layout.write`, `client.club.profile.write`, `client.club.read`; would reactivate ∅; hypothetical future actions lost `finance.report.read`, `table.seat.booked`. Not executed; current schema does not admit those future action codes.
- Future policy probe configuration `[101, 1001, 58]` / `client.club.profile.write`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
- Future policy probe finance `[101, 1001, 58]` / `finance.report.read`: **DENY_IN_MODEL**; reasons `NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY`. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.
