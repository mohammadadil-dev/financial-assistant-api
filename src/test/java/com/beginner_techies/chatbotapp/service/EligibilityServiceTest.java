package com.beginner_techies.chatbotapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.beginner_techies.chatbotapp.dto.EligibilityState;
import com.beginner_techies.chatbotapp.service.EligibilityService.Verdict;

class EligibilityServiceTest {

	private final EligibilityService service = new EligibilityService();

	private EligibilityState state(Double income, String employer, Integer serviceMonths) {
		var s = new EligibilityState();
		s.setMonthlyIncome(income);
		s.setEmployerType(employer);
		s.setServiceMonths(serviceMonths);
		return s;
	}

	@Test
	void missingSlotsMeansNeedInfo() {
		var v = service.evaluateDetailed(new EligibilityState());
		assertThat(v.status).isEqualTo(Verdict.Status.NEED_INFO);
	}

	@Test
	void solidGovernmentProfileIsPrequalified() {
		var v = service.evaluateDetailed(state(10_000.0, "government", 24));
		assertThat(v.status).isEqualTo(Verdict.Status.PREQUALIFIED);
	}

	@Test
	void dtiCapNeverExceedsSamaThird() {
		// SAMA Responsible Lending: debt burden ratio ≤ 33.33% for employees.
		for (String emp : new String[] { "government", "private", "contract", "self-employed" }) {
			var v = service.evaluateDetailed(state(20_000.0, emp, 60));
			assertThat(v.suggestedDtiCap).as("cap for %s", emp).isLessThanOrEqualTo(1.0 / 3.0 + 1e-9);
		}
	}

	@Test
	void nonSaudiCapIsTighter() {
		var saudi = state(10_000.0, "private", 24);
		saudi.setNationality("Saudi");
		var expat = state(10_000.0, "private", 24);
		expat.setNationality("Non-Saudi");
		assertThat(service.evaluateDetailed(expat).suggestedDtiCap)
				.isLessThan(service.evaluateDetailed(saudi).suggestedDtiCap);
	}

	@Test
	void heavyObligationsFlagDtiAboveCap() {
		var s = state(10_000.0, "private", 24);
		s.setOtherObligationsMonthly(5_000.0); // 50% DTI
		var v = service.evaluateDetailed(s);
		assertThat(v.reasons).contains("DTI_ABOVE_CAP");
	}

	@Test
	void clearlyLowIncomeIsIneligible() {
		var v = service.evaluateDetailed(state(3_000.0, "private", 24));
		assertThat(v.status).isEqualTo(Verdict.Status.PRELIM_INELIGIBLE);
		assertThat(v.reasons).contains("LOW_INCOME");
	}
}
