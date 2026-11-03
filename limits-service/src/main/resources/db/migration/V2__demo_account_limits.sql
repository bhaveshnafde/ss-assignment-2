-- Demo: ACC-2002 has a low personal limit, so BR3 (daily limit) can be shown end to end with the
-- seeded balances. ACC-2002 holds 5,000 INR in the Account service: one transfer of 1,500 succeeds,
-- a second one the same day takes it past 2,000 and is refused with LIMIT_EXCEEDED.
INSERT INTO account_limits (account_id, daily_limit, per_transfer_limit, updated_at, version)
VALUES ('ACC-2002', 2000.00, 1500.00, CURRENT_TIMESTAMP, 0);
