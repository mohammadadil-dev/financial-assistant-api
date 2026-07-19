package com.beginner_techies.chatbotapp.dto;

import com.beginner_techies.chatbotapp.enums.LoanType;

public class EligibilityState {
	// --- Conversation / locale ---
	private String lang = "en"; // "en" or "ar"

	// --- Product selection ---
	private LoanType loanType; // PERSONAL, MORTGAGE/HOME, AUTO

	// --- Eligibility slots (triad + optional) ---
	private Double monthlyIncome; // SAR
	private String employerType; // government/private/contract/self-employed (raw)
	private Integer serviceMonths; // months at current employer
	private Boolean hasExistingLoans; // optional yes/no
	private Double otherObligationsMonthly; // optional SAR (credit cards, other EMIs)
	private String nationality; // optional

	// --- EMI inputs ---
	private String currency; // "SAR" / "ريال" (display only)
	private Double amount; // principal
	private Integer tenureMonths; // months
	private Double annualRate; // not used; rate decided internally (kept for compatibility)

	// ---------------- Getters / Setters ----------------

	public String lang() {
		return lang;
	}

	public void setLang(String lang) {
		this.lang = (lang == null || lang.isBlank()) ? "en" : lang;
	}

	public boolean isArabic() {
		return "ar".equalsIgnoreCase(lang);
	}

	public LoanType getLoanType() {
		return loanType;
	}

	public void setLoanType(LoanType loanType) {
		this.loanType = loanType;
	}

	public Double getMonthlyIncome() {
		return monthlyIncome;
	}

	public void setMonthlyIncome(Double monthlyIncome) {
		this.monthlyIncome = monthlyIncome;
	}

	public String getEmployerType() {
		return employerType;
	}

	public void setEmployerType(String employerType) {
		this.employerType = employerType;
	}

	/** Normalized employer type used by policy/rate logic. */
	public String getEmployerTypeNormalized() {
		if (employerType == null)
			return null;
		String x = employerType.toLowerCase();
		if (x.contains("gov") || x.contains("حك"))
			return "government";
		if (x.contains("contract") || x.contains("متعاقد"))
			return "contract";
		if (x.contains("self") || x.contains("عمل حر"))
			return "self-employed";
		if (x.contains("private") || x.contains("خاص"))
			return "private";
		return employerType.toLowerCase().trim();
	}

	public Integer getServiceMonths() {
		return serviceMonths;
	}

	public void setServiceMonths(Integer serviceMonths) {
		this.serviceMonths = serviceMonths;
	}

	public Boolean getHasExistingLoans() {
		return hasExistingLoans;
	}

	public void setHasExistingLoans(Boolean hasExistingLoans) {
		this.hasExistingLoans = hasExistingLoans;
	}

	public Double getOtherObligationsMonthly() {
		return otherObligationsMonthly;
	}

	public void setOtherObligationsMonthly(Double otherObligationsMonthly) {
		this.otherObligationsMonthly = otherObligationsMonthly;
	}

	public String getNationality() {
		return nationality;
	}

	public void setNationality(String nationality) {
		this.nationality = nationality;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public Double getAmount() {
		return amount;
	}

	public void setAmount(Double amount) {
		this.amount = amount;
	}

	public Integer getTenureMonths() {
		return tenureMonths;
	}

	public void setTenureMonths(Integer tenureMonths) {
		this.tenureMonths = tenureMonths;
	}

	public Double getAnnualRate() {
		return annualRate;
	}

	public void setAnnualRate(Double annualRate) {
		this.annualRate = annualRate;
	}

	// ---------------- Multi-step flow state ----------------
	// Non-null while a guided flow is collecting input, e.g. CB_NAME/CB_MOBILE/
	// CB_TIME (agent callback), AFFORD_INCOME/AFFORD_OBLIG (affordability),
	// SETTLE_MONTHS (early settlement).
	private String pendingFlow;
	private String callbackName;
	private String callbackMobile;

	public String getPendingFlow() {
		return pendingFlow;
	}

	public void setPendingFlow(String pendingFlow) {
		this.pendingFlow = pendingFlow;
	}

	public String getCallbackName() {
		return callbackName;
	}

	public void setCallbackName(String callbackName) {
		this.callbackName = callbackName;
	}

	public String getCallbackMobile() {
		return callbackMobile;
	}

	public void setCallbackMobile(String callbackMobile) {
		this.callbackMobile = callbackMobile;
	}

	// ---------------- Convenience / Flow helpers ----------------

	/** The minimum we need before we can run an eligibility evaluation. */
	public boolean hasMinimumEligibility() {
		return monthlyIncome != null && employerType != null && !employerType.isBlank() && serviceMonths != null;
		// NOTE: hasExistingLoans is OPTIONAL and no longer blocks progress
	}

	public boolean needsIncome() {
		return monthlyIncome == null;
	}

	public boolean needsEmployerType() {
		return employerType == null || employerType.isBlank();
	}

	public boolean needsServiceMonths() {
		return serviceMonths == null;
	}

	public boolean needsExistingLoans() {
		return hasExistingLoans == null;
	}

	public boolean needsNationality() {
		return nationality == null || nationality.isBlank();
	}

	/** Reset EMI-only fields (e.g., when changing product). */
	public void resetEmi() {
		this.amount = null;
		this.tenureMonths = null;
		this.annualRate = null;
	}

	/** Optional: Clear triad if you want to restart eligibility flow. */
	public void resetEligibility() {
		this.monthlyIncome = null;
		this.employerType = null;
		this.serviceMonths = null;
		this.hasExistingLoans = null;
		this.otherObligationsMonthly = null;
		this.nationality = null;
	}

	/** Merge router output safely (nulls don’t overwrite existing values). */
	public void copyFrom(IntentResult r) {
		if (r == null)
			return;
		if (r.currency != null)
			this.currency = r.currency;
		if (r.amount != null)
			this.amount = r.amount;
		if (r.annualRate != null)
			this.annualRate = r.annualRate; // usually null by design
		if (r.tenureMonths != null)
			this.tenureMonths = r.tenureMonths;
		if (r.monthlyIncome != null)
			this.monthlyIncome = r.monthlyIncome;
		if (r.employerType != null)
			this.employerType = r.employerType;
		if (r.serviceMonths != null)
			this.serviceMonths = r.serviceMonths;
		if (r.hasExistingLoans != null)
			this.hasExistingLoans = r.hasExistingLoans;
		if (r.nationality != null)
			this.nationality = r.nationality;
		// If your router emits a loanType, merge it here too (extend IntentResult
		// accordingly)
		// if (r.loanType != null) this.loanType = r.loanType;
	}
}
