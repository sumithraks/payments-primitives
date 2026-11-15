package com.personal.coding.payments.models;

public record MatchedRecord(MatchType type, BankLine bank, LedgerEntry ledger) {}
