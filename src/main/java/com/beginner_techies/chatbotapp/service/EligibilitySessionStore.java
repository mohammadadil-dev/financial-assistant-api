package com.beginner_techies.chatbotapp.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.EligibilityState;
import com.beginner_techies.chatbotapp.dto.IntentResult;

@Service
public class EligibilitySessionStore {

    private final Map<String, EligibilityState> sessions = new ConcurrentHashMap<>();

    public EligibilityState get(String userId) {
        return sessions.computeIfAbsent(userId, id -> new EligibilityState());
    }

    public void reset(String userId) {
        sessions.remove(userId);
    }

    public void setLangByMessage(String userId, String message) {
        var state = get(userId);
        if (message != null && message.chars().anyMatch(c -> Character.UnicodeBlock.of(c).toString().startsWith("ARABIC"))) {
            state.setLang("ar");
        } else {
            state.setLang("en");
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
