package com.beginner_techies.chatbotapp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.EligibilityState;
import com.beginner_techies.chatbotapp.util.FinanceTools;

@Service
public class EligibilityService {

	public enum VerdictStatus {
		PREQUALIFIED, // meets rules comfortably
		BORDERLINE, // close to limits; suggest adjustments
		NEED_INFO, // missing inputs – ask before judging
		PRELIM_INELIGIBLE // likely not eligible under current inputs (not final)
	}

	public static final class EligibilityVerdict {
		public final VerdictStatus status;
		public final List<String> reasons; // human-readable, localized by caller
		public final Double appliedAnnualRate; // % APR used for any EMI math
		public final Double estimatedEmi; // if amount+tenure present
		public final Double dtiRatio; // 0..1 if computable (incl. other obligations)
		public final Double maxAllowedEmi; // income*DTI cap if computable

		public EligibilityVerdict(VerdictStatus status, List<String> reasons, Double appliedAnnualRate,
				Double estimatedEmi, Double dtiRatio, Double maxAllowedEmi) {
			this.status = status;
			this.reasons = reasons;
			this.appliedAnnualRate = appliedAnnualRate;
			this.estimatedEmi = estimatedEmi;
			this.dtiRatio = dtiRatio;
			this.maxAllowedEmi = maxAllowedEmi;
		}
	}

	// Policy knobs (tune per product / employer type)
	private static final Map<String, Integer> MIN_SERVICE_MONTHS = Map.of("government", 3, "private", 6, "contract", 12,
			"self-employed", 12);

	private static final Map<String, Double> MIN_INCOME = Map.of("government", 8000.0, "private", 10000.0, "contract",
			12000.0, "self-employed", 15000.0);

	private static final double MAX_DTI = 0.40; // 40% of income
	private static final double HARD_DTI = 0.50; // beyond this → prelim ineligible

	private final RateService rateService;
	private final FinanceTools tools; // for EMI

	public EligibilityService(RateService rateService, FinanceTools tools) {
		this.rateService = rateService;
		this.tools = tools;
	}

	/**
	 * Deterministic evaluation. No LLM here. - Doesn’t “decline” when information
	 * is missing → returns NEED_INFO + what to ask. - Uses policy thresholds by
	 * employer type. - If amount & tenure present, computes EMI and DTI.
	 */
	public EligibilityVerdict evaluateDetailed(EligibilityState s) {
		List<String> reasons = new ArrayList<>();

		// 0) Require the basic triad for any meaningful decision
		if (s.getMonthlyIncome() == null || s.getEmployerType() == null || s.getServiceMonths() == null) {
			if (s.getMonthlyIncome() == null)
				reasons.add("NEED_INCOME");
			if (s.getEmployerType() == null)
				reasons.add("NEED_EMPLOYER_TYPE");
			if (s.getServiceMonths() == null)
				reasons.add("NEED_SERVICE_MONTHS");
			return new EligibilityVerdict(VerdictStatus.NEED_INFO, reasons, null, null, null, null);
		}

		String emp = normalizeEmployer(s.getEmployerType());
		int minSvc = MIN_SERVICE_MONTHS.getOrDefault(emp, 6);
		double minIncome = MIN_INCOME.getOrDefault(emp, 10000.0);

		// 1) Base policy checks
		if (s.getServiceMonths() < minSvc) {
			reasons.add("LOW_SERVICE_MONTHS");
		}
		if (s.getMonthlyIncome() < minIncome) {
			reasons.add("LOW_INCOME");
		}

		// If any base policy fails -> prelim ineligible, but keep friendly tone in UI
		if (!reasons.isEmpty() && (s.getAmount() == null || s.getTenureMonths() == null)) {
			return new EligibilityVerdict(VerdictStatus.PRELIM_INELIGIBLE, reasons, null, null, null, null);
		}

		// 2) If amount & tenure provided, compute EMI and DTI
		Double rate = null, emi = null, dti = null, maxEmi = null;
		if (s.getAmount() != null && s.getTenureMonths() != null && s.getLoanType() != null) {
			rate = rateService.getRate(s.getLoanType(), s.getAmount(), s.getTenureMonths(), emp);
			if (rate == null || rate <= 0) {
				// fallback: still compute zero-rate EMI to give a sense of affordability band
				rate = 0.0;
			}
			emi = tools.emi(s.getAmount(), rate, s.getTenureMonths()); // handles r=0 internally
			double other = safeDouble(s.getOtherObligationsMonthly()); // optional field; 0 if null
			maxEmi = s.getMonthlyIncome() * MAX_DTI;
			dti = (emi + other) / s.getMonthlyIncome();

			if (dti > HARD_DTI)
				reasons.add("DTI_ABOVE_50");
			else if (dti > MAX_DTI)
				reasons.add("DTI_ABOVE_40");
		}

		// 3) Decide status
		VerdictStatus status;
		if (reasons.contains("LOW_SERVICE_MONTHS") || reasons.contains("LOW_INCOME")
				|| reasons.contains("DTI_ABOVE_50")) {
			status = VerdictStatus.PRELIM_INELIGIBLE;
		} else if (reasons.contains("DTI_ABOVE_40")) {
			status = VerdictStatus.BORDERLINE;
		} else if (s.getAmount() != null && s.getTenureMonths() != null) {
			status = VerdictStatus.PREQUALIFIED; // meets base and affordability rule
		} else {
			status = VerdictStatus.PREQUALIFIED; // base checks pass; offer next steps (EMI, docs)
		}

		return new EligibilityVerdict(status, reasons, rate, emi, dti, maxEmi);
	}

	private static String normalizeEmployer(String e) {
		if (e == null)
			return "private";
		String x = e.toLowerCase();
		if (x.contains("gov"))
			return "government";
		if (x.contains("contract"))
			return "contract";
		if (x.contains("self"))
			return "self-employed";
		if (x.contains("خاص"))
			return "private";
		if (x.contains("حك"))
			return "government";
		if (x.contains("متعاقد"))
			return "contract";
		if (x.contains("عمل حر"))
			return "self-employed";
		return "private";
	}

	private static double safeDouble(Double v) {
		return v == null ? 0.0 : v;
	}
}
