<!--
  Plantilla de PR (git-workflow.md §5, definition-of-done.md). El título y la descripción van en
  inglés (git-workflow.md §3). Marcar cada casilla o tacharla con una nota si no aplica.
-->

## What and why

<!-- What changes, and the reason (business rule BR-XXX, ADR, or bug). -->

## Definition of Done

- [ ] 1. All tests pass (unit, integration, E2E if a covered flow is touched)
- [ ] 2. New or changed business logic has >= 80% coverage
- [ ] 3. I reviewed my own diff end to end
- [ ] 4. Linter / formatter report no errors or manual exceptions
- [ ] 5. Architecture tests ARCH-01..14 pass
- [ ] 6. Schema changes are Flyway migrations: timestamp version, forward-only, expand/contract if incompatible (ADR-0027)
- [ ] 7. Comments explain why, not what, with the BR-XXX id when it applies (Spanish, coding-standards.md §5)
- [ ] 8. No commented-out code; no TODO without owner and reason
- [ ] 9. No hardcoded secrets or credentials; new variables are in `.env.example`
- [ ] 10. API docs updated if a contract changed
- [ ] 11. New business rules are in `business-rules.md` with their id
- [ ] 12. No new external dependency without a recorded decision (ADR if architecturally significant)
- [ ] 13. New or changed HTTP endpoints are annotated and the OpenAPI lint passes
- [ ] 14. New HTTP endpoints have an explicit rate-limit policy
- [ ] 15. New use cases expose a business metric and structured logs with `traceId`
- [ ] 16. New mutating `/app/**` routes require CSRF and are covered by the route-sweep test

## Notes for the reviewer

<!-- Risks, follow-ups, anything that is deliberately out of scope. -->
