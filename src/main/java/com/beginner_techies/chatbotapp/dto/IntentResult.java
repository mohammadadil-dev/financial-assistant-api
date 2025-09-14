package com.beginner_techies.chatbotapp.dto;

import com.beginner_techies.chatbotapp.enums.Intent;
import com.beginner_techies.chatbotapp.enums.LoanType;

public class IntentResult {
    public Intent intent = Intent.UNKNOWN;

    public String currency;       // e.g., "SAR"
    public Double amount;
    public Double annualRate;     // always null (rate internal)
    public Integer tenureMonths;

    public Double monthlyIncome;
    public String employerType;
    public Integer serviceMonths;
    public Boolean hasExistingLoans;
    public String nationality;

    public boolean needsAuth = false;

    // Optional: for loan-type signaling
    public LoanType loanType;
}
