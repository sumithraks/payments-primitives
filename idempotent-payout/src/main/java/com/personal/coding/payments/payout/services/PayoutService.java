package com.personal.coding.payments.payout.services;

import com.personal.coding.payments.payout.models.*;
import com.personal.coding.payments.payout.clients.*;

import java.util.concurrent.ConcurrentHashMap;


public class PayoutService {

    public PayoutService(BankClient c) {
        client=c;
    }

    public static class Request {
        PayoutResult payoutResult;
        String idempotencyKey;
        String sellerId; 
        long amountCents;
    }

    private ConcurrentHashMap<String,Request> payouts = new ConcurrentHashMap<>();
    private BankClient client;

    private void validateRequest(String idempotencyKey, String sellerId, long amountCents) throws Exception {
        if (idempotencyKey==null || idempotencyKey.isBlank())
            throw new Exception("Idempotency key is required");
    
        if (amountCents<=0)
            throw new Exception("Amount must be positive");

        if (sellerId == null || sellerId.isBlank())
            throw new Exception("Seller is required");
    }
    public PayoutResult payout(String idempotencyKey, String sellerId, long amountCents) throws Exception {

        validateRequest(idempotencyKey,sellerId,amountCents);
    
        Request req = new Request();
        req.idempotencyKey=idempotencyKey;
        req.amountCents=amountCents;
        req.sellerId=sellerId;

        Request existing = payouts.putIfAbsent(idempotencyKey,req);
        
        Request lockableReq = (existing==null?req:existing);
        synchronized (lockableReq) {
            if (lockableReq.amountCents != amountCents || !lockableReq.sellerId.equals(sellerId)) {
                throw new Exception("Seller id or amount mismatches for same idempotency key");
            }
            if (lockableReq.payoutResult != null) {
                if (lockableReq.payoutResult.status()!=null && lockableReq.payoutResult.status()== PayoutStatus.SUCCESS)
                    return lockableReq.payoutResult;
                else if (lockableReq.payoutResult.status()!=null && lockableReq.payoutResult.status()==PayoutStatus.UNKNOWN) 
                    throw new Exception("Verify the bank statement for unknown result and update later");
            } else {
                String ref = client.send(sellerId, amountCents);
                if (ref == null || ref.equals("ERROR")) {
                    throw new Exception("Bank client return empty ref or error.");
                }
                if (ref.equals("TIMEOUT")) {
                    lockableReq.payoutResult=new PayoutResult(idempotencyKey, sellerId, amountCents,PayoutStatus.UNKNOWN, "UNKNOWN");
                } else {
                    lockableReq.payoutResult=new PayoutResult(idempotencyKey, sellerId, amountCents,PayoutStatus.SUCCESS, ref);
                }
                return lockableReq.payoutResult;
            }
            return lockableReq.payoutResult;
        }
    }

    public PayoutResult resolveUnknownPayouts(String idempotencyKey, String sellerId, long amountCents, PayoutStatus status, String ref) throws Exception{

        validateRequest(idempotencyKey,sellerId,amountCents);
    
        if (status != PayoutStatus.SUCCESS && status != PayoutStatus.ERROR)
            throw new Exception("Resolution must be SUCCESS or ERROR");

        if (status == PayoutStatus.SUCCESS && (ref == null || ref.isBlank()))
            throw new Exception("Bank reference is required for a successful payout");
        Request lockableReq = payouts.get(idempotencyKey);
        if (lockableReq == null) {
            throw new Exception("Transaction not found");
        }

        synchronized (lockableReq) {
            if (lockableReq.payoutResult != null ) {
                if (lockableReq.payoutResult.status()!=null  && lockableReq.payoutResult.status() != PayoutStatus.UNKNOWN) {
                    throw new Exception("Existing payout status is not unknown. It is "+lockableReq.payoutResult.status());
                } 
                else if (lockableReq.payoutResult.status()==null) {
                    throw new Exception("Existing payout status is null");
                }
                if (lockableReq.payoutResult.amountCents()!=amountCents ||
                    !lockableReq.payoutResult.sellerId().equals(sellerId)) {
                    throw new Exception("Existing payout seller or amount does not match");
                }
                
                if (status==PayoutStatus.ERROR)
                    lockableReq.payoutResult = null;
                else if (status==PayoutStatus.SUCCESS)
                    lockableReq.payoutResult = new PayoutResult(idempotencyKey, sellerId, amountCents,status, ref);
            } else {
                throw new Exception("No Payout result found");
            }
            return lockableReq.payoutResult;
        }
    }
}