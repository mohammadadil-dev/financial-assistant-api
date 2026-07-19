package com.beginner_techies.chatbotapp.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FinanceToolsTest {

	private final FinanceTools tools = new FinanceTools();

	@Test
	void emiMatchesReducingBalanceFormula() {
		// 100,000 @ 7% over 48 months → 2,394.62 (standard annuity formula)
		assertThat(tools.emi(100_000, 7.0, 48)).isCloseTo(2394.62, within(0.5));
	}

	@Test
	void emiTimesMonthsAlwaysExceedsPrincipal() {
		double emi = tools.emi(200_000, 6.5, 60);
		assertThat(emi * 60).isGreaterThan(200_000);
	}

	@Test
	void zeroRateFallsBackToStraightLine() {
		assertThat(tools.emi(12_000, 0.0, 12)).isCloseTo(1000.0, within(0.01));
	}

	@Test
	void invalidMonthsReturnsZero() {
		assertThat(tools.emi(10_000, 7.0, 0)).isZero();
	}
}
