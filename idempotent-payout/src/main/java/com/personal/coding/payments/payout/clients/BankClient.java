package com.personal.coding.payments.payout.clients;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class BankClient { 

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean timeoutNext = new AtomicBoolean();
    private final AtomicBoolean errorNext = new AtomicBoolean();

    public void timeOutNextCall() { 
        timeoutNext.set(true); 
    }
    public void errorNextCall()   { 
        errorNext.set(true); 
    }
    public int  callCount()       { 
        return calls.get(); 
    }
    public void reset() { 
        calls.set(0); timeoutNext.set(false); errorNext.set(false); }


    public String send(String sellerId, long amountCents) {
        calls.incrementAndGet();
        try { 
            Thread.sleep(50); 
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (timeoutNext.getAndSet(false)) return "TIMEOUT";   // used once, then cleared
        if (errorNext.getAndSet(false))   return "ERROR";
        return "REF-" + UUID.randomUUID();                     // default: success, new ref each call
    }
}