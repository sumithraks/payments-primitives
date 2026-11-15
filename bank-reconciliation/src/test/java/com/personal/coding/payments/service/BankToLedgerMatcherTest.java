package com.personal.coding.payments.service;


import com.personal.coding.payments.models.*;
import com.personal.coding.payments.models.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BankToLedgerMatcherTest {

    private final BankToLedgerMatcher matcher = new BankToLedgerMatcher();

    // ---------- helpers ----------

    private static LocalDate nov(int day) { return LocalDate.of(2026, 11, day); }

    private static BankLine bank(String id, String ref, long cents, int day) {
        return new BankLine(id, ref, cents, nov(day));
    }

    private static LedgerEntry ledger(String id, String ref, long cents, int day) {
        return new LedgerEntry(id, ref, cents, nov(day));
    }

    private MatchResult run(List<BankLine> bank, List<LedgerEntry> ledger) {
        MatchResult r = matcher.match(new ArrayList<>(bank), new ArrayList<>(ledger));
        assertCountsBalance(r, bank.size(), ledger.size());
        return r;
    }

    /** Every input line must land in exactly one bucket: matched, unmatched or duplicate. */
    private static void assertCountsBalance(MatchResult r, int bankTotal, int ledgerTotal) {
        long bankDups = r.duplicates().stream().filter(o -> o instanceof BankLine).count();
        long ledgerDups = r.duplicates().stream().filter(o -> o instanceof LedgerEntry).count();
        assertEquals(bankTotal, r.matched().size() + r.unmatchedBank().size() + bankDups, "bank lines lost or double-counted");
        assertEquals(ledgerTotal, r.matched().size() + r.unmatchedLedger().size() + ledgerDups, "ledger entries lost or double-counted");
    }

    private static Set<String> matchedPairs(MatchResult r) {
        return r.matched().stream()
                .map(m -> m.type() + " " + m.bank().id() + "-" + m.ledger().id())
                .collect(Collectors.toSet());
    }

    private static Set<String> bankIds(List<BankLine> lines) {
        return lines.stream().map(BankLine::id).collect(Collectors.toSet());
    }

    private static Set<String> ledgerIds(List<LedgerEntry> entries) {
        return entries.stream().map(LedgerEntry::id).collect(Collectors.toSet());
    }

    private static Set<String> dupIds(MatchResult r) {
        return r.duplicates().stream()
                .map(o -> o instanceof BankLine b ? b.id() : ((LedgerEntry) o).id())
                .collect(Collectors.toSet());
    }

    // ---------- full dataset ----------

    @Test
    void fullDataset() {
        MatchResult r = run(
                List.of(bank("B1", "R1", 3_050_000, 10), bank("B2", null, 2_960_000, 10),
                        bank("B3", null, 200_000, 11),   bank("B4", "R9", 50_000, 11),
                        bank("B5", "R1", 3_050_000, 10)),
                List.of(ledger("L1", "R1", 3_050_000, 10), ledger("L2", null, 2_960_000, 11),
                        ledger("L3", null, 200_000, 11),   ledger("L4", null, 200_000, 12),
                        ledger("L5", "R7", 90_000, 10)));

        assertEquals(Set.of("EXACT B1-L1", "MATCHED_WITHOUT_REFERENCE B2-L2"), matchedPairs(r));
        assertEquals(Set.of("B3", "B4"), bankIds(r.unmatchedBank()));        // B3: two candidates; B4: ref not in ledger
        assertEquals(Set.of("L3", "L4", "L5"), ledgerIds(r.unmatchedLedger()));
        assertEquals(Set.of("B5"), dupIds(r));
    }

    // ---------- pass 1: by reference ----------

    @Test
    void exactReferenceAndAmountMatches() {
        MatchResult r = run(List.of(bank("B1", "R1", 100, 10)), List.of(ledger("L1", "R1", 100, 10)));

        assertEquals(Set.of("EXACT B1-L1"), matchedPairs(r));
    }

    @Test
    void sameReferenceDifferentAmountIsUnmatchedNotDuplicate() {
        MatchResult r = run(List.of(bank("B1", "R1", 100, 10)), List.of(ledger("L1", "R1", 999, 10)));

        assertTrue(r.matched().isEmpty());
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
        assertEquals(Set.of("L1"), ledgerIds(r.unmatchedLedger()));
        assertTrue(r.duplicates().isEmpty());
    }

    @Test
    void oneOfTwoReferencesMismatchedOnAmount() {
        MatchResult r = run(
                List.of(bank("B1", "R1", 100, 10), bank("B2", "R2", 200, 10)),
                List.of(ledger("L1", "R1", 999, 10), ledger("L2", "R2", 200, 10)));

        assertEquals(Set.of("EXACT B2-L2"), matchedPairs(r));
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
        assertEquals(Set.of("L1"), ledgerIds(r.unmatchedLedger()));
    }

    @Test
    void trueDuplicateSameReferenceAndAmount() {
        MatchResult r = run(
                List.of(bank("B1", "R1", 3_050_000, 10), bank("B2", "R1", 3_050_000, 10)),
                List.of(ledger("L1", "R1", 3_050_000, 10)));

        assertEquals(1, r.matched().size());
        assertEquals(1, r.duplicates().size());
        assertTrue(r.unmatchedBank().isEmpty());
    }

    @Test
    void sameReferenceSmallAmountThenRealAmount() {
        MatchResult r = run(
                List.of(bank("B1", "R1", 50_000, 10), bank("B2", "R1", 3_050_000, 10)),
                List.of(ledger("L1", "R1", 3_050_000, 10)));

        assertEquals(Set.of("EXACT B2-L1"), matchedPairs(r));
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
    }

    @Test
    void sameReferenceOrderDoesNotMatter() {
        MatchResult r = run(
                List.of(bank("B2", "R1", 3_050_000, 10), bank("B1", "R1", 50_000, 10)),
                List.of(ledger("L1", "R1", 3_050_000, 10)));

        assertEquals(Set.of("EXACT B2-L1"), matchedPairs(r));
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
    }

    // ---------- pass 2: no reference ----------

    @Test
    void referenceLineNeverFuzzyMatches() {
        // B1 has a reference that didn't match; it must not pair with L2 on amount alone
        MatchResult r = run(
                List.of(bank("B1", "R1", 100, 10)),
                List.of(ledger("L1", "R1", 999, 10), ledger("L2", null, 100, 10)));

        assertTrue(r.matched().isEmpty());
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
        assertEquals(Set.of("L1", "L2"), ledgerIds(r.unmatchedLedger()));
    }

    @Test
    void ambiguousTwoBankLinesOneLedgerEntryStaysUnmatched() {
        MatchResult r = run(
                List.of(bank("B1", "", 500, 10), bank("B2", "", 500, 10)),
                List.of(ledger("L1", "", 500, 10)));

        assertTrue(r.matched().isEmpty());
        assertEquals(Set.of("B1", "B2"), bankIds(r.unmatchedBank()));
        assertEquals(Set.of("L1"), ledgerIds(r.unmatchedLedger()));
    }

    @Test
    void oneDayApartMatchesWithoutReference() {
        MatchResult r = run(List.of(bank("B1", null, 100, 10)), List.of(ledger("L1", null, 100, 11)));

        assertEquals(Set.of("MATCHED_WITHOUT_REFERENCE B1-L1"), matchedPairs(r));
    }

    @Test
    void twoDaysApartDoesNotMatch() {
        MatchResult r = run(List.of(bank("B1", "", 100, 10)), List.of(ledger("L1", "", 100, 12)));

        assertTrue(r.matched().isEmpty());
        assertEquals(Set.of("B1"), bankIds(r.unmatchedBank()));
        assertEquals(Set.of("L1"), ledgerIds(r.unmatchedLedger()));
    }

    // ---------- edge cases ----------

    @Test
    void bothSidesEmpty() {
        MatchResult r = run(List.of(), List.of());

        assertTrue(r.matched().isEmpty());
        assertTrue(r.unmatchedBank().isEmpty());
        assertTrue(r.unmatchedLedger().isEmpty());
        assertTrue(r.duplicates().isEmpty());
    }

    @Test
    void bankOnlyEverythingUnmatched() {
        MatchResult r = run(List.of(bank("B1", "R1", 100, 10), bank("B2", "", 200, 10)), List.of());

        assertEquals(Set.of("B1", "B2"), bankIds(r.unmatchedBank()));
    }

    @Test
    void nullInputsTreatedAsEmpty() {
        MatchResult r = matcher.match(null, null);

        assertNotNull(r);
        assertTrue(r.matched().isEmpty());
    }

    @Test
    void doesNotModifyCallersLists() {
        List<BankLine> bank = new ArrayList<>(List.of(bank("B1", null, 100, 10)));
        List<LedgerEntry> ledger = new ArrayList<>(List.of(ledger("L1", null, 100, 10)));

        matcher.match(bank, ledger);

        assertEquals(1, bank.size());
        assertEquals(1, ledger.size());
    }
}
