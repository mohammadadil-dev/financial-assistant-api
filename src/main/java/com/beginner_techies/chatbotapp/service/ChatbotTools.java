package com.beginner_techies.chatbotapp.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.beginner_techies.chatbotapp.dto.LoanStatusResponse;
import com.beginner_techies.chatbotapp.enums.LoanType;
import com.beginner_techies.chatbotapp.util.FinanceTools;

/**
 * Function-calling tools the LLM can invoke on the free-text (RAG) path, so
 * questions like "what's the EMI for 300k over 5 years?" are answered with
 * REAL math from the same services the rule engine uses — never guessed.
 *
 * Every result string reminds the model the figures are indicative. Tool
 * invocations are audit-logged. Requires a tool-capable model
 * (Groq llama-3.3 ✓, Ollama needs llama3.1+); gated by app.ai.tools.enabled.
 */
@Component
public class ChatbotTools {

	private static final double DBR_CAP = 1.0 / 3.0; // SAMA salaried debt-burden cap

	private final FinanceTools finance;
	private final RateService rateService;
	private final LoanService loanService;
	private final LeadStore leadStore;
	private final AuditService audit;
	private final ProductPolicy policy;

	public ChatbotTools(FinanceTools finance, RateService rateService, LoanService loanService, LeadStore leadStore,
			AuditService audit, ProductPolicy policy) {
		this.finance = finance;
		this.rateService = rateService;
		this.loanService = loanService;
		this.leadStore = leadStore;
		this.audit = audit;
		this.policy = policy;
	}

	@Tool(description = "Calculate the monthly installment (EMI) for a loan. Use for any 'what would I pay per month' question.")
	public String calculateEmi(@ToolParam(description = "Loan amount in SAR") double amount,
			@ToolParam(description = "Tenure in months") int tenureMonths,
			@ToolParam(description = "Loan type: PERSONAL, HOME or AUTO") String loanType) {
		audit.toolCall("tool-ctx", "calculateEmi", amount + "/" + tenureMonths + "/" + loanType);
		if (amount < 1000 || amount > 10_000_000 || tenureMonths < 6 || tenureMonths > 360) {
			return "Invalid input: amount must be 1,000–10,000,000 SAR and tenure 6–360 months.";
		}
		LoanType lt = parseType(loanType);
		// SAMA: consumer finance tenure max 60 months (real estate exempt)
		int maxTen = (lt == LoanType.MORTGAGE || lt == LoanType.HOME) ? 360 : 60;
		if (tenureMonths > maxTen)
			tenureMonths = maxTen;
		double rate = safeRate(lt, amount, tenureMonths);
		double emi = finance.emi(amount, rate, tenureMonths);
		double total = emi * tenureMonths;
		return ("EMI ≈ %,.2f SAR/month for %,.0f SAR over %d months at %.2f%% annual rate. "
				+ "Total payable ≈ %,.2f SAR (profit ≈ %,.2f SAR). Indicative, subject to eligibility.")
				.formatted(emi, amount, tenureMonths, rate, total, total - amount);
	}

	@Tool(description = "Maximum loan amount a customer can afford from their salary, under SAMA's 33% debt-burden cap and the company's product caps. Use for 'how much can I borrow' questions.")
	public String maxAffordableLoan(@ToolParam(description = "Monthly income in SAR") double monthlyIncome,
			@ToolParam(description = "Existing monthly obligations in SAR (0 if none)") double monthlyObligations,
			@ToolParam(description = "Loan type: PERSONAL, HOME or AUTO") String loanType,
			@ToolParam(description = "Customer nationality: 'Saudi' or 'Non-Saudi'; use 'Saudi' if unknown") String nationality) {
		audit.toolCall("tool-ctx", "maxAffordableLoan",
				monthlyIncome + "/" + monthlyObligations + "/" + loanType + "/" + nationality);
		if (monthlyIncome < 1000)
			return "Invalid input: monthly income must be at least 1,000 SAR.";
		LoanType lt = parseType(loanType);
		int tenure = (lt == LoanType.MORTGAGE || lt == LoanType.HOME) ? 300 : 60;
		double maxEmi = monthlyIncome * DBR_CAP - Math.max(0, monthlyObligations);
		if (maxEmi < 100)
			return "With these obligations there is almost no room for a new installment under SAMA's 33% debt-burden cap. Suggest talking to an agent.";
		double rate = safeRate(lt, 100_000, tenure);
		double r = rate / 1200.0;
		double k = Math.pow(1 + r, tenure);
		double maxLoan = Math.floor((maxEmi * (k - 1) / (r * k)) / 1000) * 1000;

		// Company product cap (nationality-aware)
		double cap = policy.maxAmount(lt, nationality);
		String capNote = "";
		if (maxLoan > cap) {
			maxLoan = cap;
			maxEmi = finance.emi(maxLoan, rate, tenure);
			capNote = " (limited by the product maximum under company policy)";
		}
		return ("Maximum affordable loan ≈ %,.0f SAR over %d months at %.2f%%, with an installment of %,.0f SAR/month "
				+ "(SAMA 33%% debt-burden cap)%s. Indicative, subject to eligibility.")
				.formatted(maxLoan, tenure, rate, maxEmi, capNote);
	}

	@Tool(description = "Get the status of a loan application using the customer's 10-digit National ID.")
	public String getLoanStatus(@ToolParam(description = "10-digit National ID") String nationalId) {
		String digits = nationalId == null ? "" : nationalId.replaceAll("[^0-9]", "");
		audit.toolCall("tool-ctx", "getLoanStatus", digits.length() >= 4 ? "******" + digits.substring(digits.length() - 4) : "invalid");
		if (!digits.matches("\\d{10}"))
			return "Invalid National ID: it must be exactly 10 digits.";
		try {
			LoanStatusResponse resp = loanService.getStatusByNationalId(digits);
			String masked = "******" + digits.substring(6);
			return "Application status for ID " + masked + ": " + resp.toEnglish() + ".";
		} catch (Exception e) {
			return "Could not fetch the application status right now. Offer to raise a callback request instead.";
		}
	}

	@Tool(description = "Early settlement estimate for an active loan: outstanding balance plus the SAMA-capped fee (max 3 months' profit).")
	public String earlySettlementQuote(@ToolParam(description = "Original loan amount in SAR") double amount,
			@ToolParam(description = "Original tenure in months") int tenureMonths,
			@ToolParam(description = "Installments already paid") int paidInstallments,
			@ToolParam(description = "Loan type: PERSONAL, HOME or AUTO") String loanType) {
		audit.toolCall("tool-ctx", "earlySettlementQuote",
				amount + "/" + tenureMonths + "/" + paidInstallments + "/" + loanType);
		if (amount < 1000 || tenureMonths < 6 || paidInstallments < 1 || paidInstallments >= tenureMonths)
			return "Invalid input: paid installments must be between 1 and tenure-1.";
		LoanType lt = parseType(loanType);
		double rate = safeRate(lt, amount, tenureMonths);
		double r = rate / 1200.0;
		double emi = finance.emi(amount, rate, tenureMonths);
		double pk = Math.pow(1 + r, paidInstallments);
		double balance = amount * pk - emi * (pk - 1) / r;
		if (balance <= 0)
			return "By these numbers the loan is already fully repaid.";
		double fee = 0, b = balance;
		for (int i = 0; i < Math.min(3, tenureMonths - paidInstallments); i++) {
			double profit = b * r;
			fee += profit;
			b -= (emi - profit);
		}
		return ("Early settlement estimate: outstanding balance ≈ %,.2f SAR, settlement fee (capped at 3 months' profit per SAMA) ≈ %,.2f SAR, "
				+ "total ≈ %,.2f SAR. Indicative — final figure comes from the account statement.")
				.formatted(balance, fee, balance + fee);
	}

	@Tool(description = "Request a callback from a human agent. Use when the customer wants to talk to a person and has given name, Saudi mobile and preferred time. Returns a reference number.")
	public String requestAgentCallback(@ToolParam(description = "Customer's name") String name,
			@ToolParam(description = "Saudi mobile, e.g. 05XXXXXXXX") String saudiMobile,
			@ToolParam(description = "Preferred time: morning, afternoon or evening") String preferredTime) {
		String digits = saudiMobile == null ? "" : saudiMobile.replaceAll("[^0-9]", "");
		String normalized = digits.matches("^05\\d{8}$") ? "966" + digits.substring(1)
				: digits.matches("^9665\\d{8}$") ? digits : digits.matches("^5\\d{8}$") ? "966" + digits : null;
		audit.toolCall("tool-ctx", "requestAgentCallback", (name == null ? "?" : name) + "/" + preferredTime);
		if (name == null || name.isBlank() || normalized == null)
			return "Missing or invalid details: need the customer's name and a valid Saudi mobile (05XXXXXXXX). Ask for them.";
		var lead = leadStore.save("llm-tool", name.trim(), normalized,
				preferredTime == null || preferredTime.isBlank() ? "any time" : preferredTime.trim());
		return "Callback registered. Reference: " + lead.ref() + ". An advisor will call " + name.trim() + " "
				+ lead.preferredTime() + ".";
	}

	private LoanType parseType(String loanType) {
		if (loanType == null)
			return LoanType.PERSONAL;
		try {
			String t = loanType.trim().toUpperCase();
			if (t.contains("HOME") || t.contains("MORTGAGE"))
				return LoanType.MORTGAGE;
			if (t.contains("AUTO") || t.contains("CAR"))
				return LoanType.AUTO;
			return LoanType.valueOf(t);
		} catch (IllegalArgumentException e) {
			return LoanType.PERSONAL;
		}
	}

	private double safeRate(LoanType lt, double amount, int tenure) {
		double rate = Double.NaN;
		try {
			rate = rateService.getRate(lt, amount, tenure, null);
		} catch (Exception ignored) {
		}
		if (Double.isNaN(rate) || rate <= 0) {
			rate = switch (lt) {
			case PERSONAL -> 7.0;
			case MORTGAGE, HOME -> 6.25;
			case AUTO -> 5.5;
			};
		}
		return rate;
	}
}
