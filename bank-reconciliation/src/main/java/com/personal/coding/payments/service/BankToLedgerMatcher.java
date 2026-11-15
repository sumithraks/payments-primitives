package com.personal.coding.payments.service;

import java.util.List;
import java.util.ArrayList;
import com.personal.coding.payments.models.*;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Iterator;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.HashSet;

public class BankToLedgerMatcher {

    public MatchResult match(List<BankLine> bankLines, List<LedgerEntry> ledgerEntries) { 
        
        if (bankLines == null) bankLines = List.of();
        if (ledgerEntries == null) ledgerEntries = List.of();

        List<BankLine> bankslinesForPhase2 = new ArrayList<>();
        List<LedgerEntry> ledgerEntriesForPhase2 = new ArrayList<>();
        List<Object> duplicates = new ArrayList();

        Map<String,BankLine> refToBankLine= new HashMap<>();
        Map<String,LedgerEntry> refToLedger = new HashMap<>();

        for (BankLine b:bankLines) {
            if (b.reference() == null || b.reference().isBlank()) {
                bankslinesForPhase2.add(b);
                continue;
            }
            if (!refToBankLine.containsKey(b.reference()+":"+b.amountCents()))
                refToBankLine.put(b.reference()+":"+b.amountCents(),b);
            else 
                duplicates.add(b);
        }

        for (LedgerEntry l:ledgerEntries) {
            if (l.reference() == null || l.reference().isBlank()) {
                ledgerEntriesForPhase2.add(l);
                continue;
            }
                
            if (!refToLedger.containsKey(l.reference()+":"+l.amountCents()))
                refToLedger.put(l.reference()+":"+l.amountCents(),l);
            else
                duplicates.add(l);
        }

        List<MatchedRecord> matches = doPhase1(refToBankLine,refToLedger);
        matches.addAll(doPhase2(bankslinesForPhase2, ledgerEntriesForPhase2));
        bankslinesForPhase2.addAll(refToBankLine.values());
        ledgerEntriesForPhase2.addAll(refToLedger.values());
    
        MatchResult result = new MatchResult(matches,
                     bankslinesForPhase2,
                     ledgerEntriesForPhase2,
                    duplicates);
        return result;
    }

    List<MatchedRecord> doPhase1(Map<String,BankLine> refToBankLine,
         Map<String,LedgerEntry> refToLedger) {
        
        List<MatchedRecord> result = new ArrayList<>();
        Iterator<Map.Entry<String,LedgerEntry>> it = refToLedger.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String,LedgerEntry> e = it.next();
            if (refToBankLine.containsKey(e.getKey())) {
                //&& Math.abs(ChronoUnit.DAYS.between(refToBankLine.get(e.getKey()).date(),e.getValue().date()))<=1) {
                MatchedRecord r = new MatchedRecord(MatchType.EXACT, 
                    refToBankLine.get(e.getKey()), e.getValue());
                result.add(r);
                it.remove();
                refToBankLine.remove(e.getKey());
            }
        }
        return result;
    }

    List<MatchedRecord> doPhase2(List<BankLine> bankLines,
        List<LedgerEntry> ledgerEntries) {
        if (bankLines==null || ledgerEntries==null)
            return null;
        List<MatchedRecord> result = new ArrayList<>();
        
        Map<Long,List<LedgerEntry>> amountsToLedger = 
            ledgerEntries.stream().collect(Collectors.groupingBy(LedgerEntry::amountCents));
        Map<BankLine,List<LedgerEntry>> matchedBankToLedger = new HashMap<>();
        Map<LedgerEntry,List<BankLine>> matchedLedgerToBank = new HashMap<>();

        for (BankLine b: bankLines) {
            if (amountsToLedger.containsKey(b.amountCents())) {
                for (LedgerEntry l: amountsToLedger.get(b.amountCents())) {
                    if (Math.abs(ChronoUnit.DAYS.between(l.date(),b.date()))<2) {
                        matchedBankToLedger.computeIfAbsent(b, k->new ArrayList<>()).add(l);
                        matchedLedgerToBank.computeIfAbsent(l,k->new ArrayList<>()).add(b);
                    }   
                }   
            }   
        }
       
        Set<BankLine> matchedBankLines=new HashSet<>();
        Set<LedgerEntry> maLedgerEntries=new HashSet<>();

        for (BankLine b:bankLines) {
             if (matchedBankToLedger.containsKey(b) && matchedBankToLedger.get(b).size()==1
                    && matchedLedgerToBank.get(matchedBankToLedger.get(b).get(0)).size()==1) {
    
                MatchedRecord m = new MatchedRecord(MatchType.MATCHED_WITHOUT_REFERENCE,
                    b,matchedBankToLedger.get(b).get(0));
                result.add(m);
                matchedBankLines.add(b);
                maLedgerEntries.add(matchedBankToLedger.get(b).get(0));
            }
        }

        bankLines.removeIf(k->matchedBankLines.contains(k));
        ledgerEntries.removeIf(k->maLedgerEntries.contains(k));
        return result;
    }
}