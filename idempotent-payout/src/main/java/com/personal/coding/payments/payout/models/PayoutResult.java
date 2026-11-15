package com.personal.coding.payments.payout.models;

public record PayoutResult(String payoutId, String sellerId, long amountCents, PayoutStatus status, String bankRef) {}