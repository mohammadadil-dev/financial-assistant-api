package com.beginner_techies.chatbotapp.service.impl;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.LoanStatusResponse;
import com.beginner_techies.chatbotapp.dto.LoanStatusResponse.Status;
import com.beginner_techies.chatbotapp.service.LoanService;

@Service
public class LoanServiceMockImpl implements LoanService {

	@Override
	public LoanStatusResponse getStatusByNationalId(String nationalId) {
		// Mock logic—swap with your repository / REST call
		if (nationalId.endsWith("1"))
			return new LoanStatusResponse(Status.SUBMITTED, null);
		if (nationalId.endsWith("2"))
			return new LoanStatusResponse(Status.UNDER_REVIEW, null);
		if (nationalId.endsWith("3"))
			return new LoanStatusResponse(Status.APPROVED, null);
		if (nationalId.endsWith("4"))
			return new LoanStatusResponse(Status.FUNDED, null);
		if (nationalId.endsWith("5"))
			return new LoanStatusResponse(Status.REJECTED, "Debt-to-income above limit");
		return new LoanStatusResponse(Status.UNKNOWN, null);
	}
}