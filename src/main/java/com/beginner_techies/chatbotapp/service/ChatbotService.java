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
import com.beginner_techies.chatbotapp.dto.LoanStatusResponse;
import com.beginner_techies.chatbotapp.enums.LoanAppStatus;
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
	private final LoanService loanService;

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
			RateService rateService, DocumentLoader documentLoader, LoanService loanService) {
		this.chatClient = chatClient;
		this.vectorStore = vectorStore;
		this.router = router;
		this.sessions = sessions;
		this.eligibility = eligibility;
		this.tools = tools;
		this.rateService = rateService;
		this.documentLoader = documentLoader;
		this.loanService = loanService;
	}

	// ====== Public entrypoint ======
	public ChatReply handleMessage(String userId, String userMessageRaw, String langFromClient) {
		// 0) Extract plain text (supports {"content":"..."} or {"message":"..."})
		String raw = extractMessage(userMessageRaw);
		String lower = raw == null ? "" : raw.trim().toLowerCase();
		var state = sessions.get(userId);

		// 1) Update language from THIS message so reply matches user
		if (langFromClient != null && (langFromClient.equals("ar") || langFromClient.equals("en"))) {
			state.setLang(langFromClient);
			sessions.lockLang(userId, true); // 👈 new: mark sticky
		} else {
			// No explicit lang passed → only auto-detect if not locked
			if (!sessions.isLangLocked(userId)) {
				sessions.setLangByMessage(userId, raw); // your existing detector
			}
		}
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
		if ("nat_nonsa".equalsIgnoreCase(lower) || "non_saudi".equalsIgnoreCase(lower)
				|| "non-saudi".equalsIgnoreCase(lower) || "non saudi".equalsIgnoreCase(lower)
				|| "expat".equalsIgnoreCase(lower)) {
			raw = "nationality Non-Saudi";
		}
		// Arabic direct tokens (if user typed them literally)
		if (raw.contains("الجنسية سعودي"))
			raw = "nationality Saudi";
		if (raw.contains("الجنسية غير سعودي") || raw.contains("غير سعودي"))
			raw = "nationality Non-Saudi";

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
		if (lower.equals("lang ar") || lower.equals("lang_ar") || raw.equals("العربية")) {
			state.setLang("ar");
			return assistanceMenu("ar");
		}
		if (lower.equals("lang en") || lower.equals("lang_en") || lower.equals("english")) {
			state.setLang("en");
			return assistanceMenu("en");
		}

		String natIdCandidate = normalizeToLatinDigits(raw).replaceAll("[^0-9]", "");
		if (natIdCandidate.matches("^\\d{10}$")) {
			return handleLoanStatusFlow(state, raw);
		}
		// ---------------- Global shortcuts (no LLM) ----------------

		// Menu
		if (isMenuCommand(raw)) {
			return assistanceMenu(sessions.get(userId).lang());
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

		// Start account application
		if (lower.equals("apply_loan") || lower.equals("i want to apply loan") || raw.contains("تطبيق القرض")) {
			String applyUrl = getApplyUrl(state.getLoanType()); // see helper below
			String msg = lang.equals("ar")
					? ("قدّم طلب التمويل عبر الرابط التالي:\n" + applyUrl + "\n\n"
							+ "بعد التقديم سنخبرك بالخطوات التالية.")
					: ("Apply for your financing using this link:\n" + applyUrl + "\n\n"
							+ "We’ll guide you on the next steps after you submit.");

			// Primary action opens the link; others keep chat going
			var opts = new java.util.ArrayList<ChatOption>();
			opts.add(new ChatOption("open_apply", lang.equals("ar") ? "فتح نموذج التقديم" : "Open application",
					applyUrl));
			opts.add(
					new ChatOption("docs", lang.equals("ar") ? "الأسئلة الشائعة / المستندات" : "FAQs / Documents", ""));
			opts.add(new ChatOption("contact", lang.equals("ar") ? "التحدث إلى موظف" : "Talk to an agent", ""));
			return ChatReply.options(msg, withNav(opts, lang));
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

		if (lower.contains("status") || raw.contains("حالة") || raw.contains("متابعة الطلب")
				|| raw.contains("تتبع الطلب")) {
			return handleLoanStatusFlow(state, raw);
		}

		String maybeId = extractNationalId(raw);
		if (maybeId != null && (lower.length() == 10 || lower.matches("^\\s*\\d{10}\\s*$"))) {
			return handleLoanStatusFlow(state, raw);
		}

		if (lower.equals("track") || lower.contains("track application") || raw.contains("متابعة الطلب")
				|| raw.contains("تتبع الطلب")) {
			return handleTrackApplicationStart(state);
		}

		// ---------------- Router / LLM (only if nothing matched) ----------------
		var intent = router.detect(raw, state.lang()); // pass RAW + detected lang
		state = sessions.merge(userId, intent); // merge slots
		return routeIntent(intent, state, raw, userId);
	}

	private ChatReply handleTrackApplicationStart(EligibilityState state) {
		boolean ar = "ar".equals(state.lang());
		String prompt = ar ? "من أجل متابعة طلبك، الرجاء إدخال رقم الهوية الوطنية (10 أرقام):"
				: "To track your application, please enter your National ID (10 digits):";

		// Helpful examples as buttons (no payload to avoid leaking IDs)
		var opts = new ArrayList<ChatOption>();
		if (ar) {
			opts.add(new ChatOption("track_help", "مثال: 1234567890", ""));
			opts.add(new ChatOption("contact", "التحدث إلى موظف", ""));
		} else {
			opts.add(new ChatOption("track_help", "Example: 1234567890", ""));
			opts.add(new ChatOption("contact", "Talk to an agent", ""));
		}
		return ChatReply.options(prompt, withNav(opts, state.lang()));
	}

	private String getApplyUrl(LoanType type) {
		// TODO: put your real application URLs here
		final String DEFAULT = "https://your-fintech.com/apply";
		if (type == null)
			return DEFAULT;

		return switch (type) {
		case PERSONAL -> "https://your-fintech.com/apply/personal";
		case AUTO -> "https://your-fintech.com/apply/auto";
		case MORTGAGE, HOME -> "https://your-fintech.com/apply/home";
		};
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
		if (s.getLoanType() == null)
			return loanTypeMenu(s.lang());

		var v = eligibility.evaluateDetailed(s);
		var ar = "ar".equals(s.lang());

		java.util.function.Function<String, String> L = code -> switch (code) {
		case "NEED_INCOME" -> ar ? "ما هو دخلك الشهري؟" : "What is your monthly income?";
		case "NEED_EMPLOYER_TYPE" -> ar ? "ما نوع جهة عملك؟ (حكومي/خاص/متعاقد/عمل حر)"
				: "What is your employer type? (government/private/contract/self-employed)";
		case "NEED_SERVICE_MONTHS" ->
			ar ? "كم شهراً لدى جهة عملك الحالية؟" : "How many months with your current employer?";
		case "LOW_INCOME" -> ar ? "الدخل أقل من الحد الأدنى المطلوب." : "Income is below the minimum.";
		case "LOW_SERVICE_MONTHS" -> ar ? "مدة الخدمة أقل من المطلوب." : "Months of service are below minimum.";
		case "DTI_ABOVE_CAP" ->
			ar ? "نسبة الالتزامات إلى الدخل أعلى من الحد المسموح." : "Debt-to-income ratio exceeds the allowed cap.";
		default -> code;
		};

		// If still missing data → keep prompting
		if (v.status == EligibilityService.Verdict.Status.NEED_INFO) {
			StringBuilder b = new StringBuilder(ar ? "قبل التقييم المبدئي، أحتاج هذه التفاصيل:"
					: "Before a preliminary assessment, I need the following:");
			for (String r : v.reasons)
				b.append("\n• ").append(L.apply(r));
			return ChatReply.options(b.toString(), withNav(java.util.List.of(
			// your quick-picks here (income, employer type, service months) …
			), s.lang()));
		}

		// Build a personalized offer if feasible
		Offer offer = buildOffer(s, v);

		// Compose the main message by verdict
		StringBuilder msg = new StringBuilder();
		var opts = new java.util.ArrayList<ChatOption>();

		switch (v.status) {
		case PRELIM_INELIGIBLE -> {
			msg.append(ar ? "قد تكون غير مؤهل مبدئيًا بناءً على المعطيات الحالية:"
					: "You may be preliminarily ineligible based on current data:");
			for (String r : v.reasons)
				msg.append("\n• ").append(L.apply(r));

			if (offer != null && offer.amount > 0) {
				// Offer a *smaller* path forward (reduce amount or increase tenure)
				msg.append(ar ? "\n\nاقتراح بديل: حاول مبلغًا أقل أو مدة أطول لنسبة التزام أفضل."
						: "\n\nAlternative: try a smaller amount or longer tenure for a better DTI.");
				opts.add(new ChatOption("emi", ar ? "جرّب مدة أطول" : "Try longer tenure",
						ar ? "مدة 60 شهر" : "tenure 60 months"));
				opts.add(new ChatOption("chg_amt", ar ? "قلّل مبلغ القرض" : "Reduce loan amount", ""));
			} else {
				opts.add(new ChatOption("elig", ar ? "تحديث الدخل/البيانات" : "Update income/details", ""));
			}
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent",
					ar ? "أريد التحدث إلى موظف" : "I want to talk to a human agent"));
			return ChatReply.options(msg.toString(), withNav(opts, s.lang()));
		}

		case BORDERLINE, PREQUALIFIED -> {
			msg.append(v.status == EligibilityService.Verdict.Status.PREQUALIFIED
					? (ar ? "تقييم مبدئي إيجابي." : "Preliminary check looks good.")
					: (ar ? "الوضع على الحدود وقد يتطلب مراجعة إضافية." : "Borderline—may require additional review."));

			// Attach personalized offer (amount + tenure + EMI)
			if (offer != null && offer.amount > 0) {
				String offerLine = ar
						? ("\nعرض تقريبي: %, .0f %s على %d شهر، قسط ≈ %, .2f %s شهريًا. معدل سنوي مطبق: %.2f%%.")
						: ("\nIndicative offer: %, .0f %s over %d months, EMI ≈ %, .2f %s / month. Applied annual rate: %.2f%%.");
				msg.append(offerLine.formatted(offer.amount, offer.currency, offer.tenureMonths, offer.emi,
						offer.currency, offer.annualRate).replace(" ,", ","));
			}

			// Helpful next steps
			opts.add(new ChatOption("emi", ar ? "حساب القسط (EMI)" : "Calculate EMI",
					ar ? "حاسبة القسط" : "Calculate EMI"));
			opts.add(new ChatOption("apply_loan", ar ? "بدء التقديم" : "Apply Loan",
					ar ? "تطبيق القرض" : "I want to apply loan"));
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent",
					ar ? "أريد التحدث إلى موظف" : "I want to talk to a human agent"));

			return ChatReply.options(msg.toString(), withNav(opts, s.lang()));
		}
		}

		// fallback
		return assistanceMenu(s.lang());
	}

	// --- LOAN STATUS: entry + dispatcher ---
	private ChatReply handleLoanStatusFlow(EligibilityState state, String raw) {
		boolean ar = "ar".equals(state.lang());

		// 1) Extract + validate National ID
		String natId = extractNationalId(raw);
		if (natId == null || !natId.matches("\\d{10}")) {
			String err = ar ? "الرجاء إدخال رقم هوية وطنية صالح مكوّن من 10 أرقام (مثال: 1234567890)."
					: "Please enter a valid 10-digit National ID (e.g., 1234567890).";
			return ChatReply.options(err, withNav(
					List.of(new ChatOption("track_retry", ar ? "إعادة المحاولة" : "Try again", "")), state.lang()));
		}

		// 2) Mask for display safety
		String masked = "******" + natId.substring(6);

		// 3) Fetch status via the loan service (mock impl for now; swap for the real
		// core/DB integration later)
		LoanStatusResponse resp;
		try {
			resp = loanService.getStatusByNationalId(natId);
		} catch (Exception e) {
			String err = ar ? "عذراً، حدث خطأ أثناء جلب حالة الطلب." : "Sorry, something went wrong fetching the status.";
			return ChatReply.options(err, withNav(
					List.of(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", "")), state.lang()));
		}

		LoanStatusResponse.Status status = (resp == null || resp.getStatus() == null)
				? LoanStatusResponse.Status.UNKNOWN
				: resp.getStatus();

		// 4) Build bilingual response
		String text = null;
		var opts = new ArrayList<ChatOption>();

		switch (status) {
		case UNKNOWN -> {
			text = ar ? "لم نعثر على طلب مرتبط بالهوية " + masked + ". هل ترغب ببدء طلب جديد؟"
					: "We couldn’t find an application linked to " + masked
							+ ". Would you like to start a new application?";
			if (ar) {
				opts.add(new ChatOption("elig", "تحقق من الأهلية أولاً", ""));
				opts.add(new ChatOption("emi", "حساب القسط (EMI)", ""));
			} else {
				opts.add(new ChatOption("elig", "Check eligibility first", ""));
				opts.add(new ChatOption("emi", "Calculate EMI", ""));
			}
		}
		case SUBMITTED -> {
			text = ar ? "تم استلام طلبك ويراجَع حالياً (الهوية: " + masked + ")."
					: "Your application has been received and is under review (ID: " + masked + ").";
			if (ar) {
				opts.add(new ChatOption("docs", "ما المستندات المطلوبة؟", ""));
				opts.add(new ChatOption("contact", "التحدث إلى موظف", ""));
			} else {
				opts.add(new ChatOption("docs", "Which documents are required?", ""));
				opts.add(new ChatOption("contact", "Talk to an agent", ""));
			}
		}
		case UNDER_REVIEW -> {
			text = ar ? "طلبك قيد المراجعة حالياً (الهوية: " + masked + ")."
					: "Your application is currently under review (ID: " + masked + ").";
			if (ar) {
				opts.add(new ChatOption("docs", "ما المستندات المطلوبة؟", ""));
				opts.add(new ChatOption("contact", "التحدث إلى موظف", ""));
			} else {
				opts.add(new ChatOption("docs", "Which documents are required?", ""));
				opts.add(new ChatOption("contact", "Talk to an agent", ""));
			}
		}
		case APPROVED -> {
			text = ar
					? "تهانينا! تمت الموافقة المبدئية على طلبك (الهوية: " + masked
							+ "). الخطوة التالية: اكتمال المستندات والتوقيع."
					: "Congrats! Your application is preliminarily approved (ID: " + masked
							+ "). Next: documents & signing.";
			if (ar) {
				opts.add(new ChatOption("emi", "حساب القسط", ""));
				opts.add(new ChatOption("docs", "المستندات المطلوبة", ""));
			} else {
				opts.add(new ChatOption("emi", "Calculate EMI", ""));
				opts.add(new ChatOption("docs", "Required documents", ""));
			}
		}
		case FUNDED -> {
			text = ar
					? "تم تمويل طلبك بنجاح (الهوية: " + masked
							+ "). هل ترغب في تذكير بمواعيد السداد أو معرفة تفاصيل التسوية المبكرة؟"
					: "Your loan has been funded (ID: " + masked
							+ "). Would you like repayment reminders or early-settlement details?";
			if (ar) {
				opts.add(new ChatOption("remind", "تعيين تذكير للسداد", ""));
				opts.add(new ChatOption("faq_settle", "تفاصيل السداد المبكر", ""));
			} else {
				opts.add(new ChatOption("remind", "Set a repayment reminder", ""));
				opts.add(new ChatOption("faq_settle", "Early settlement details", ""));
			}
		}
		case REJECTED -> {
			String reason = resp == null ? null : resp.getReason();
			boolean hasReason = reason != null && !reason.isBlank();
			text = ar
					? "نأسف، تم رفض طلبك (الهوية: " + masked + ")"
							+ (hasReason ? " — " + resp.toArabic() : "") + ". هل ترغب بمعرفة طرق التحسين؟"
					: "Sorry, your application was rejected (ID: " + masked + ")"
							+ (hasReason ? " — " + resp.toEnglish() : "") + ". Want to see how to improve?";
			if (ar) {
				opts.add(new ChatOption("elig", "إعادة التحقق من الأهلية", ""));
				opts.add(new ChatOption("contact", "التحدث إلى موظف", ""));
			} else {
				opts.add(new ChatOption("elig", "Re-check eligibility", ""));
				opts.add(new ChatOption("contact", "Talk to an agent", ""));
			}
		}
		}

		return ChatReply.options(text, withNav(opts, state.lang()));
	}

	// Extract first 10 consecutive digits that looks like a Saudi National ID
	private String extractNationalId(String raw) {
		if (raw == null)
			return null;
		// accept Arabic-Indic digits too
		String latin = arabicDigitsToLatin(raw);
		var m = java.util.regex.Pattern.compile("\\b\\d{10}\\b").matcher(latin);
		return m.find() ? m.group() : null;
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
		options.add(new ChatOption("ten_up", state.lang().equals("ar") ? "جرّب مدة أطول" : "Try longer tenure", ""));
		options.add(new ChatOption("chg_amt", state.lang().equals("ar") ? "تغيير مبلغ القرض" : "Change amount", ""));

		if (state.getMonthlyIncome() == null && amount >= 5_000) {
			options.add(new ChatOption("add_inc",
					state.lang().equals("ar") ? "فحص القدرة (أضف دخلي)" : "Check affordability (add income)", ""));
		}
		options.add(new ChatOption("elig", state.lang().equals("ar") ? "التحقق من الأهلية" : "Check eligibility", ""));

		return ChatReply.options(text, withNav(options, state.lang()));
	}

	private ChatReply handleFaqRag(EligibilityState state, String raw) {
		String lang = state.lang();
		String q = sanitizeQuery(raw);

		List<Document> hits;
		try {
			hits = documentLoader.searchByLangDiversified(q, lang, 6, 2);
		} catch (Exception e) {
			hits = List.of();
		}
		String ctx = documentLoader.joinContents(hits);

		String langDirective = "ar".equals(lang)
				? "\nSTRICT_OUTPUT: Answer ONLY in Arabic, as plain text sentences. Do NOT return JSON, keys, or code fences."
				: "\nSTRICT_OUTPUT: Answer ONLY in English, as plain text sentences. Do NOT return JSON, keys, or code fences.";

		String answer = chatClient.prompt().system(SYSTEM + (ctx.isBlank() ? "" : "\nCONTEXT:\n" + ctx) + langDirective)
				.user(raw).call().content();

		answer = normalizeModelAnswer(answer, lang); // 👈 make it safe/plain

		return ChatReply.text(answer);
	}

	private String normalizeModelAnswer(String ans, String lang) {
		if (ans == null)
			return "";
		// strip code fences if any
		ans = ans.replaceAll("(?s)```+.*?```+", "").trim();

		// Try parse a top-level JSON object
		if (ans.startsWith("{") && ans.endsWith("}")) {
			try {
				var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				var node = mapper.readTree(ans);
				// common keys we’ve seen
				String[] keys = new String[] { "response", "answer", "text", "content", "message" };
				for (String k : keys) {
					if (node.has(k) && !node.get(k).isNull()) {
						return node.get(k).asText();
					}
				}
			} catch (Exception ignored) {
			}
		}
		// If it’s a JSON array of strings, join as bullets
		if (ans.startsWith("[") && ans.endsWith("]")) {
			try {
				var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				var arr = mapper.readTree(ans);
				if (arr.isArray()) {
					String bullet = "ar".equals(lang) ? "• " : "• ";
					StringBuilder sb = new StringBuilder();
					for (var n : arr) {
						if (n.isTextual()) {
							if (sb.length() > 0)
								sb.append("\n");
							sb.append(bullet).append(n.asText());
						}
					}
					if (sb.length() > 0)
						return sb.toString();
				}
			} catch (Exception ignored) {
			}
		}
		// Otherwise return as-is (already plain text)
		return ans;
	}

	// ====== Builders ======
	private ChatReply assistanceMenu(String lang) {
		if ("ar".equals(lang)) {
			return ChatReply.options("مرحباً! أنا مساعدك المالي. كيف أستطيع مساعدتك اليوم؟",
					withNav(List.of(new ChatOption("elig", "التحقق من أهلية التمويل", ""),
							new ChatOption("emi", "حاسبة القسط الشهري (EMI)", ""),
							new ChatOption("track", "متابعة الطلب", ""), // ← NEW
							new ChatOption("docs", "الأسئلة الشائعة / المستندات", ""),
							new ChatOption("lang_en", "English", "")), "ar"));
		}
		return ChatReply.options("Hey! I’m your finance assistant. How can I help you today?",
				withNav(List.of(new ChatOption("elig", "Check Loan Eligibility", ""),
						new ChatOption("emi", "Calculate EMI", ""), new ChatOption("track", "Track application", ""), // ←
																														// NEW
						new ChatOption("docs", "FAQs / Documents", ""), new ChatOption("lang_ar", "العربية", "")),
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

	/** Convert Arabic-Indic digits to Latin digits (٠١٢٣٤٥٦٧٨٩ -> 0123456789) */
	private static String normalizeToLatinDigits(String s) {
		if (s == null)
			return "";
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x0660 && c <= 0x0669) {
				out.append((char) ('0' + (c - 0x0660)));
			} else if (c >= 0x06F0 && c <= 0x06F9) {
				// Eastern Arabic-Indic digits (Persian)
				out.append((char) ('0' + (c - 0x06F0)));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static boolean isMenuCommand(String raw) {
		if (raw == null)
			return false;
		String s = raw.trim();
		String lower = s.toLowerCase();
		return lower.equals("menu") || s.contains("القائمة") || s.contains("القائمة الرئيسية") || s.contains("منيو")
				|| s.contains("الرئيسية");
	}

	private boolean isDocsCommand(String lower, String raw) {
		return lower.equals("docs") || lower.contains("document") || raw.contains("المستندات")
				|| raw.contains("الوثائق") || raw.contains("الأوراق");
	}

	private boolean isEligCommand(String lower, String raw) {
		return lower.equals("elig") || lower.equals("eligibility") || lower.equals("elig_go") || raw.contains("أهلية")
				|| raw.contains("مؤهل") || raw.contains("هل أنا مؤهل");
	}

	private boolean isEmiCommand(String lower, String raw) {
		return lower.equals("emi") || lower.startsWith("calc emi") || lower.equals("emi_go") || raw.contains("قسط")
				|| raw.contains("حاسبة") || raw.contains("حاسبة القسط");
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
		} else {
			list.add(new ChatOption("menu", "Main menu", ""));
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

	// ===== Offer helpers (inside ChatbotService) =====

	// Pick a sensible default tenure per product
	private int defaultTenureMonths(LoanType t) {
		return switch (t) {
		case PERSONAL -> 48; // 4 years
		case AUTO -> 60; // 5 years
		case MORTGAGE, HOME -> 240; // 20 years (adjust if your bank uses 25 years → 300)
		};
	}

	// Product caps (optional, tune to your bank’s policy)
	private double productMaxAmount(LoanType t) {
		return switch (t) {
		case PERSONAL -> 50_000.0;
		case AUTO -> 20_000.0;
		case MORTGAGE, HOME -> 2_000_000.0;
		};
	}

	// Invert EMI formula to compute principal P from EMI, monthly rate r, and n
	// months
	// EMI = P * r * (1+r)^n / ((1+r)^n - 1)
	// => P = EMI * ((1+r)^n - 1) / (r * (1+r)^n)
	private double principalFromEmi(double emi, double annualRatePct, int nMonths) {
		double rMonthly = (annualRatePct / 100.0) / 12.0;
		if (emi <= 0.0)
			return 0.0;
		if (rMonthly <= 0.0)
			return emi * nMonths; // zero-rate fallback
		double pow = Math.pow(1.0 + rMonthly, nMonths);
		double denom = rMonthly * pow;
		double numer = (pow - 1.0);
		return (denom == 0.0) ? 0.0 : emi * (numer / denom);
	}

	/**
	 * Build a personalized offer (amount + tenure + EMI) if we have enough data.
	 */
	private Offer buildOffer(EligibilityState s, EligibilityService.Verdict v) {
		// We need income + obligations + loan type
		if (s.getLoanType() == null || s.getMonthlyIncome() == null)
			return null;

		double income = Math.max(0, s.getMonthlyIncome());
		double other = (s.getOtherObligationsMonthly() == null) ? 0.0 : Math.max(0, s.getOtherObligationsMonthly());

		// If your verdict filled suggestedDtiCap, use it; otherwise pick a safe cap
		// ~40%
		double dtiCap = (v != null && v.suggestedDtiCap != null) ? v.suggestedDtiCap : 0.40;
		dtiCap = Math.max(0.25, Math.min(0.50, dtiCap));

		// Max *new* EMI budget (income * cap minus existing obligations)
		double maxNewEmi = Math.max(0.0, dtiCap * income - other);

		// Choose tenure and APR
		int n = (s.getTenureMonths() != null) ? s.getTenureMonths() : defaultTenureMonths(s.getLoanType());
		double apr = rateService.getRate(s.getLoanType(),
				// we don't know amount yet, pass a rough guess
				Math.max(50_000, s.getAmount() == null ? 50_000 : s.getAmount()), n, s.getEmployerType());
		// Safety fallback if rate service returns 0/NaN
		if (Double.isNaN(apr) || apr <= 0.0)
			apr = fallbackRate(s.getLoanType());

		// Compute principal offer from EMI budget
		double rawAmount = principalFromEmi(maxNewEmi, apr, n);

		// Clamp to product caps
		double cap = productMaxAmount(s.getLoanType());
		double offerAmount = Math.min(rawAmount, cap);

		// Be a bit conservative (optionally haircut by ~5%)
		offerAmount = Math.max(0.0, Math.floor(offerAmount / 1000.0) * 1000.0 * 0.95);

		// Recompute EMI from final amount (so EMI aligns with rounded amount)
		double emi = tools.emi(offerAmount, apr, n);

		Offer out = new Offer();
		out.amount = round2(offerAmount);
		out.tenureMonths = n;
		out.annualRate = apr;
		out.emi = round2(emi);
		out.currency = (s.getCurrency() != null) ? s.getCurrency() : ("ar".equals(s.lang()) ? "ريال" : "SAR");
		return out;
	}

	private static class Offer {
		double amount;
		int tenureMonths;
		double annualRate;
		double emi;
		String currency;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
