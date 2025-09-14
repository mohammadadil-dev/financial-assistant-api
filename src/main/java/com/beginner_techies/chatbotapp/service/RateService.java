package com.beginner_techies.chatbotapp.service;

import com.beginner_techies.chatbotapp.enums.LoanType;

public interface RateService {

	double getRate(LoanType loanType, double amount, int tenureMonths, String employerType);

}
