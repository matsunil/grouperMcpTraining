---
name: gte-ldap-provisioning-test
description: End-to-end smoke test that the groupOfNames LDAP provisioner on the local GTE still works, e.g. after a Grouper upgrade. Creates a throwaway group in test:, provisions it to the demo LDAP, checks members, deletes it, and reports pass or fail. Use when asked to test, verify or smoke-test LDAP / groupOfNames provisioning.
---

# GTE groupOfNames LDAP provisioning test

Use the `grouper-gte-local` MCP tools for every Grouper and LDAP step. Do not use GSH, the UI or WS.

## Before you start

- The user's global rules require approval before any Grouper write. Before step 1, tell the user exactly
  what you will change (group name, the 3 members, marking it for groupOfNames, deleting it at the end,
  and the daemon jobs you will run) and wait for one approval covering the whole test.
- Do not stop half way without cleaning up: if a check fails, still delete the group (step 5) unless the
  user asks to keep it for debugging.

## Facts about this provisioner

From `provisioner.groupOfNames.*` in grouper-loader.properties (check with `admin_config_search`,
regex `provisioner\.groupOfNames\..*`, if anything below looks wrong):

- LDAP external system: `demo` (`ldapExternalSystemConfigId`)
- Groups go to `ou=groups,dc=internet2,dc=edu`, flat DNs, RDN `cn` = the full Grouper group name,
  so the DN is `cn=test:ldapProvisionTestXYZ,ou=groups,dc=internet2,dc=edu`
- Membership attribute is `member`, with each value the member's DN, `uid=<subjectIdentifier0>,ou=people,dc=internet2,dc=edu`
- Only the `eduLDAP` source is provisioned (`subjectSourcesToProvision`)
- Jobs: full `OTHER_JOB_provisioner_full_groupOfNames`, incremental `CHANGE_LOG_consumer_provisioner_incremental_groupOfNames`

## How to "run the provisioner"

1. `admin_daemon_job_run` the full job `OTHER_JOB_provisioner_full_groupOfNames`
2. Wait 5 seconds (Bash `sleep 5` with `run_in_background`, then wait for the notification)
3. `admin_daemon_job_run` the incremental job `CHANGE_LOG_consumer_provisioner_incremental_groupOfNames`
4. Wait until both are done: poll `admin_daemon_logs` for each job name, with `startedAfter` set just
   before step 1 (loader log times are in the server's time zone, UTC on the GTE, so check a recent row
   first). Done means a row started after the trigger with status other than `STARTED`/`RUNNING`.
   Wait a few seconds between polls; give up after about 3 minutes and record a fail.
5. Record each job's status and insert/update/delete counts. If a status is not `SUCCESS`, read the
   message with `admin_daemon_job_message` and include the gist in the report.

## Steps

1. **Create the group.** Pick XYZ as 6 random lowercase letters/digits. `group_save` createGroup
   `test:ldapProvisionTestXYZ`. Pick 3 people from the `eduLDAP` source (e.g. `entity_get` searchString
   `smith`, sourceIds `eduLDAP`), look up their `uid` in the demo LDAP (`ldap` filter on
   `ou=people,dc=internet2,dc=edu`, e.g. `(employeeNumber=<subjectId>)`), and add them with
   `group_add_member` (sourceId `eduLDAP`). Confirm with `group_get_members` that there are 3.
2. **Not provisioned yet.** Run the provisioner. `ldap` filter `(cn=test:ldapProvisionTestXYZ)` with base
   `ou=groups,dc=internet2,dc=edu` must return 0 entries. Also check `group_find` with
   `includeProvisioning: true` shows no groupOfNames target.
3. **Mark for provisioning.** `group_save` addProvisioner, targetName `groupOfNames`. Check with
   `group_find` `includeProvisioning: true`. Wait 5 seconds, then run the provisioner.
4. **Check LDAP.** `ldap` filter `(cn=test:ldapProvisionTestXYZ)`, attributes `cn`, `member`,
   `objectClass`, `description`. Pass only if there is exactly 1 entry, its DN is the expected one, and
   its `member` values are exactly the 3 expected people DNs (no more, no fewer; compare
   case-insensitively).
5. **Delete and check removal.** `group_delete` the group, run the provisioner, and the same `ldap`
   filter must return 0 entries.
6. **Report.** Give PASS only if every check passed, else FAIL. Show a table: step, what was checked,
   expected, actual, result. Include the group name, the 3 members (subjectId, uid), and the job
   statuses/counts per provisioner run.
