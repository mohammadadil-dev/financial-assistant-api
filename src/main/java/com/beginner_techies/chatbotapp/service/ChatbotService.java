package com.beginner_techies.chatbotapp.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.config.DocumentLoader;
import com.beginner_techies.chatbotapp.dto.EligibilityState;
import com.beginner_techies.chatbotapp.dto.IntentResult;
import com.beginner_techies.chatbotapp.enums.AccountType;
import com.beginner_techies.chatbotapp.enums.LoanType;
import com.beginner_techies.chatbotapp.record.ChatOption;
import com.beginner_techies.chatbotapp.record.ChatReply;
import com.beginner_techies.chatbotapp.util.FinanceTools;

@Service
public class ChatbotService {

	private final ChatClient chatClient;
	private final VectorStore vectorStore; // if you need elsewhere
	private final IntentDetectorService router;
	private final EligibilitySessionStore sessions;
	private final EligibilityService eligibility;
	private final FinanceTools tools;
	private final RateService rateService;
	private final DocumentLoader documentLoader;

	private static final String SYSTEM = """
			You are a bilingual (Arabic + English) finance assistant for customers in the KSA.
			- Be concise, professional, friendly.
			- Match the user's language (Arabic or English).
			- Use provided CONTEXT for factual answers; if CONTEXT is empty, say "I don't have that information."
			- Never request or echo full sensitive data. Ask for authentication for account-specific info.
			- Not financial advice; general guidance only.
			""";

	public ChatbotService(ChatClient chatClient, VectorStore vectorStore, IntentDetectorService router,
			EligibilitySessionStore sessions, EligibilityService eligibility, FinanceTools tools,
			RateService rateService, DocumentLoader documentLoader) {
		this.chatClient = chatClient;
		this.vectorStore = vectorStore;
		this.router = router;
		this.sessions = sessions;
		this.eligibility = eligibility;
		this.tools = tools;
		this.rateService = rateService;
		this.documentLoader = documentLoader;
	}

	// ====== Public entrypoint ======
	public ChatReply handleMessage(String userId, String userMessageRaw) {
		// 0) Extract plain text (supports {"content":"..."} or {"message":"..."})
		String raw = extractMessage(userMessageRaw);
		String lower = raw == null ? "" : raw.trim().toLowerCase();

		// 1) Update language from THIS message so reply matches user
		sessions.setLangByMessage(userId, raw);
		var state = sessions.get(userId);
		String lang = state.lang();

		// --- map quick-pick ids for income → payload (defensive)
		if ("inc10k".equalsIgnoreCase(lower))
			raw = "ar".equals(lang) ? "دخل 10000" : "income 10000";
		if ("inc15k".equalsIgnoreCase(lower))
			raw = "ar".equals(lang) ? "دخل 15000" : "income 15000";
		if ("inc20k".equalsIgnoreCase(lower))
			raw = "ar".equals(lang) ? "دخل 20000" : "income 20000";
		
		if ("nat_sa".equalsIgnoreCase(lower) || "saudi".equalsIgnoreCase(lower)) {
		    raw = "nationality Saudi";
		}
		if ("nat_nonsa".equalsIgnoreCase(lower)
		        || "non_saudi".equalsIgnoreCase(lower)
		        || "non-saudi".equalsIgnoreCase(lower)
		        || "non saudi".equalsIgnoreCase(lower)
		        || "expat".equalsIgnoreCase(lower)) {
		    raw = "nationality Non-Saudi";
		}
		// Arabic direct tokens (if user typed them literally)
		if (raw.contains("الجنسية سعودي")) raw = "nationality Saudi";
		if (raw.contains("الجنسية غير سعودي") || raw.contains("غير سعودي")) raw = "nationality Non-Saudi";

		// 🔧 recompute lower AFTER raw may have been changed
		lower = raw == null ? "" : raw.trim().toLowerCase();

		// NEW: parse slot-like updates directly from this utterance
		boolean slotTouched = tryDirectSlotUpdate(state, raw);

		// 🔧 numeric-only fallback for income if we’re mid-eligibility and still
		// missing income
		if (!slotTouched && state.getLoanType() != null && state.getMonthlyIncome() == null) {
			String latin = arabicDigitsToLatin(raw).replaceAll("[,\\s]", "");
			if (latin.matches("^\\d{3,}$")) { // e.g., 8000, 12000
				try {
					state.setMonthlyIncome(Double.parseDouble(latin));
					slotTouched = true;
				} catch (NumberFormatException ignored) {
				}
			}
		}

		// Language toggles
		if (lower.equals("lang ar")) {
			state.setLang("ar");
			return assistanceMenu("ar");
		}
		if (lower.equals("lang en")) {
			state.setLang("en");
			return assistanceMenu("en");
		}

		// ---------------- Global shortcuts (no LLM) ----------------

		// Menu
		if (isMenuCommand(raw)) {
			return assistanceMenu(lang);
		}

		// Reset
		if (lower.equals("reset") || lower.equals("start over") || raw.contains("بدء من جديد")) {
			sessions.reset(userId);
			sessions.setLangByMessage(userId, raw);
			return assistanceMenu(sessions.get(userId).lang());
		}

		// 🔧 QUICK-PICK BUTTON IDS for amount/tenure/affordability
		// (If you already have handleQuickPicks(...) method, use it here)
		ChatReply quick = handleQuickPicks(lower, raw, state);
		if (quick != null)
			return quick;

		// ---------------- Loan-type gate & selections (no LLM) ----------------
		if (lower.contains("elig") || lower.contains("emi") || lower.contains("loan") || raw.contains("تمويل")) {
			boolean mentionsType = lower.contains("personal") || lower.contains("mortgage") || lower.contains("home")
					|| lower.contains("car") || lower.contains("auto") || raw.contains("تمويل شخصي")
					|| raw.contains("تمويل عقاري") || raw.contains("رهن") || raw.contains("سكني")
					|| raw.contains("تمويل سيارة");
			if (state.getLoanType() == null && !mentionsType) {
				return loanTypeMenu(lang);
			}
		}

		// Explicit loan-type selections (buttons or free text)
		if (lower.equals("personal financing") || lower.equals("personal loan") || lower.startsWith("loan personal")
				|| lower.equals("loan_personal") || raw.contains("تمويل شخصي")) {
			state.setLoanType(LoanType.PERSONAL);
			return afterLoanTypeChosen(state);
		}
		if (lower.equals("mortgage") || lower.contains("home loan") || lower.startsWith("loan mortgage")
				|| lower.equals("loan_mortgage") || raw.contains("تمويل عقاري") || raw.contains("رهن")
				|| raw.contains("سكني")) {
			state.setLoanType(LoanType.MORTGAGE);
			return afterLoanTypeChosen(state);
		}
		if (lower.equals("car loan") || lower.contains("auto loan") || lower.startsWith("loan auto")
				|| lower.equals("loan_auto") || raw.contains("تمويل سيارة") || raw.contains("سيارة")) {
			state.setLoanType(LoanType.AUTO);
			return afterLoanTypeChosen(state);
		}

		// ---------------- Account opening (no LLM) ----------------
		if (lower.matches("^(open(ing)?\\s+an?\\s*account|open\\s*account|account\\s*opening)$")
				|| raw.contains("فتح حساب") || raw.contains("فتح حساب بنكي")) {
			return handleOpenAccount(state);
		}

		// Account type choices
		if (lower.equals("acct_saving") || lower.contains("savings account") || raw.contains("حساب توفير")) {
			return handleAccountTypeDetails(state, AccountType.SAVINGS);
		}
		if (lower.equals("acct_current") || lower.contains("current account") || raw.contains("حساب جاري")) {
			return handleAccountTypeDetails(state, AccountType.CURRENT);
		}
		if (lower.equals("acct_salary") || lower.contains("salary account") || raw.contains("حساب راتب")) {
			return handleAccountTypeDetails(state, AccountType.SALARY);
		}

		// Start account application
		if (lower.equals("acct_start") || lower.equals("i want to open an account")
				|| raw.contains("أرغب في فتح حساب")) {
			return ChatReply.options(
					lang.equals("ar") ? "رائع! سنبدأ بزيارة التحقق من الهوية (KYC)."
							: "Great! Let’s begin with identity verification (KYC).",
					withNav(List.of(
							new ChatOption("kyc_start",
									lang.equals("ar") ? "ابدأ التحقق من الهوية" : "Start identity verification",
									lang.equals("ar") ? "أريد بدء التحقق" : "I want to start verification"),
							new ChatOption("contact", lang.equals("ar") ? "التحدث إلى موظف" : "Talk to an agent",
									lang.equals("ar") ? "أريد التحدث إلى موظف" : "I want to talk to a human agent")),
							lang));
		}

		// ---------------- Direct command shortcuts (no LLM) ----------------

		// Eligibility
		if (isEligCommand(lower, raw)) {
			if (state.getLoanType() == null)
				return loanTypeMenu(lang);
			return askForMissingEligibility(state);
		}

		// EMI
		if (isEmiCommand(lower, raw)) {
			if (state.getLoanType() == null)
				return loanTypeMenu(lang);
			return handleEmiCalc(state);
		}

		// Docs/FAQ (RAG)
		if (isDocsCommand(lower, raw)) {
			String seed = switch (state.getLoanType() == null ? LoanType.PERSONAL : state.getLoanType()) {
			case PERSONAL ->
				lang.equals("ar") ? "المستندات المطلوبة لتمويل شخصي" : "required documents for personal loan in KSA";
			case MORTGAGE, HOME -> lang.equals("ar") ? "المستندات المطلوبة لتمويل عقاري"
					: "required documents for mortgage/home loan in KSA";
			case AUTO ->
				lang.equals("ar") ? "المستندات المطلوبة لتمويل سيارة" : "required documents for auto/car loan in KSA";
			};
			return handleFaqRag(state, seed);
		}

		// ---------------- keep/enter eligibility flow if needed ----------------
		boolean midEligibility = state.getLoanType() != null && !state.hasMinimumEligibility();

		// 🔧 If they provided a slot now OR they are mid-flow, keep prompting next
		// missing slot
		if ((slotTouched || midEligibility) && !isDocsCommand(lower, raw) && !isEmiCommand(lower, raw)
				&& !isMenuCommand(raw) && !lower.equals("reset") && !lower.equals("start over")
				&& !raw.contains("بدء من جديد")) {
			return askForMissingEligibility(state);
		}

		// re-ask nationality if they ask *about* nationality
		if (containsPhrase(lower, "what is your nationality") || raw.contains("ما هي جنسيتك")
				|| raw.contains("ما هي جنسيتى") || raw.contains("جنسيتي") || raw.contains("جنسيتك")) {
			return askForMissingEligibility(state);
		}

		// ---------------- Router / LLM (only if nothing matched) ----------------
		var intent = router.detect(raw, state.lang()); // pass RAW + detected lang
		state = sessions.merge(userId, intent); // merge slots
		return routeIntent(intent, state, raw, userId);
	}

	// ====== Routing ======
	private ChatReply routeIntent(IntentResult intent, EligibilityState state, String userMessage, String userId) {
		return switch (intent.intent) {
		case ELIGIBILITY_SLOT_UPDATE -> {
			yield askForMissingEligibility(state); // keep in slot-filling; no RAG here
		}
		case ELIGIBILITY_CHECK -> {
			if (state.getLoanType() == null)
				yield loanTypeMenu(state.lang());
			yield handleEligibility(state);
		}
		case EMI_CALC -> {
			if (state.getLoanType() == null)
				yield loanTypeMenu(state.lang());
			yield handleEmiCalc(state);
		}
		case FAQ_RAG -> {
			yield handleFaqRag(state, userMessage); // RAG
		}
		case ACCOUNT_QUERY ->
			ChatReply.text(state.lang().equals("ar") ? "للاطّلاع على معلومات حسابك، الرجاء تسجيل الدخول."
					: "Please sign in to continue. Once authenticated, I can fetch your account or loan status.");
		default -> {
			yield assistanceMenu(state.lang());
		}
		};
	}

	// ====== Handlers ======
	private ChatReply askForMissingEligibility(EligibilityState state) {
		String lang = state.lang();
		if (state.getLoanType() == null)
			return loanTypeMenu(lang);

		if (state.getMonthlyIncome() == null) {
			return "ar".equals(lang)
					? ChatReply.options("ما هو دخلك الشهري (بالريال)؟ يمكنك الكتابة مثل: \"دخل 12000\".",
							withNav(List.of(new ChatOption("inc10k", "10,000 ريال", ""),
									new ChatOption("inc15k", "15,000 ريال", ""),
									new ChatOption("inc20k", "20,000 ريال", "")), "ar"))
					: ChatReply.options("What is your monthly income (SAR)? You can type e.g., \"income 12000\".",
							withNav(List.of(new ChatOption("inc10k", "10,000 SAR", ""),
									new ChatOption("inc15k", "15,000 SAR", ""),
									new ChatOption("inc20k", "20,000 SAR", "")), "en"));
		}

		if (state.getEmployerType() == null || state.getEmployerType().isBlank()) {
			return "ar".equals(lang)
					? ChatReply.options("ما نوع جهة عملك؟", withNav(
							List.of(new ChatOption("emp_gov", "حكومي", ""), new ChatOption("emp_priv", "خاص", ""),
									new ChatOption("emp_cont", "متعاقد", ""), new ChatOption("emp_self", "عمل حر", "")),
							"ar"))
					: ChatReply.options("What is your employer type?",
							withNav(List.of(new ChatOption("emp_gov", "Government", ""),
									new ChatOption("emp_priv", "Private", ""),
									new ChatOption("emp_cont", "Contract", ""),
									new ChatOption("emp_self", "Self-employed", "")), "en"));
		}

		if (state.getServiceMonths() == null) {
			return "ar".equals(lang)
					? ChatReply.options("كم عدد الأشهر التي عملتها لدى جهة عملك الحالية؟",
							withNav(List.of(new ChatOption("srv6", "6 أشهر", ""),
									new ChatOption("srv12", "12 شهرًا", ""), new ChatOption("srv24", "24 شهرًا", "")),
									"ar"))
					: ChatReply.options("How many months have you been with your current employer?",
							withNav(List.of(new ChatOption("srv6", "6 months", ""),
									new ChatOption("srv12", "12 months", ""), new ChatOption("srv24", "24 months", "")),
									"en"));
		}

		if (state.getHasExistingLoans() == null) {
			return "ar".equals(lang)
					? ChatReply.options("هل لديك قروض حالية؟",
							withNav(List.of(new ChatOption("loan_yes", "نعم", ""), new ChatOption("loan_no", "لا", "")),
									"ar"))
					: ChatReply.options("Do you currently have any active loans?", withNav(
							List.of(new ChatOption("loan_yes", "Yes", ""), new ChatOption("loan_no", "No", "")), "en"));
		}

		if (state.getNationality() == null || state.getNationality().isBlank()) {
			return "ar".equals(lang)
					? ChatReply.options("ما هي جنسيتك؟",
							withNav(List.of(new ChatOption("nat_sa", "سعودي", ""),
									new ChatOption("nat_nonsa", "غير سعودي", "")), "ar"))
					: ChatReply.options("What is your nationality?",
							withNav(List.of(new ChatOption("nat_sa", "Saudi", ""),
									new ChatOption("nat_nonsa", "Non-Saudi", "")), "en"));
		}

		return "ar".equals(lang)
				? ChatReply.options("تم حفظ بياناتك للأهلية. ماذا تريد أن تفعل بعد ذلك؟",
						withNav(List.of(new ChatOption("elig_go", "تحقّق من الأهلية الآن", ""),
								new ChatOption("emi_go", "احسب القسط (EMI)", "")), "ar"))
				: ChatReply.options("Got it. Your eligibility details are saved. What would you like to do next?",
						withNav(List.of(new ChatOption("elig_go", "Check eligibility now", ""),
								new ChatOption("emi_go", "Calculate EMI", "")), "en"));
	}

	private ChatReply handleEligibility(EligibilityState s) {
		// Gate: make sure we have the triad; keep asking instead of judging
		if (s.getLoanType() == null)
			return loanTypeMenu(s.lang());

		var v = eligibility.evaluateDetailed(s); // <- uses deterministic rules (no LLM)
		String lang = s.lang();
		boolean ar = "ar".equals(lang);

		// Helper lambdas for localized strings
		java.util.function.Function<String, String> lz = code -> switch (code) {
		case "NEED_INCOME" -> ar ? "ما هو دخلك الشهري؟" : "What is your monthly income?";
		case "NEED_EMPLOYER_TYPE" -> ar ? "ما نوع جهة عملك؟ (حكومي/خاص/متعاقد/عمل حر)"
				: "What is your employer type? (government/private/contract/self-employed)";
		case "NEED_SERVICE_MONTHS" ->
			ar ? "كم شهراً عملت لدى جهة عملك الحالية؟" : "How many months have you been with your current employer?";
		case "LOW_SERVICE_MONTHS" -> ar ? "مدة الخدمة أقل من الحد الأدنى المطلوب لهذا النوع من جهات العمل."
				: "Months of service below minimum for this employer type.";
		case "LOW_INCOME" ->
			ar ? "الدخل الشهري أقل من الحد الأدنى المطلوب." : "Monthly income below the required minimum.";
		case "DTI_ABOVE_50" ->
			ar ? "نسبة الالتزامات إلى الدخل تتجاوز 50٪ حالياً." : "Your debt-to-income ratio currently exceeds 50%.";
		case "DTI_ABOVE_40" -> ar ? "نسبة الالتزامات إلى الدخل أعلى من 40٪ الموصى بها."
				: "Your debt-to-income ratio is above the typical 40% guideline.";
		default -> code;
		};

		// Build message
		StringBuilder msg = new StringBuilder();
		switch (v.status) {
		case NEED_INFO -> {
			msg.append(ar ? "قبل إعطاء تقييم أوّلي، أحتاج بعض المعلومات:"
					: "Before giving a preliminary assessment, I need a couple of details:");
			for (String r : v.reasons) {
				msg.append(ar ? "\n• " : "\n• ").append(lz.apply(r));
			}
			// Offer quick slot buttons
			var opts = new java.util.ArrayList<ChatOption>();
			if (s.getMonthlyIncome() == null) {
				opts.add(
						new ChatOption("inc10k", ar ? "10,000 ريال" : "10,000 SAR", ar ? "دخل 10000" : "income 10000"));
				opts.add(
						new ChatOption("inc15k", ar ? "15,000 ريال" : "15,000 SAR", ar ? "دخل 15000" : "income 15000"));
				opts.add(
						new ChatOption("inc20k", ar ? "20,000 ريال" : "20,000 SAR", ar ? "دخل 20000" : "income 20000"));
			}
			if (s.getEmployerType() == null) {
				opts.add(new ChatOption("emp_gov", ar ? "حكومي" : "Government",
						ar ? "جهة العمل حكومي" : "employer government"));
				opts.add(new ChatOption("emp_priv", ar ? "خاص" : "Private", ar ? "جهة العمل خاص" : "employer private"));
				opts.add(new ChatOption("emp_cont", ar ? "متعاقد" : "Contract",
						ar ? "جهة العمل متعاقد" : "employer contract"));
				opts.add(new ChatOption("emp_self", ar ? "عمل حر" : "Self-employed",
						ar ? "جهة العمل عمل حر" : "employer self"));
			}
			if (s.getServiceMonths() == null) {
				opts.add(new ChatOption("srv6", ar ? "6 أشهر" : "6 months", ar ? "خدمة 6 شهر" : "service 6 months"));
				opts.add(new ChatOption("srv12", ar ? "12 شهرًا" : "12 months",
						ar ? "خدمة 12 شهر" : "service 12 months"));
				opts.add(new ChatOption("srv24", ar ? "24 شهرًا" : "24 months",
						ar ? "خدمة 24 شهر" : "service 24 months"));
			}
			return ChatReply.options(msg.toString(), withNav(opts, lang));
		}

		case PRELIM_INELIGIBLE -> {
			msg.append(ar ? "قد تكون غير مؤهل مبدئيًا بناءً على المعلومات الحالية. هذا ليس قرارًا نهائيًا.\nالأسباب:"
					: "You may be preliminarily ineligible based on current data. This is not a final decision.\nReasons:");
			for (String r : v.reasons)
				msg.append("\n• ").append(lz.apply(r));

			// Suggest constructive next steps
			var opts = new java.util.ArrayList<ChatOption>();
			opts.add(new ChatOption("emi", ar ? "حساب القسط لمبلغ أقل" : "Try smaller amount",
					ar ? "مبلغ 100000 مدة 48 شهر" : "amount 100000 tenure 48 months"));
			opts.add(new ChatOption("emi", ar ? "زيادة مدة السداد" : "Increase tenure",
					ar ? "مدة 60 شهر" : "tenure 60 months"));
			opts.add(new ChatOption("elig", ar ? "تحديث الدخل/البيانات" : "Update income/details",
					ar ? "دخل 12000" : "income 12000"));
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent",
					ar ? "أريد التحدث إلى موظف" : "I want to talk to a human agent"));
			return ChatReply.options(msg.toString(), withNav(opts, lang));
		}

		case BORDERLINE, PREQUALIFIED -> {
			// If EMI/DTI available, show them; otherwise keep it brief
			if (v.estimatedEmi != null && s.getAmount() != null && s.getTenureMonths() != null) {
				String cur = s.getCurrency() != null ? s.getCurrency() : (ar ? "ريال" : "SAR");
				String l1 = ar ? "تقييم أوّلي إيجابي." : "Preliminary check looks good.";
				String l2 = (ar ? "قسط تقريبي ≈ %, .2f %s شهريًا لمبلغ %, .0f %s على %d شهر. معدل سنوي مطبق: %.2f%%."
						: "Estimated EMI ≈ %, .2f %s / month for %, .0f %s over %d months. Applied annual rate: %.2f%%.")
						.formatted(v.estimatedEmi, cur, s.getAmount(), cur, s.getTenureMonths(),
								v.appliedAnnualRate == null ? 0.0 : v.appliedAnnualRate)
						.replace(" ,", ",");
				msg.append(l1).append("\n").append(l2);

				if (v.dtiRatio != null && v.maxAllowedEmi != null) {
					double dtiPct = Math.round(v.dtiRatio * 10000.0) / 100.0;
					String l3 = ar ? ("نسبة الالتزامات إلى الدخل ≈ " + dtiPct + "٪ (الحد الإرشادي 40٪).")
							: ("DTI ≈ " + dtiPct + "% (typical guideline 40%).");
					msg.append("\n").append(l3);
				}
			} else {
				msg.append(ar ? "تبدو شروط الأهلية الأساسية مستوفاة.\nهل ترغب بحساب القسط أو متابعة التقديم؟"
						: "You appear to meet the basic eligibility criteria.\nWould you like to calculate EMI or start an application?");
			}

			var opts = new java.util.ArrayList<ChatOption>();
			opts.add(new ChatOption("emi", ar ? "حساب القسط (EMI)" : "Calculate EMI",
					ar ? "حاسبة القسط" : "Calculate EMI"));
			opts.add(new ChatOption("acct_start", ar ? "بدء التقديم" : "Start application",
					ar ? "أرغب في فتح حساب" : "I want to open an account"));
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent",
					ar ? "أريد التحدث إلى موظف" : "I want to talk to a human agent"));
			return ChatReply.options(msg.toString(), withNav(opts, lang));
		}
		}

		// Fallback (shouldn't happen)
		return assistanceMenu(lang);
	}

	private ChatReply handleEmiCalc(EligibilityState state) {
		if (state.getLoanType() == null)
			return loanTypeMenu(state.lang());

		// Ask for amount/tenure (ID-only suggestions; Arabic labels, empty payload)
		if (state.getAmount() == null || state.getTenureMonths() == null) {
			String prompt = state.lang().equals("ar")
					? "خلّينا نحسب القسط الشهري. اختر مبلغ القرض والمدة، أو اكتب تفاصيلك (مثلاً: \"قرض 150000 لمدة 42 شهر\")."
					: "Let’s calculate your EMI. Choose an amount & tenure, or type your own (e.g., 'loan 150000 for 42 months').";

			var suggestions = state.lang().equals("ar")
					? List.of(new ChatOption("amt100k", "100,000 ريال / 36 شهر", ""),
							new ChatOption("amt200k", "200,000 ريال / 48 شهر", ""),
							new ChatOption("amt300k", "300,000 ريال / 60 شهر", ""))
					: List.of(new ChatOption("amt100k", "100,000 SAR / 36 months", ""),
							new ChatOption("amt200k", "200,000 SAR / 48 months", ""),
							new ChatOption("amt300k", "300,000 SAR / 60 months", ""));

			return ChatReply.options(prompt, withNav(suggestions, state.lang()));
		}

		double amount = state.getAmount();
		int tenure = state.getTenureMonths();
		double rate = rateService.getRate(state.getLoanType(), amount, tenure, state.getEmployerType());
		if (Double.isNaN(rate) || rate <= 0.0)
			rate = fallbackRate(state.getLoanType());

		double emi = tools.emi(amount, rate, tenure);
		String cur = (state.getCurrency() != null) ? state.getCurrency() : (state.lang().equals("ar") ? "ريال" : "SAR");

		double totalPayable = Math.round(emi * tenure * 100.0) / 100.0;
		double interestOnly = Math.round((totalPayable - amount) * 100.0) / 100.0;

		String text = state.lang().equals("ar")
				? ("""
						القسط ≈ %, .2f %s شهرياً
						لقرض قدره %, .0f %s لمدة %d شهر.
						نسبة الفائدة السنوية المطبقة: %.2f%%.
						الإجمالي التقريبي للسداد: %, .2f %s (الفوائد: %, .2f %s).
						""").formatted(emi, cur, amount, cur, tenure, rate, totalPayable, cur, interestOnly, cur)
						.replace(" ,", ",")
				: ("""
						EMI ≈ %, .2f %s / month
						For %, .0f %s over %d months.
						Applied annual rate: %.2f%%.
						Total payable ≈ %, .2f %s (interest ≈ %, .2f %s).
						""").formatted(emi, cur, amount, cur, tenure, rate, totalPayable, cur, interestOnly, cur)
						.replace(" ,", ",");

		var options = new ArrayList<ChatOption>();
		if (tenure > 12)
			options.add(
					new ChatOption("ten_down", state.lang().equals("ar") ? "جرّب مدة أقصر" : "Try shorter tenure", ""));
		int longer = Math.min(120, tenure + 12);
		options.add(new ChatOption("ten_up", state.lang().equals("ar") ? "جرّب مدة أطول" : "Try longer tenure", ""));
		options.add(new ChatOption("chg_amt", state.lang().equals("ar") ? "تغيير مبلغ القرض" : "Change amount", ""));

		if (state.getMonthlyIncome() == null && amount >= 5_000) {
			options.add(new ChatOption("add_inc",
					state.lang().equals("ar") ? "فحص القدرة (أضف دخلي)" : "Check affordability (add income)", ""));
		}
		options.add(new ChatOption("elig", state.lang().equals("ar") ? "التحقق من الأهلية" : "Check eligibility", ""));

		return ChatReply.options(text, withNav(options, state.lang()));
	}

	private ChatReply handleOpenAccount(EligibilityState state) {
		String lang = state.lang();
		String question = lang.equals("ar") ? "أي نوع من الحسابات ترغب في فتحه؟"
				: "Which type of account would you like to open?";
		var choices = lang.equals("ar")
				? List.of(new ChatOption("acct_saving", "حساب توفير", ""),
						new ChatOption("acct_current", "حساب جاري", ""), new ChatOption("acct_salary", "حساب راتب", ""))
				: List.of(new ChatOption("acct_saving", "Savings Account", ""),
						new ChatOption("acct_current", "Current Account", ""),
						new ChatOption("acct_salary", "Salary Account", ""));
		return ChatReply.options(question, withNav(choices, lang));
	}

	private ChatReply handleAccountTypeDetails(EligibilityState state, AccountType type) {
		String lang = state.lang();
		String query = switch (type) {
		case SAVINGS -> lang.equals("ar") ? "المستندات المطلوبة لفتح حساب توفير"
				: "documents required to open a savings account in KSA";
		case CURRENT -> lang.equals("ar") ? "المستندات المطلوبة لفتح حساب جاري"
				: "documents required to open a current account in KSA";
		case SALARY -> lang.equals("ar") ? "المستندات المطلوبة لفتح حساب راتب"
				: "documents required to open a salary account in KSA";
		};

		List<Document> docs;
		try {
			docs = documentLoader.searchByLangDiversified(query, lang, 6, 2);
		} catch (Exception e) {
			docs = List.of();
		}
		String ctx = documentLoader.joinContents(docs);

		String title, bullets, note;
		if ("ar".equals(lang)) {
			title = switch (type) {
			case SAVINGS -> "متطلبات فتح حساب توفير:";
			case CURRENT -> "متطلبات فتح حساب جاري:";
			case SALARY -> "متطلبات فتح حساب راتب:";
			};
			bullets = """
					• الهوية الوطنية
					• إثبات العنوان (إن وُجد)
					• خطاب جهة العمل/الطالب (إذا طُلب)
					""".strip();
			note = "ملاحظة: قد تختلف المتطلبات حسب نوع الحساب وجهة العمل، وقد نطلب مستندات إضافية أثناء التقديم.";
		} else {
			title = switch (type) {
			case SAVINGS -> "Requirements to open a Savings Account:";
			case CURRENT -> "Requirements to open a Current Account:";
			case SALARY -> "Requirements to open a Salary Account:";
			};
			bullets = """
					• National ID
					• Proof of Address (if applicable)
					• Employer or Student Letter (if requested)
					""".strip();
			note = "Note: Requirements may vary by account type and employer; additional documents may be requested.";
		}

		String text = ("%s\n%s\n\n%s").formatted(title, bullets, note).strip();

		var options = new ArrayList<ChatOption>();
		if ("ar".equals(lang)) {
			options.add(new ChatOption("acct_start", "ابدأ فتح الحساب", ""));
			options.add(new ChatOption("acct_docs_more", "تفاصيل أكثر", ""));
			options.add(new ChatOption("contact", "التحدث إلى موظف", ""));
		} else {
			options.add(new ChatOption("acct_start", "Start application", ""));
			options.add(new ChatOption("acct_docs_more", "See more details", ""));
			options.add(new ChatOption("contact", "Talk to an agent", ""));
		}
		return ChatReply.options(text, withNav(options, lang));
	}

	private ChatReply handleFaqRag(EligibilityState state, String raw) {
		String lang = state.lang();
		String q = sanitizeQuery(raw);
		if (q.length() < 3) {
			return ChatReply.options(
					lang.equals("ar") ? "هل يمكنك التوضيح أكثر؟ ما الذي تريد معرفته تحديداً؟"
							: "Could you clarify what you’d like to know?",
					withNav(List.of(new ChatOption("docs",
							lang.equals("ar") ? "المستندات المطلوبة" : "Required Documents", "")), lang));
		}

		List<Document> hits = List.of();
		try {
			hits = documentLoader.searchByLangDiversified(q, lang, 6, 2);
		} catch (Exception ignored) {
		}
		String ctx = documentLoader.joinContents(hits);

		String answer = chatClient.prompt().system(SYSTEM + (ctx.isBlank() ? "" : "\nCONTEXT:\n" + ctx)).user(raw)
				.call().content();

		return ChatReply.text(answer);
	}

	// ====== Builders ======
	private ChatReply assistanceMenu(String lang) {
		String out = "ar".equals(lang) ? "ar" : "en";
		if ("ar".equals(out)) {
			return ChatReply.options("مرحباً! أنا مساعدك المالي. كيف أستطيع مساعدتك اليوم؟",
					withNav(List.of(new ChatOption("elig", "التحقق من أهلية القرض", ""),
							new ChatOption("emi", "حاسبة القسط الشهري (EMI)", ""),
							new ChatOption("acct", "فتح حساب", ""), new ChatOption("docs", "المستندات المطلوبة", ""),
							new ChatOption("lang_en", "English", "")), "ar"));
		}
		return ChatReply.options("Hey! I’m your finance assistant. How can I help you today?",
				withNav(List.of(new ChatOption("elig", "Check Loan Eligibility", ""),
						new ChatOption("emi", "Calculate EMI", ""), new ChatOption("acct", "Open an Account", ""),
						new ChatOption("docs", "Required Documents", ""), new ChatOption("lang_ar", "العربية", "")),
						"en"));
	}

	private ChatReply loanTypeMenu(String lang) {
		if ("ar".equals(lang)) {
			return ChatReply.options("أي نوع من التمويل ترغب به؟",
					withNav(List.of(new ChatOption("loan_personal", "تمويل شخصي", ""),
							new ChatOption("loan_mortgage", "تمويل عقاري / رهن", ""),
							new ChatOption("loan_auto", "تمويل سيارة", "")), "ar"));
		}
		return ChatReply.options("Which type of loan would you like?",
				withNav(List.of(new ChatOption("loan_personal", "Personal Financing", ""),
						new ChatOption("loan_mortgage", "Mortgage / Home Loan", ""),
						new ChatOption("loan_auto", "Car / Auto Loan", "")), "en"));
	}

	private ChatReply afterLoanTypeChosen(EligibilityState state) {
		String lang = state.lang();
		String t = switch (state.getLoanType()) {
		case PERSONAL -> lang.equals("ar") ? "تمويل شخصي" : "Personal Financing";
		case MORTGAGE, HOME -> lang.equals("ar") ? "تمويل عقاري / رهن" : "Mortgage / Home Loan";
		case AUTO -> lang.equals("ar") ? "تمويل سيارة" : "Car / Auto Loan";
		};
		if ("ar".equals(lang)) {
			return ChatReply.options("تم اختيار: " + t + ". ماذا تريد أن تفعل الآن?",
					withNav(List.of(new ChatOption("elig", "التحقق من الأهلية", ""),
							new ChatOption("emi", "حساب القسط (EMI)", ""),
							new ChatOption("docs", "المستندات المطلوبة", "")), "ar"));
		}
		return ChatReply.options("Selected: " + t + ". What would you like to do next?",
				withNav(List.of(new ChatOption("elig", "Check Eligibility", ""),
						new ChatOption("emi", "Calculate EMI", ""), new ChatOption("docs", "Required Documents", "")),
						"en"));
	}

	// ====== Quick-picks (IDs only) ======
	private ChatReply handleQuickPicks(String lower, String raw, EligibilityState state) {
		switch (lower) {
		case "amt100k" -> {
			state.setAmount(100_000.0);
			state.setTenureMonths(36);
			return handleEmiCalc(state);
		}
		case "amt200k" -> {
			state.setAmount(200_000.0);
			state.setTenureMonths(48);
			return handleEmiCalc(state);
		}
		case "amt300k" -> {
			state.setAmount(300_000.0);
			state.setTenureMonths(60);
			return handleEmiCalc(state);
		}

		case "ten_up" -> {
			Integer t = state.getTenureMonths();
			int next = Math.min(120, (t == null ? 36 : t + 12));
			state.setTenureMonths(next);
			return handleEmiCalc(state);
		}
		case "ten_down" -> {
			Integer t = state.getTenureMonths();
			int next = Math.max(12, (t == null ? 36 : t - 12));
			state.setTenureMonths(next);
			return handleEmiCalc(state);
		}

		case "chg_amt" -> {
			String L = state.lang();
			return ChatReply.options("ar".equals(L) ? "كم مبلغ القرض المرغوب؟" : "What loan amount would you like?",
					withNav(List.of(new ChatOption("amt100k", "ar".equals(L) ? "100,000 ريال" : "100,000 SAR", ""),
							new ChatOption("amt200k", "ar".equals(L) ? "200,000 ريال" : "200,000 SAR", ""),
							new ChatOption("amt300k", "ar".equals(L) ? "300,000 ريال" : "300,000 SAR", "")), L));
		}

		case "add_inc" -> {
			String L = state.lang();
			return ChatReply.options(
					"ar".equals(L) ? "أدخل دخلك الشهري (بالريال)." : "Enter your monthly income (SAR).",
					withNav(List.of(new ChatOption("inc10k", "ar".equals(L) ? "10,000 ريال" : "10,000 SAR", ""),
							new ChatOption("inc15k", "ar".equals(L) ? "15,000 ريال" : "15,000 SAR", ""),
							new ChatOption("inc20k", "ar".equals(L) ? "20,000 ريال" : "20,000 SAR", "")), L));
		}

		case "inc10k" -> {
			state.setMonthlyIncome(10_000.0);
			return askForMissingEligibility(state);
		}
		case "inc15k" -> {
			state.setMonthlyIncome(15_000.0);
			return askForMissingEligibility(state);
		}
		case "inc20k" -> {
			state.setMonthlyIncome(20_000.0);
			return askForMissingEligibility(state);
		}

		case "emp_gov" -> {
			state.setEmployerType("government");
			return askForMissingEligibility(state);
		}
		case "emp_priv" -> {
			state.setEmployerType("private");
			return askForMissingEligibility(state);
		}
		case "emp_cont" -> {
			state.setEmployerType("contract");
			return askForMissingEligibility(state);
		}
		case "emp_self" -> {
			state.setEmployerType("self-employed");
			return askForMissingEligibility(state);
		}

		case "srv6" -> {
			state.setServiceMonths(6);
			return askForMissingEligibility(state);
		}
		case "srv12" -> {
			state.setServiceMonths(12);
			return askForMissingEligibility(state);
		}
		case "srv24" -> {
			state.setServiceMonths(24);
			return askForMissingEligibility(state);
		}

		case "loan_yes" -> {
			state.setHasExistingLoans(true);
			return askForMissingEligibility(state);
		}
		case "loan_no" -> {
			state.setHasExistingLoans(false);
			return askForMissingEligibility(state);
		}

		case "elig_go" -> {
			return handleEligibility(state);
		}
		case "emi_go" -> {
			return handleEmiCalc(state);
		}
		}
		return null;
	}

	// ====== Helpers ======
	private String extractMessage(String raw) {
		if (raw == null)
			return "";
		String s = raw.trim();
		try {
			if (s.startsWith("{")) {
				var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				var node = mapper.readTree(s);
				var content = node.get("content");
				if (content != null && !content.isNull())
					return content.asText();
				var message = node.get("message");
				if (message != null && !message.isNull())
					return message.asText();
			}
		} catch (Exception ignored) {
		}
		return s;
	}

	private String resolveAndSetLang(String userId, String raw) {
		var st = sessions.get(userId);
		if (containsArabic(raw)) {
			st.setLang("ar");
			return "ar";
		}
		if (looksLikeAsciiPayload(raw)) {
			String prev = st.lang();
			if (prev == null || prev.isBlank()) {
				st.setLang("en");
				return "en";
			}
			return prev; // keep previous language
		}
		st.setLang("en");
		return "en";
	}

	private static boolean containsArabic(String s) {
		if (s == null || s.isBlank())
			return false;
		return s.codePoints().anyMatch(cp -> (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F)
				|| (cp >= 0x08A0 && cp <= 0x08FF) || (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF));
	}

	private static boolean looksLikeAsciiPayload(String s) {
		if (s == null)
			return false;
		String t = s.trim();
		return !t.isEmpty() && t.matches("^[a-z0-9_\\-\\s]+$");
	}

	private static boolean isMenuCommand(String raw) {
		if (raw == null)
			return false;
		String s = raw.trim();
		String lower = s.toLowerCase();
		return lower.equals("menu") || s.contains("القائمة") || s.contains("القائمة الرئيسية") || s.contains("منيو");
	}

	private boolean isEligCommand(String lower, String raw) {
		return lower.equals("elig") || lower.equals("eligibility") || lower.equals("elig_go") || raw.contains("أهلية");
	}

	private boolean isEmiCommand(String lower, String raw) {
		return lower.equals("emi") || lower.startsWith("calc emi") || lower.equals("emi_go") || raw.contains("قسط")
				|| raw.contains("حاسبة");
	}

	private boolean isDocsCommand(String lower, String raw) {
		return lower.equals("docs") || lower.contains("document") || raw.contains("المستندات");
	}

	private String sanitizeQuery(String s) {
		if (s == null)
			return "";
		var q = s.strip().replaceAll("\\p{Cntrl}", "");
		return q.length() > 300 ? q.substring(0, 300) : q;
	}

	private List<ChatOption> withNav(List<ChatOption> base, String lang) {
		var list = new ArrayList<>(base);
		if ("ar".equals(lang)) {
			list.add(new ChatOption("menu", "القائمة الرئيسية", ""));
			list.add(new ChatOption("reset", "بدء من جديد", ""));
		} else {
			list.add(new ChatOption("menu", "Main menu", ""));
			list.add(new ChatOption("reset", "Start over", ""));
		}
		return list;
	}

	private double fallbackRate(LoanType t) {
		return switch (t) {
		case PERSONAL -> 7.0;
		case MORTGAGE, HOME -> 6.25;
		case AUTO -> 5.5;
		};
	}

	private void mergeSlots(EligibilityState s, IntentResult in) {
		if (in == null)
			return;
		if (in.currency != null)
			s.setCurrency(in.currency);
		if (in.amount != null)
			s.setAmount(in.amount);
		if (in.tenureMonths != null)
			s.setTenureMonths(in.tenureMonths);
		if (in.annualRate != null)
			s.setAnnualRate(in.annualRate);

		if (in.monthlyIncome != null)
			s.setMonthlyIncome(in.monthlyIncome);
		if (in.employerType != null)
			s.setEmployerType(in.employerType);
		if (in.serviceMonths != null)
			s.setServiceMonths(in.serviceMonths);
		if (in.hasExistingLoans != null)
			s.setHasExistingLoans(in.hasExistingLoans);
		if (in.nationality != null)
			s.setNationality(in.nationality);

		if (in.loanType != null)
			s.setLoanType(in.loanType);
	}

	private static boolean containsPhrase(String haystack, String needle) {
		return haystack != null && haystack.contains(needle);
	}

	// ---- Slot parsing helpers (English + Arabic) ----

	private boolean tryDirectSlotUpdate(EligibilityState state, String raw) {
		if (raw == null || raw.isBlank())
			return false;
		String lower = raw.toLowerCase();

		boolean touched = false;

		// 1) Monthly income (income 12000 / دخل 12000 / راتب 12000 / salary 12000)
		var inc = findNumberAfterAny(raw, "income", "salary", "دخل", "راتب");
		if (inc != null) {
			state.setMonthlyIncome(inc);
			touched = true;
		}

		// 2) Employer type
		if (containsAny(lower, "employer government", "جهة العمل حكومي", "حكومي", "government")) {
			state.setEmployerType("government");
			touched = true;
		} else if (containsAny(lower, "employer private", "جهة العمل خاص", "خاص", "private")) {
			state.setEmployerType("private");
			touched = true;
		} else if (containsAny(lower, "employer contract", "جهة العمل متعاقد", "متعاقد", "contract")) {
			state.setEmployerType("contract");
			touched = true;
		} else if (containsAny(lower, "employer self", "عمل حر", "self-employed", "self employed")) {
			state.setEmployerType("self-employed");
			touched = true;
		}

		// 3) Service months (service 12 months / خدمة 12 شهر)
		var svc = findNumberAfterAny(raw, "service", "خدمة");
		if (svc != null) {
			state.setServiceMonths(svc.intValue());
			touched = true;
		}

		// 4) Existing loans yes/no (loans yes/no / قروض موجودة نعم/لا)
		if (containsAny(lower, "loans yes", "قروض موجودة نعم", "yes")) {
			state.setHasExistingLoans(true);
			touched = true;
		}
		if (containsAny(lower, "loans no", "قروض موجودة لا", "no", "لا")) {
			state.setHasExistingLoans(false);
			touched = true;
		}

		// 5) Nationality (quick common cases; extend as needed)
		if (containsAny(lower, "nationality saudi", "الجنسية سعودي", "سعودي")) {
			state.setNationality("Saudi");
			touched = true;
		} else if (containsAny(lower, "nationality non-saudi", "الجنسية غير سعودي", "غير سعودي")) {
			state.setNationality("Non-Saudi");
			touched = true;
		} else if (containsAny(lower, "indian", "هندي")) {
			state.setNationality("Indian");
			touched = true;
		}

		// 6) Amount (amount 150000 / مبلغ 150000)
		var amt = findNumberAfterAny(raw, "amount", "مبلغ", "قرض");
		if (amt != null) {
			state.setAmount(amt);
			touched = true;
		}

		// 7) Tenure months (tenure 36 months / مدة 36 شهر)
		var ten = findNumberAfterAny(raw, "tenure", "مدة");
		if (ten != null) {
			state.setTenureMonths(ten.intValue());
			touched = true;
		}

		// 8) “loan 150000 for 42 months” (English)
		var m1 = java.util.regex.Pattern
				.compile("loan\\s+([\\d,\\.]+)\\s+for\\s+(\\d+)\\s+months", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(raw);
		if (m1.find()) {
			Double a = parseNumber(m1.group(1));
			Integer t = parseIntSafe(m1.group(2));
			if (a != null) {
				state.setAmount(a);
				touched = true;
			}
			if (t != null) {
				state.setTenureMonths(t);
				touched = true;
			}
		}
		// 9) Arabic “قرض 150000 لمدة 42 شهر”
		var m2 = java.util.regex.Pattern.compile("قرض\\s+([\\d,\\.\\u0660-\\u0669]+)\\s+لمدة\\s+(\\d+)\\s+شهر")
				.matcher(raw);
		if (m2.find()) {
			Double a = parseNumber(m2.group(1));
			Integer t = parseIntSafe(arabicDigitsToLatin(m2.group(2)));
			if (a != null) {
				state.setAmount(a);
				touched = true;
			}
			if (t != null) {
				state.setTenureMonths(t);
				touched = true;
			}
		}

		return touched;
	}

	private static boolean containsAny(String haystack, String... needles) {
		if (haystack == null)
			return false;
		for (String n : needles)
			if (haystack.contains(n.toLowerCase()))
				return true;
		return false;
	}

	private static Double findNumberAfterAny(String raw, String... keys) {
		if (raw == null)
			return null;
		for (String k : keys) {
			// match: "<k> <number>" allowing Arabic numerals and commas
			var p = java.util.regex.Pattern.compile(k + "\\s+([\\d,\\.\\u0660-\\u0669]+)",
					java.util.regex.Pattern.CASE_INSENSITIVE);
			var m = p.matcher(raw);
			if (m.find())
				return parseNumber(m.group(1));
		}
		return null;
	}

	private static Double parseNumber(String s) {
		if (s == null)
			return null;
		s = arabicDigitsToLatin(s);
		s = s.replaceAll(",", "").trim();
		try {
			return Double.parseDouble(s);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static Integer parseIntSafe(String s) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return null;
		}
	}

	private static String arabicDigitsToLatin(String s) {
		if (s == null)
			return null;
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			// Arabic-Indic ٠١٢٣٤٥٦٧٨٩ \u0660-\u0669
			if (c >= 0x0660 && c <= 0x0669) {
				out.append((char) ('0' + (c - 0x0660)));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

}
