package com.moneytransfer.ledger.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BR5: a journal must balance.
 * <ul>
 *   <li>at least one DEBIT line and at least one CREDIT line</li>
 *   <li>total debits equal total credits</li>
 *   <li>every amount is greater than 0 with at most 2 decimal places</li>
 *   <li>no account is both debited and credited in the same journal</li>
 * </ul>
 */
public final class JournalRules {

    public static final int MAX_LINES = 50;

    private JournalRules() { }

    /** @throws RuleViolationException with the first rule the journal breaks */
    public static void check(List<EntryLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw unbalanced("a journal needs at least one debit and one credit line");
        }
        if (lines.size() > MAX_LINES) {
            throw new RuleViolationException(422, "INVALID_ENTRY", "a journal may have at most " + MAX_LINES + " lines");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        Set<String> debited = new HashSet<>();
        Set<String> credited = new HashSet<>();

        for (EntryLine line : lines) {
            if (line.amount().signum() <= 0) {
                throw new RuleViolationException(422, "INVALID_ENTRY",
                        "amount for " + line.accountId() + " must be greater than 0");
            }
            if (line.amount().stripTrailingZeros().scale() > 2) {
                throw new RuleViolationException(422, "INVALID_ENTRY",
                        "amount for " + line.accountId() + " may have at most 2 decimal places");
            }
            if (line.direction() == Direction.DEBIT) {
                debits = debits.add(line.amount());
                debited.add(line.accountId());
            } else {
                credits = credits.add(line.amount());
                credited.add(line.accountId());
            }
        }

        if (debited.isEmpty() || credited.isEmpty()) {
            throw unbalanced("a journal needs at least one debit and one credit line");
        }
        if (debits.compareTo(credits) != 0) {
            throw unbalanced("debits " + debits.toPlainString() + " do not equal credits " + credits.toPlainString());
        }
        debited.retainAll(credited);
        if (!debited.isEmpty()) {
            throw new RuleViolationException(422, "INVALID_ENTRY",
                    "account " + debited.iterator().next() + " is both debited and credited");
        }
    }

    /**
     * A canonical text form of a journal request, independent of line order and amount formatting
     * (100 = 100.00). Two requests describe the same journal exactly when their canonical forms match.
     */
    public static String canonical(String currency, List<EntryLine> lines) {
        return currency.toUpperCase(Locale.ROOT) + "|" + lines.stream()
                .map(l -> l.accountId() + ":" + l.direction() + ":" + l.amount().stripTrailingZeros().toPlainString())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    private static RuleViolationException unbalanced(String message) {
        return new RuleViolationException(422, "UNBALANCED_JOURNAL", message);
    }
}
