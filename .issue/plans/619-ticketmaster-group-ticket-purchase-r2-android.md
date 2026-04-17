# Issue #619 / #640 Android companion

## Goal

Ship an Android companion release for the Ticketmaster group-purchase cost-sharing work so app users pick up the new core-served event-modal behavior.

## Scope

1. Bump the Android app version in the first companion commit, as required by the Groovitation Android workflow.
2. Push the companion branch while the core `issue/619-ticketmaster-group-ticket-purchase-r2` pipeline is running.
3. Wait for branch CI, then merge the companion branch to `android/main` immediately if green.
