package com.personal.coding.payments.models;
import java.time.LocalDate;
public record LedgerEntry(String id, String reference, long amountCents, LocalDate date) {}
