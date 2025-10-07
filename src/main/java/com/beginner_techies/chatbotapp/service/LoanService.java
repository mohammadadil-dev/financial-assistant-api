package com.beginner_techies.chatbotapp.service;

import com.beginner_techies.chatbotapp.dto.LoanStatusResponse;

public interface LoanService {

    LoanStatusResponse getStatusByNationalId(String nationalId);
}
