package com.beginner_techies.chatbotapp.util;

import org.springframework.stereotype.Service;

@Service
public class FinanceTools {
	public double emi(double principal, double annualRatePercent, int months) {
		if (months <= 0)
			return 0;
		double r = annualRatePercent / 12.0 / 100.0; // monthly nominal
		if (r <= 0)
			return Math.round((principal / months) * 100.0) / 100.0;
		double pow = Math.pow(1 + r, months);
		double emi = principal * r * pow / (pow - 1);
		return Math.round(emi * 100.0) / 100.0;
	}

	public boolean isAffordable(double income, double proposedEmi) {
		return proposedEmi <= income * 0.40; // 40% cap
	}
}