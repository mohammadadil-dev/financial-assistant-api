package com.beginner_techies.chatbotapp.service;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.enums.LoanType;

@Service
public class DefaultRateService implements RateService {

    @Override
    public double getRate(LoanType loanType, double amount, int tenureMonths, String employerType) {
        // Simple policy; tweak as needed
        double base = switch (loanType) {
            case PERSONAL -> 7.0;
            case MORTGAGE, HOME -> 6.25;
            case AUTO -> 5.5;
        };
        if (employerType != null && employerType.equalsIgnoreCase("government")) base -= 0.25;
        if (tenureMonths > 60) base += 0.25;
        return base;
    }
}
