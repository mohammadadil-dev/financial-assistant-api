package com.beginner_techies.chatbotapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.beginner_techies.chatbotapp.enums.LoanType;

class ProductPolicyTest {

	private final ProductPolicy policy = new ProductPolicy(50_000, 20_000, 200_000, 100_000, 2_000_000, 1_000_000);

	@Test
	void capsDifferByNationality() {
		assertThat(policy.maxAmount(LoanType.PERSONAL, "Saudi")).isEqualTo(50_000);
		assertThat(policy.maxAmount(LoanType.PERSONAL, "Non-Saudi")).isEqualTo(20_000);
		assertThat(policy.maxAmount(LoanType.AUTO, "Saudi")).isEqualTo(200_000);
		assertThat(policy.maxAmount(LoanType.MORTGAGE, "Non-Saudi")).isEqualTo(1_000_000);
		assertThat(policy.maxAmount(LoanType.HOME, "Saudi")).isEqualTo(2_000_000);
	}

	@Test
	void unknownNationalityDefaultsToSaudiCap() {
		assertThat(policy.maxAmount(LoanType.PERSONAL, null)).isEqualTo(50_000);
		assertThat(policy.maxAmount(LoanType.PERSONAL, "")).isEqualTo(50_000);
	}

	@Test
	void nationalityParsingHandlesNegativesAndArabic() {
		// "non-saudi" contains "saudi" — must NOT be treated as Saudi
		assertThat(ProductPolicy.isSaudi("Non-Saudi")).isFalse();
		assertThat(ProductPolicy.isSaudi("non saudi")).isFalse();
		assertThat(ProductPolicy.isSaudi("Saudi")).isTrue();
		assertThat(ProductPolicy.isSaudi("سعودي")).isTrue();
		assertThat(ProductPolicy.isSaudi("غير سعودي")).isFalse();
	}

	@Test
	void samaTenureLimits() {
		assertThat(policy.maxTenureMonths(LoanType.PERSONAL)).isEqualTo(60);
		assertThat(policy.maxTenureMonths(LoanType.AUTO)).isEqualTo(60);
		assertThat(policy.maxTenureMonths(LoanType.MORTGAGE)).isEqualTo(360);
	}
}
