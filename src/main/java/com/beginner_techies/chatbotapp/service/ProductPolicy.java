package com.beginner_techies.chatbotapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.beginner_techies.chatbotapp.enums.LoanType;

/**
 * Company credit-policy caps, per product and nationality — configurable via
 * application.properties (app.policy.cap.*) so the business can tune them
 * without a code change. Tenure limits are regulatory (SAMA), not policy:
 * consumer finance max 60 months; real-estate finance exempt.
 */
@Component
public class ProductPolicy {

	private final double personalSaudi;
	private final double personalNonSaudi;
	private final double autoSaudi;
	private final double autoNonSaudi;
	private final double homeSaudi;
	private final double homeNonSaudi;

	public ProductPolicy(@Value("${app.policy.cap.personal.saudi:50000}") double personalSaudi,
			@Value("${app.policy.cap.personal.nonsaudi:20000}") double personalNonSaudi,
			@Value("${app.policy.cap.auto.saudi:200000}") double autoSaudi,
			@Value("${app.policy.cap.auto.nonsaudi:100000}") double autoNonSaudi,
			@Value("${app.policy.cap.home.saudi:2000000}") double homeSaudi,
			@Value("${app.policy.cap.home.nonsaudi:1000000}") double homeNonSaudi) {
		this.personalSaudi = personalSaudi;
		this.personalNonSaudi = personalNonSaudi;
		this.autoSaudi = autoSaudi;
		this.autoNonSaudi = autoNonSaudi;
		this.homeSaudi = homeSaudi;
		this.homeNonSaudi = homeNonSaudi;
	}

	/** Nationality unknown defaults to the Saudi cap (consistent with EligibilityService). */
	public double maxAmount(LoanType type, String nationality) {
		boolean saudi = isSaudi(nationality);
		return switch (type) {
		case PERSONAL -> saudi ? personalSaudi : personalNonSaudi;
		case AUTO -> saudi ? autoSaudi : autoNonSaudi;
		case MORTGAGE, HOME -> saudi ? homeSaudi : homeNonSaudi;
		};
	}

	/** SAMA Responsible Lending: consumer finance ≤ 60 months; real estate exempt. */
	public int maxTenureMonths(LoanType type) {
		return switch (type) {
		case PERSONAL, AUTO -> 60;
		case MORTGAGE, HOME -> 360;
		};
	}

	public static boolean isSaudi(String nationality) {
		if (nationality == null || nationality.isBlank())
			return true;
		String n = nationality.trim().toLowerCase();
		return n.contains("saudi") && !n.contains("non") || n.contains("سعود") && !n.contains("غير");
	}
}
