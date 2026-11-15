package com.personal.coding.payments.models;

import java.util.List;
public record MatchResult(List<MatchedRecord> matched,
                     List<BankLine> unmatchedBank,
                     List<LedgerEntry> unmatchedLedger,
                     List<Object> duplicates) {}