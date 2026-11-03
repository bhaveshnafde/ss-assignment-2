-- PostgreSQL-only defence in depth. The Java code already enforces both rules; these triggers
-- make sure even a bug, a manual SQL session or a future change cannot break them.

-- 1. Append-only: refuse every UPDATE, DELETE and TRUNCATE on the ledger tables.
CREATE FUNCTION ledger_refuse_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed; post a reversal instead', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journals_append_only
    BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_change();

CREATE TRIGGER trg_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_change();

CREATE TRIGGER trg_journals_no_truncate
    BEFORE TRUNCATE ON journals
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_refuse_change();

CREATE TRIGGER trg_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_refuse_change();

-- 2. BR5 at commit time: every journal's debits must equal its credits. The check is DEFERRED
--    to COMMIT, because a journal's lines are inserted one by one and are only balanced once
--    all of them are in.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger AS $$
DECLARE
    debits  NUMERIC(19, 2);
    credits NUMERIC(19, 2);
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debits, credits
      FROM journal_entries
     WHERE journal_id = NEW.journal_id;
    IF debits <> credits OR debits = 0 THEN
        RAISE EXCEPTION 'journal % is unbalanced: debits % <> credits %', NEW.journal_id, debits, credits
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();
