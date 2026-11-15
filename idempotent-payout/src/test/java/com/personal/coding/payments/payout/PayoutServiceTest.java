package com.personal.coding.payments.payout;


import static org.junit.jupiter.api.Assertions.*;
import com.personal.coding.payments.payout.clients.*;
import com.personal.coding.payments.payout.models.*;
import com.personal.coding.payments.payout.services.*;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PayoutServiceTest {

    private BankClient bank;
    private PayoutService service;

    @BeforeEach
    void setUp() {
        bank = new BankClient();
        service = new PayoutService(bank);
    }

    // ---------- Idempotency ----------

    @Test
    void repeatedKeyReturnsSameResultAndCallsBankOnce() throws Exception {
        PayoutResult first = service.payout("k1", "seller-1", 700_000);
        PayoutResult second = service.payout("k1", "seller-1", 700_000);

        assertEquals(first, second);
        assertEquals(PayoutStatus.SUCCESS, first.status());
        assertEquals(1, bank.callCount());
    }

    @Test
    void payoutIdIsSet() throws Exception {
        PayoutResult result = service.payout("k1b", "seller-1", 100);

        assertNotNull(result.payoutId());
        assertFalse(result.payoutId().isBlank());
    }

    @Test
    void retryWithEqualButDifferentSellerStringIsAccepted() throws Exception {
        service.payout("k2", "seller-1", 100);
        String sameSellerNewObject = new String("seller-1");   // as if parsed from a new request

        assertDoesNotThrow(() -> service.payout("k2", sameSellerNewObject, 100));
        assertEquals(1, bank.callCount());
    }

    @Test
    void sameKeyDifferentAmountIsRejectedWithoutCallingBank() throws Exception {
        service.payout("k3", "seller-1", 100);

        assertThrows(Exception.class, () -> service.payout("k3", "seller-1", 999));
        assertEquals(1, bank.callCount());
    }

    @Test
    void sameKeyDifferentSellerIsRejected() throws Exception {
        service.payout("k4", "seller-1", 100);

        assertThrows(Exception.class, () -> service.payout("k4", "seller-2", 100));
        assertEquals(1, bank.callCount());
    }

    @Test
    void differentKeysEachCallTheBank() throws Exception {
        service.payout("a", "seller-1", 100);
        service.payout("b", "seller-1", 100);

        assertEquals(2, bank.callCount());
    }

    // ---------- Concurrency ----------

    @Test
    void tenThreadsSameKeyCallBankExactlyOnce() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PayoutResult>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();                                     // all threads go together
                return service.payout(new String("same-key"), "seller-1", 500);   // separate String objects
            }));
        }
        start.countDown();

        Set<PayoutResult> distinct = new HashSet<>();
        for (Future<PayoutResult> f : results) distinct.add(f.get());
        pool.shutdown();

        assertEquals(1, bank.callCount(), "bank must be called exactly once");
        assertEquals(1, distinct.size(), "every thread must get the same result");
    }

    @Test
    void tenThreadsSameLiteralKeyCallBankExactlyOnce() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PayoutResult>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.payout("literal-key", "seller-1", 500);   // one shared (interned) String
            }));
        }
        start.countDown();

        Set<PayoutResult> distinct = new HashSet<>();
        for (Future<PayoutResult> f : results) distinct.add(f.get());
        pool.shutdown();

        assertEquals(1, bank.callCount());
        assertEquals(1, distinct.size());
    }

    // ---------- Validation ----------

    @Test
    void nullOrBlankKeyIsRejected() {
        assertThrows(Exception.class, () -> service.payout(null, "seller-1", 100));
        assertThrows(Exception.class, () -> service.payout("  ", "seller-1", 100));
        assertEquals(0, bank.callCount());
    }

    @Test
    void nullOrBlankSellerIsRejected() {
        assertThrows(Exception.class, () -> service.payout("k", null, 100));
        assertThrows(Exception.class, () -> service.payout("k", " ", 100));
        assertEquals(0, bank.callCount());
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        assertThrows(Exception.class, () -> service.payout("k", "seller-1", 0));
        assertThrows(Exception.class, () -> service.payout("k", "seller-1", -5));
        assertEquals(0, bank.callCount());
    }

    // ---------- Bank error: no money moved, retry allowed ----------

    @Test
    void bankErrorThrowsAndRetrySendsOnce() throws Exception {
        bank.errorNextCall();
        assertThrows(Exception.class, () -> service.payout("k7", "seller-1", 100));

        PayoutResult retry = service.payout("k7", "seller-1", 100);
        service.payout("k7", "seller-1", 100);
        service.payout("k7", "seller-1", 100);

        assertEquals(PayoutStatus.SUCCESS, retry.status());
        assertEquals(2, bank.callCount(), "one failed call + one successful retry, then no more");
    }

    @Test
    void afterBankErrorRetryWithDifferentAmountIsRejected() {
        bank.errorNextCall();
        assertThrows(Exception.class, () -> service.payout("k8", "seller-1", 500));

        assertThrows(Exception.class, () -> service.payout("k8", "seller-1", 99_999));
        assertEquals(1, bank.callCount());
    }

    // ---------- Timeout: outcome unknown, never resend ----------

    @Test
    void timeoutIsRecordedAsUnknownAndNotResent() throws Exception {
        bank.timeOutNextCall();
        PayoutResult result = service.payout("k9", "seller-1", 100);

        assertEquals(PayoutStatus.UNKNOWN, result.status());
        assertThrows(Exception.class, () -> service.payout("k9", "seller-1", 100));
        assertEquals(1, bank.callCount());
    }

    @Test
    void resolveUnknownToSuccessStoresBankReference() throws Exception {
        bank.timeOutNextCall();
        service.payout("k10", "seller-1", 100);

        PayoutResult resolved = service.resolveUnknownPayouts("k10", "seller-1", 100, PayoutStatus.SUCCESS, "BANK-REF-10");

        assertEquals(PayoutStatus.SUCCESS, resolved.status());
        assertEquals("BANK-REF-10", resolved.bankRef());
        assertEquals(resolved, service.payout("k10", "seller-1", 100));   // later retries see SUCCESS
        assertEquals(1, bank.callCount());
    }

    @Test
    void resolveAcceptsEqualButDifferentSellerString() throws Exception {
        bank.timeOutNextCall();
        service.payout("k10b", "seller-1", 100);

        String sameSellerNewObject = new String("seller-1");
        PayoutResult resolved = service.resolveUnknownPayouts("k10b", sameSellerNewObject, 100, PayoutStatus.SUCCESS, "R");

        assertEquals(PayoutStatus.SUCCESS, resolved.status());
    }

    @Test
    void resolveUnknownToErrorAllowsOneRetry() throws Exception {
        bank.timeOutNextCall();
        service.payout("k11", "seller-1", 100);

        service.resolveUnknownPayouts("k11", "seller-1", 100, PayoutStatus.ERROR, null);
        PayoutResult retry = service.payout("k11", "seller-1", 100);
        service.payout("k11", "seller-1", 100);

        assertEquals(PayoutStatus.SUCCESS, retry.status());
        assertEquals(2, bank.callCount());
    }

    @Test
    void resolveRejectsBadInput() throws Exception {
        bank.timeOutNextCall();
        service.payout("k12", "seller-1", 100);

        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("missing", "seller-1", 100, PayoutStatus.SUCCESS, "R"));
        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("k12", "seller-2", 100, PayoutStatus.SUCCESS, "R"));
        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("k12", "seller-1", 999, PayoutStatus.SUCCESS, "R"));
        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("k12", "seller-1", 100, PayoutStatus.SUCCESS, null));
        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("k12", "seller-1", 100, PayoutStatus.UNKNOWN, "R"));
    }

    @Test
    void resolveRejectsPayoutThatIsNotUnknown() throws Exception {
        service.payout("k13", "seller-1", 100);   // SUCCESS

        assertThrows(Exception.class, () -> service.resolveUnknownPayouts("k13", "seller-1", 100, PayoutStatus.SUCCESS, "R"));
    }
}