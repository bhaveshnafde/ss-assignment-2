# A dependency that can be slow or fail (our own mock)

**Dependency:** the SMS provider, simulated inside the Notification service. It can be made slow
(`delayMs`), randomly failing (`failRate`) or failing for the next N sends (`failNext`), at runtime.
**Design:** Transfer sends the SMS in the background after the transfer is COMPLETED and retries on
failure; the notification is unique per transfer, so a retry never sends twice.

## Claims and the files that prove them

| # | Claim | Evidence | What to look for |
| --- | --- | --- | --- |
| D1 | The provider was set to fail the next 2 sends | [sms-fail-next-2/provider-settings.json](sms-fail-next-2/provider-settings.json) | `"failNext":2` |
| D2 | The transfer completed immediately anyway | [sms-fail-next-2/transfer.headers.txt](sms-fail-next-2/transfer.headers.txt), [sms-fail-next-2/transfer.json](sms-fail-next-2/transfer.json) | `201`, `"status":"COMPLETED"` |
| D3 | Every provider attempt is recorded and the retry succeeded | [sms-fail-next-2/notifications.json](sms-fail-next-2/notifications.json) | history `FAILED`, `FAILED`, `SENT`; one notification |
| D4 | The transfer records the final SMS outcome without changing its own status | [sms-fail-next-2/transfer-after.json](sms-fail-next-2/transfer-after.json) | `"status":"COMPLETED"`, `"notificationStatus":"SENT"` |

The run, in order: [sms-fail-next-2/summary.txt](sms-fail-next-2/summary.txt).

## Also proved by automated tests

- [NotificationApiIntegrationTest](../../notification-service/src/test/java/com/moneytransfer/notification/web/NotificationApiIntegrationTest.java) · `N3_providerFailsTwice_thenTheThirdRetrySucceeds_andEveryAttemptIsRecorded`
- [TransferSagaOrchestratorTest](../../transfer-service/src/test/java/com/moneytransfer/transfer/domain/saga/TransferSagaOrchestratorTest.java) · `notificationProblemNeverFailsACompletedTransfer`

## Screenshots to add

| File | What it shows |
| --- | --- |
| `screenshots/dashboard-sms-failure.png` | Dashboard "Slow / failing dependency" tab after "Next 2 sends fail": attempts FAILED, FAILED, SENT |

## Reproduce

`evidence/collect-all.sh` (it resets the provider to normal at the end).
