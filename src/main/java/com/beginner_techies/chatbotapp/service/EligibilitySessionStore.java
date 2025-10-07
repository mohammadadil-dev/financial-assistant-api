package com.beginner_techies.chatbotapp.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.EligibilityState;
import com.beginner_techies.chatbotapp.dto.IntentResult;

@Service
public class EligibilitySessionStore {

    private final Map<String, EligibilityState> sessions = new ConcurrentHashMap<>();
    private final Set<String> langLocked = ConcurrentHashMap.newKeySet();

    public EligibilityState get(String userId) {
        return sessions.computeIfAbsent(userId, id -> new EligibilityState());
    }
    
    public void lockLang(String userId, boolean locked) {
        if (locked) langLocked.add(userId); else langLocked.remove(userId);
    }
    public boolean isLangLocked(String userId) {
        return langLocked.contains(userId);
    }

    public void reset(String userId) {
        sessions.remove(userId);
        langLocked.remove(userId);
    }

    public void setLangByMessage(String userId, String raw) {
        if (isLangLocked(userId)) return; // 👈 critical
        var st = get(userId);
        if (raw != null && raw.codePoints().anyMatch(cp ->
                (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F)
             || (cp >= 0x08A0 && cp <= 0x08FF) || (cp >= 0xFB50 && cp <= 0xFDFF)
             || (cp >= 0xFE70 && cp <= 0xFEFF))) {
            st.setLang("ar");
        } else if (st.lang() == null || st.lang().isBlank()) {
            st.setLang("en"); // default once
        }
    }

    /**
     * Merge detected intent slots into the session state.
     */
    public EligibilityState merge(String userId, IntentResult intent) {
        var state = get(userId);

        if (intent.amount != null) state.setAmount(intent.amount);
        if (intent.tenureMonths != null) state.setTenureMonths(intent.tenureMonths);
        if (intent.monthlyIncome != null) state.setMonthlyIncome(intent.monthlyIncome);
        if (intent.employerType != null) state.setEmployerType(intent.employerType);
        if (intent.serviceMonths != null) state.setServiceMonths(intent.serviceMonths);
        if (intent.hasExistingLoans != null) state.setHasExistingLoans(intent.hasExistingLoans);
        if (intent.nationality != null) state.setNationality(intent.nationality);

        // loanType detection could also be merged here if your IntentResult carries it
        // if (intent.loanType != null) state.setLoanType(intent.loanType);

        return state;
    }
}
