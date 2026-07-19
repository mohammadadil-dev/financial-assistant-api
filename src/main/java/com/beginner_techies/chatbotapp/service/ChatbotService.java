package com.beginner_techies.chatbotapp.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
	private final ChatMemory chatMemory;

	private final LeadStore leadStore;
	private final SystemPromptProvider systemPrompt;
	private final GuardrailService guardrails;
	private final ChatbotTools chatbotTools;
	private final ProductPolicy policy;
	private final boolean toolsEnabled;

	public ChatbotService(ChatClient chatClient, VectorStore vectorStore, IntentDetectorService router,
			EligibilitySessionStore sessions, EligibilityService eligibility, FinanceTools tools,
			RateService rateService, DocumentLoader documentLoader, LoanService loanService, ChatMemory chatMemory,
			LeadStore leadStore, SystemPromptProvider systemPrompt, GuardrailService guardrails,
			ChatbotTools chatbotTools, ProductPolicy policy,
			@org.springframework.beans.factory.annotation.Value("${app.ai.tools.enabled:false}") boolean toolsEnabled) {
		this.leadStore = leadStore;
		this.systemPrompt = systemPrompt;
		this.guardrails = guardrails;
		this.chatbotTools = chatbotTools;
		this.policy = policy;
		this.toolsEnabled = toolsEnabled;
		this.chatClient = chatClient;
		this.vectorStore = vectorStore;
		this.router = router;
		this.sessions = sessions;
		this.eligibility = eligibility;
		this.tools = tools;
		this.rateService = rateService;
		this.documentLoader = documentLoader;
		this.loanService = loanService;
		this.chatMemory = chatMemory;
	}

	/**
	 * Receives token deltas as the LLM generates them. Only LLM-backed replies
	 * (FAQ/RAG) stream; rule-engine replies return at once without deltas.
	 */
	@FunctionalInterface
	public interface TokenListener {
		void onToken(String delta);
	}

	// ====== Public entrypoint ======
	public ChatReply handleMessage(String userId, String userMessageRaw, String langFromClient) {
		return handleMessage(userId, userMessageRaw, langFromClient, null);
	}

	// Streaming-aware entrypoint; listener may be null for the blocking path.
	public ChatReply handleMessage(String userId, String userMessageRaw, String langFromClient,
			TokenListener listener) {
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

		// --- Guided flows in progress (callback / affordability / settlement).
		// Must run BEFORE National-ID sniffing: a Saudi mobile is also 10 digits.
		if (state.getPendingFlow() != null) {
			ChatReply flowReply = handlePendingFlow(state, raw, userId);
			if (flowReply != null)
				return flowReply;
			// null → user escaped the flow (menu/reset); continue normal routing
		}

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
			if (latin.matches("^\\d{4,7}$")) { // plausible salary range only —
				try { // a bare "60" (tenure?) must not become a 60 SAR income
					double val = Double.parseDouble(latin);
					if (val >= 1000 && val <= 1_000_000) {
						state.setMonthlyIncome(val);
						slotTouched = true;
					}
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

		// Affordability — "how much can I borrow?"
		if (isAffordCommand(lower, raw)) {
			return startAffordability(state);
		}

		// Payment schedule (amortization summary)
		if (isScheduleCommand(lower, raw)) {
			return handlePaymentSchedule(state);
		}

		// Early settlement quote
		if (isSettleCommand(lower, raw)) {
			return startSettlement(state);
		}

		// Talk to an agent → callback lead capture
		if (isContactCommand(lower, raw)) {
			return startCallbackFlow(state);
		}

		// Repayment reminders (needs a linked account — offer callback instead)
		if (lower.equals("remind") || raw.contains("تذكير بالسداد") || containsPhrase(lower, "repayment reminder")) {
			return remindReply(state);
		}

		// Track application retry/help buttons
		if (lower.equals("track_retry") || lower.equals("track_help")) {
			return handleTrackApplicationStart(state);
		}

		// Documents — personalized rule-based checklist
		if (isDocsCommand(lower, raw)) {
			return docsChecklist(state);
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
		return routeIntent(intent, state, raw, userId, listener);
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
	private ChatReply routeIntent(IntentResult intent, EligibilityState state, String userMessage, String userId,
			TokenListener listener) {
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
			yield handleFaqRag(state, userMessage, userId, listener); // RAG
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

		// Enforce the SAMA tenure limit (60 months for consumer finance)
		if (state.getTenureMonths() != null) {
			int maxTen = productMaxTenureMonths(state.getLoanType());
			if (state.getTenureMonths() > maxTen)
				state.setTenureMonths(maxTen);
		}

		// Enforce the company's nationality-aware amount cap
		boolean amountCapped = false;
		if (state.getAmount() != null) {
			double capAmt = policy.maxAmount(state.getLoanType(), state.getNationality());
			if (state.getAmount() > capAmt) {
				state.setAmount(capAmt);
				amountCapped = true;
			}
		}

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

		if (amountCapped) {
			text += state.lang().equals("ar") ? "\n(تم ضبط المبلغ على الحد الأقصى للمنتج وفق سياسة الشركة)"
					: "\n(Amount adjusted to the product maximum under company policy)";
		}

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

	private ChatReply handleFaqRag(EligibilityState state, String raw, String userId, TokenListener listener) {
		String lang = state.lang();
		String q = sanitizeQuery(raw);

		// Session-aware retrieval: when we know the product, prefer its chunks
		String product = state.getLoanType() == null ? null : switch (state.getLoanType()) {
		case PERSONAL -> "personal";
		case MORTGAGE, HOME -> "home";
		case AUTO -> "auto";
		};
		List<Document> hits;
		try {
			hits = documentLoader.search(q, lang, product, 6);
		} catch (Exception e) {
			hits = List.of();
		}
		String ctx = documentLoader.joinContents(hits);

		// Honest no-answer fallback: without matching knowledge the model must
		// not improvise company-specific facts (rates, fees, policy).
		String noKbDirective = ctx.isBlank()
				? "\nNOTE: No internal knowledge matched this question. If it concerns company-specific rates, fees, or policy, say you don't have that information and offer an agent callback. General finance education is fine."
				: "";

		String langDirective = "ar".equals(lang)
				? "\nSTRICT_OUTPUT: Answer ONLY in Arabic, as plain text sentences. Do NOT return JSON, keys, or code fences."
				: "\nSTRICT_OUTPUT: Answer ONLY in English, as plain text sentences. Do NOT return JSON, keys, or code fences.";

		// Guardrail: prompt-injection attempts never reach the LLM
		if (guardrails.isInjectionAttempt(raw)) {
			return ChatReply.text("ar".equals(lang) ? "لا أستطيع المساعدة في ذلك. كيف أقدر أخدمك في تمويلك؟"
					: "I can't help with that. How can I help you with your financing?");
		}

		var promptSpec = chatClient.prompt()
				.system(systemPrompt.render(lang) + (ctx.isBlank() ? "" : "\nCONTEXT:\n" + ctx) + noKbDirective
						+ langDirective)
				.user(raw)
				.advisors(a -> a.advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
						.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, userId));

		// Function-calling: real EMI/affordability/status/settlement math for
		// free-text questions (requires a tool-capable model, e.g. Groq llama-3.3)
		if (toolsEnabled) {
			promptSpec = promptSpec.tools(chatbotTools);
		}

		String answer;
		if (listener == null) {
			answer = promptSpec.call().content();
		} else {
			// Stream tokens to the listener while accumulating the full answer;
			// blockLast() is fine here — we're on the SSE worker thread.
			StringBuilder acc = new StringBuilder();
			promptSpec.stream().content().doOnNext(delta -> {
				acc.append(delta);
				listener.onToken(delta);
			}).blockLast();
			answer = acc.toString();
		}

		// Normalize (strip JSON/fences) then PII-mask as the final output filter
		answer = guardrails.maskPii(normalizeModelAnswer(answer, lang));

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
	// ====== New feature command matchers ======

	private boolean isAffordCommand(String lower, String raw) {
		return lower.equals("afford") || containsPhrase(lower, "how much can i borrow")
				|| containsPhrase(lower, "borrowing capacity") || raw.contains("كم أقدر أقترض")
				|| raw.contains("كم يمكنني الاقتراض") || raw.contains("كم استطيع اقترض");
	}

	private boolean isScheduleCommand(String lower, String raw) {
		return lower.equals("schedule") || containsPhrase(lower, "payment schedule")
				|| containsPhrase(lower, "amortization") || raw.contains("جدول السداد")
				|| raw.contains("جدول الدفعات");
	}

	private boolean isSettleCommand(String lower, String raw) {
		return lower.equals("faq_settle") || lower.equals("settle") || containsPhrase(lower, "early settlement")
				|| containsPhrase(lower, "settle early") || raw.contains("السداد المبكر")
				|| raw.contains("التسوية المبكرة");
	}

	private boolean isContactCommand(String lower, String raw) {
		return lower.equals("contact") || containsPhrase(lower, "talk to an agent")
				|| containsPhrase(lower, "human agent") || containsPhrase(lower, "call me back")
				|| raw.contains("التحدث إلى موظف") || raw.contains("اتصلوا بي");
	}

	// ====== Guided flows (callback / affordability / settlement) ======

	/** Handles one turn of an in-progress guided flow. Returns null when the
	 *  user escaped (menu/reset) so normal routing continues. */
	private ChatReply handlePendingFlow(EligibilityState state, String raw, String userId) {
		boolean ar = state.isArabic();
		String text = raw == null ? "" : raw.trim();
		String lower = text.toLowerCase();

		// Escape hatch: ANY global command cancels the flow and falls through to
		// normal routing. Users click old buttons mid-flow all the time — without
		// this, "Check eligibility" gets captured as someone's NAME and the flow
		// traps them.
		if (isMenuCommand(text) || lower.equals("reset") || lower.equals("start over")
				|| text.contains("بدء من جديد") || isEligCommand(lower, text) || isEmiCommand(lower, text)
				|| isDocsCommand(lower, text) || isAffordCommand(lower, text) || isScheduleCommand(lower, text)
				|| isSettleCommand(lower, text) || isContactCommand(lower, text) || lower.equals("track")
				|| lower.equals("track_retry") || lower.equals("track_help") || lower.equals("apply_loan")) {
			state.setPendingFlow(null);
			return null;
		}

		switch (state.getPendingFlow()) {

		case "CB_NAME" -> {
			// A real name: letters and spaces — button ids ("elig") and numbers
			// must never be captured as a person's name
			if (!text.matches("^[\\p{L}][\\p{L} .'’-]{1,59}$") || text.contains("_")) {
				return flowPrompt(state, ar ? "فضلاً أدخل اسمك الكامل (حروف فقط)."
						: "Please enter your full name (letters only).");
			}
			state.setCallbackName(text);
			state.setPendingFlow("CB_MOBILE");
			return flowPrompt(state, ar ? "شكراً " + text + "! ما رقم جوالك؟ (مثال: 05XXXXXXXX)"
					: "Thanks " + text + "! What's your mobile number? (e.g., 05XXXXXXXX)");
		}

		case "CB_MOBILE" -> {
			String digits = normalizeToLatinDigits(text).replaceAll("[^0-9+]", "");
			String normalized = normalizeSaudiMobile(digits);
			if (normalized == null) {
				return flowPrompt(state, ar ? "الرجاء إدخال رقم جوال سعودي صالح (05XXXXXXXX أو ‎+9665XXXXXXXX)."
						: "Please enter a valid Saudi mobile number (05XXXXXXXX or +9665XXXXXXXX).");
			}
			state.setCallbackMobile(normalized);
			state.setPendingFlow("CB_TIME");
			var opts = new ArrayList<ChatOption>();
			opts.add(new ChatOption("cb_am", ar ? "صباحاً (9–12)" : "Morning (9–12)", ar ? "صباحاً" : "morning"));
			opts.add(new ChatOption("cb_pm", ar ? "ظهراً (12–4)" : "Afternoon (12–4)", ar ? "ظهراً" : "afternoon"));
			opts.add(new ChatOption("cb_eve", ar ? "مساءً (4–8)" : "Evening (4–8)", ar ? "مساءً" : "evening"));
			return ChatReply.options(ar ? "متى تفضل أن نتصل بك؟" : "When would you like us to call?", opts);
		}

		case "CB_TIME" -> {
			String slot = switch (lower) {
			case "cb_am", "morning", "صباحاً" -> ar ? "صباحاً (9–12)" : "morning (9–12)";
			case "cb_pm", "afternoon", "ظهراً" -> ar ? "ظهراً (12–4)" : "afternoon (12–4)";
			case "cb_eve", "evening", "مساءً" -> ar ? "مساءً (4–8)" : "evening (4–8)";
			default -> text;
			};
			var lead = leadStore.save(userId, state.getCallbackName(), state.getCallbackMobile(), slot);
			state.setPendingFlow(null);
			state.setCallbackName(null);
			state.setCallbackMobile(null);
			String msg = ar
					? "تم تسجيل طلبك ✅\nرقم المرجع: **" + lead.ref() + "**\nسيتصل بك أحد مستشارينا " + slot + "."
					: "You're all set ✅\nReference: **" + lead.ref() + "**\nOne of our advisors will call you " + slot
							+ ".";
			return ChatReply.options(msg, withNav(new ArrayList<>(), state.lang()));
		}

		case "DOCS_REFINE" -> {
			// The docs checklist asked a refinement question — apply the answer
			// and re-render the (more precise) checklist. Anything else falls
			// through to normal routing so buttons like "Check eligibility" work.
			boolean touched = true;
			switch (lower) {
			case "loan_personal" -> state.setLoanType(LoanType.PERSONAL);
			case "loan_mortgage" -> state.setLoanType(LoanType.MORTGAGE);
			case "loan_auto" -> state.setLoanType(LoanType.AUTO);
			case "nat_sa" -> state.setNationality("Saudi");
			case "nat_nonsa" -> state.setNationality("Non-Saudi");
			case "emp_gov" -> state.setEmployerType("government");
			case "emp_priv" -> state.setEmployerType("private");
			case "emp_cont" -> state.setEmployerType("contract");
			case "emp_self" -> state.setEmployerType("self-employed");
			default -> touched = tryDirectSlotUpdate(state, text);
			}
			if (touched)
				return docsChecklist(state);
			state.setPendingFlow(null);
			return null;
		}

		case "AFFORD_TYPE" -> {
			LoanType chosen = switch (lower) {
			case "loan_personal" -> LoanType.PERSONAL;
			case "loan_mortgage" -> LoanType.MORTGAGE;
			case "loan_auto" -> LoanType.AUTO;
			default -> LoanType.fromText(text);
			};
			if (chosen == null) {
				return flowPrompt(state, ar ? "فضلاً اختر نوع التمويل: شخصي، عقاري، أو سيارة."
						: "Please choose a financing type: personal, home, or car.");
			}
			state.setLoanType(chosen);
			if (state.getNationality() == null)
				return askAffordNationality(state);
			if (state.getMonthlyIncome() == null)
				return askAffordIncome(state);
			if (state.getOtherObligationsMonthly() == null)
				return askObligations(state);
			state.setPendingFlow(null);
			return computeAffordability(state);
		}

		case "AFFORD_NAT" -> {
			// Check the negative forms FIRST: "non-saudi" also contains "saudi"
			boolean isNonSaudi = lower.equals("nat_nonsa") || lower.contains("non") || lower.contains("expat")
					|| text.contains("غير") || text.contains("مقيم");
			boolean isSaudi = !isNonSaudi
					&& (lower.equals("nat_sa") || lower.contains("saudi") || text.contains("سعود"));
			if (!isNonSaudi && !isSaudi) {
				return flowPrompt(state, ar ? "فضلاً اختر: سعودي أو غير سعودي (مقيم)."
						: "Please choose: Saudi or Expat (Non-Saudi).");
			}
			state.setNationality(isNonSaudi ? "Non-Saudi" : "Saudi");
			if (state.getMonthlyIncome() == null)
				return askAffordIncome(state);
			if (state.getOtherObligationsMonthly() == null)
				return askObligations(state);
			state.setPendingFlow(null);
			return computeAffordability(state);
		}

		case "AFFORD_INCOME" -> {
			Double income = parseFlowNumber(text);
			if (income == null || income < 1000 || income > 1_000_000) {
				return flowPrompt(state, ar ? "فضلاً أدخل دخلك الشهري بالريال (مثال: 12000)."
						: "Please enter your monthly income in SAR (e.g., 12000).");
			}
			state.setMonthlyIncome(income);
			return askObligations(state);
		}

		case "AFFORD_OBLIG" -> {
			Double obligations = (lower.equals("none") || text.contains("لا يوجد")) ? Double.valueOf(0.0)
					: parseFlowNumber(text);
			if (obligations == null || obligations < 0) {
				return flowPrompt(state, ar ? "فضلاً أدخل مبلغ الالتزامات الشهرية (0 إذا لا يوجد)."
						: "Please enter your monthly obligations amount (0 if none).");
			}
			state.setOtherObligationsMonthly(obligations);
			state.setPendingFlow(null);
			return computeAffordability(state);
		}

		case "SETTLE_MONTHS" -> {
			Double k = parseFlowNumber(text);
			Integer tenure = state.getTenureMonths();
			if (k == null || tenure == null || k < 1 || k >= tenure) {
				return flowPrompt(state, ar
						? "فضلاً أدخل عدد الأقساط المسددة (رقم بين 1 و " + (tenure == null ? 359 : tenure - 1) + ")."
						: "Please enter how many installments you've paid (between 1 and "
								+ (tenure == null ? 359 : tenure - 1) + ").");
			}
			state.setPendingFlow(null);
			return computeSettlement(state, k.intValue());
		}

		default -> {
			state.setPendingFlow(null);
			return null;
		}
		}
	}

	/** A flow question with the standard nav chip, so users always see a way out. */
	private ChatReply flowPrompt(EligibilityState state, String prompt) {
		return ChatReply.options(prompt, withNav(new ArrayList<>(), state.lang()));
	}

	private Double parseFlowNumber(String raw) {
		if (raw == null)
			return null;
		String latin = normalizeToLatinDigits(raw).replaceAll("[,\\s]", "");
		var m = java.util.regex.Pattern.compile("(\\d{1,7}(?:\\.\\d+)?)").matcher(latin);
		if (!m.find())
			return null;
		try {
			return Double.parseDouble(m.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Accepts 05XXXXXXXX, 5XXXXXXXX, 9665XXXXXXXX, +9665XXXXXXXX → 9665XXXXXXXX */
	private String normalizeSaudiMobile(String digits) {
		if (digits == null)
			return null;
		String d = digits.replace("+", "");
		if (d.matches("^05\\d{8}$"))
			return "966" + d.substring(1);
		if (d.matches("^9665\\d{8}$"))
			return d;
		if (d.matches("^5\\d{8}$"))
			return "966" + d;
		return null;
	}

	private ChatReply startCallbackFlow(EligibilityState state) {
		boolean ar = state.isArabic();
		state.setPendingFlow("CB_NAME");
		return flowPrompt(state, ar ? "يسعدنا التواصل معك! 📞 ما اسمك الكريم؟"
				: "Happy to have an advisor call you! 📞 What's your name?");
	}

	private ChatReply remindReply(EligibilityState state) {
		boolean ar = state.isArabic();
		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("contact", ar ? "اتصلوا بي لتفعيلها" : "Call me to set it up", ""));
		return ChatReply.options(ar
				? "تذكيرات السداد تُرسل برسائل نصية قبل موعد القسط بثلاثة أيام، وتتطلب ربط حسابك. اطلب مكالمة من مستشار لتفعيلها."
				: "Repayment reminders are sent by SMS 3 days before each installment and require a linked account. Request a callback and an advisor will set it up.",
				withNav(opts, state.lang()));
	}

	// ====== Affordability ("how much can I borrow?") ======

	/** SAMA responsible-lending cap on the debt burden ratio for salaried customers. */
	private static final double DBR_CAP = 1.0 / 3.0;

	private ChatReply startAffordability(EligibilityState state) {
		boolean ar = state.isArabic();
		// Need before capacity: ask WHAT financing they want first, then compute
		// what they can get for that product (caps and tenure differ per product).
		if (state.getLoanType() == null) {
			state.setPendingFlow("AFFORD_TYPE");
			var opts = new ArrayList<ChatOption>();
			opts.add(new ChatOption("loan_personal", ar ? "تمويل شخصي" : "Personal Financing", ""));
			opts.add(new ChatOption("loan_mortgage", ar ? "تمويل عقاري / رهن" : "Mortgage / Home Loan", ""));
			opts.add(new ChatOption("loan_auto", ar ? "تمويل سيارة" : "Car Loan", ""));
			return ChatReply.options(ar ? "لنعرف قدرتك التمويلية 💰 — أي نوع تمويل تحتاج؟"
					: "Let's find out how much you can borrow 💰 — which type of financing do you need?", opts);
		}
		if (state.getNationality() == null) {
			return askAffordNationality(state);
		}
		if (state.getMonthlyIncome() == null) {
			return askAffordIncome(state);
		}
		if (state.getOtherObligationsMonthly() == null) {
			return askObligations(state);
		}
		return computeAffordability(state);
	}

	private ChatReply askAffordNationality(EligibilityState state) {
		boolean ar = state.isArabic();
		state.setPendingFlow("AFFORD_NAT");
		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("nat_sa", ar ? "سعودي" : "Saudi", ""));
		opts.add(new ChatOption("nat_nonsa", ar ? "غير سعودي (مقيم)" : "Expat (Non-Saudi)", ""));
		return ChatReply.options(ar ? "هل أنت سعودي أم مقيم؟ (يختلف الحد الأقصى للتمويل حسب الجنسية)"
				: "Are you Saudi or an expat? (The maximum financing amount differs by nationality.)", opts);
	}

	private ChatReply askAffordIncome(EligibilityState state) {
		boolean ar = state.isArabic();
		state.setPendingFlow("AFFORD_INCOME");
		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("aff10k", ar ? "10,000 ريال" : "10,000 SAR", "10000"));
		opts.add(new ChatOption("aff15k", ar ? "15,000 ريال" : "15,000 SAR", "15000"));
		opts.add(new ChatOption("aff20k", ar ? "20,000 ريال" : "20,000 SAR", "20000"));
		return ChatReply.options(ar ? "كم دخلك الشهري؟ اكتب المبلغ أو اختر:"
				: "What's your monthly income? Type it or pick:", opts);
	}

	private ChatReply askObligations(EligibilityState state) {
		boolean ar = state.isArabic();
		state.setPendingFlow("AFFORD_OBLIG");
		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("obl0", ar ? "لا يوجد" : "None", "0"));
		opts.add(new ChatOption("obl1k", ar ? "1,000 ريال" : "1,000 SAR", "1000"));
		opts.add(new ChatOption("obl2k", ar ? "2,000 ريال" : "2,000 SAR", "2000"));
		return ChatReply.options(ar ? "كم إجمالي التزاماتك الشهرية الحالية (أقساط، بطاقات ائتمان)؟ اكتب المبلغ أو اختر:"
				: "What are your existing monthly obligations (loans, credit cards)? Type an amount or pick:", opts);
	}

	private ChatReply computeAffordability(EligibilityState state) {
		boolean ar = state.isArabic();
		LoanType lt = state.getLoanType() == null ? LoanType.PERSONAL : state.getLoanType();
		int tenure = (lt == LoanType.MORTGAGE || lt == LoanType.HOME) ? 300 : 60;

		double income = state.getMonthlyIncome();
		double obligations = state.getOtherObligationsMonthly() == null ? 0.0 : state.getOtherObligationsMonthly();
		double maxEmi = income * DBR_CAP - obligations;

		if (maxEmi < 100) {
			var opts = new ArrayList<ChatOption>();
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", ""));
			return ChatReply.options(ar
					? "بناءً على التزاماتك الحالية، هامش السداد المتاح لديك محدود جداً حالياً. يسعدنا مناقشة الخيارات معك."
					: "Based on your current obligations there's very little room for a new installment right now. We'd be happy to discuss options with you.",
					withNav(opts, state.lang()));
		}

		double rate = rateService.getRate(lt, 100_000, tenure, state.getEmployerType());
		if (Double.isNaN(rate) || rate <= 0)
			rate = fallbackRate(lt);
		double r = rate / 1200.0;
		double k = Math.pow(1 + r, tenure);
		double maxLoan = Math.floor((maxEmi * (k - 1) / (r * k)) / 1000) * 1000;

		// Cap by company product policy (nationality-aware)
		double productCap = policy.maxAmount(lt, state.getNationality());
		boolean cappedByPolicy = maxLoan > productCap;
		if (cappedByPolicy) {
			maxLoan = productCap;
			// EMI shown should match the capped amount, not the DBR budget
			maxEmi = tools.emi(maxLoan, rate, tenure);
		}

		String cur = ar ? "ريال" : "SAR";
		String typeName = switch (lt) {
		case PERSONAL -> ar ? "تمويل شخصي" : "personal financing";
		case MORTGAGE, HOME -> ar ? "تمويل عقاري" : "home financing";
		case AUTO -> ar ? "تمويل سيارة" : "auto financing";
		};

		// Save so "Calculate EMI" continues seamlessly with these numbers
		state.setAmount(maxLoan);
		state.setTenureMonths(tenure);

		String msg = ar
				? ("بناءً على دخل شهري %s %s والتزامات %s %s:\n\nيمكنك اقتراض حتى **%s %s** تقريباً (%s على %d شهر بمعدل %.2f%%)\nبقسط شهري أقصاه **%s %s** — وفق حد نسبة الالتزامات (33%%) من ساما.\n\n_تقدير أولي وليس عرضاً ملزماً._")
						.formatted(fmtNum(income), cur, fmtNum(obligations), cur, fmtNum(maxLoan), cur, typeName,
								tenure, rate, fmtNum(maxEmi), cur)
				: ("Based on a monthly income of %s %s and obligations of %s %s:\n\nYou could borrow up to **%s %s** (%s over %d months at %.2f%%)\nwith a maximum installment of **%s %s** — per SAMA's 33%% debt-burden cap.\n\n_Indicative estimate, not a binding offer._")
						.formatted(fmtNum(income), cur, fmtNum(obligations), cur, fmtNum(maxLoan), cur, typeName,
								tenure, rate, fmtNum(maxEmi), cur);

		if (cappedByPolicy) {
			boolean saudi = ProductPolicy.isSaudi(state.getNationality());
			msg += ar
					? "\n\n(هذا هو الحد الأقصى لهذا المنتج " + (saudi ? "للسعوديين" : "للمقيمين")
							+ " وفق سياسة الشركة)"
					: "\n\n(This is the product maximum for " + (saudi ? "Saudi customers" : "expats")
							+ " under company policy)";
		}

		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("emi", ar ? "احسب القسط لهذا المبلغ" : "Calculate EMI for this amount", ""));
		opts.add(new ChatOption("elig", ar ? "التحقق من الأهلية" : "Check eligibility", ""));
		opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", ""));
		return ChatReply.options(msg, withNav(opts, state.lang()));
	}

	private String fmtNum(double n) {
		return String.format("%,.0f", n);
	}

	// ====== Payment schedule & early settlement ======

	private ChatReply handlePaymentSchedule(EligibilityState state) {
		boolean ar = state.isArabic();
		if (state.getAmount() == null || state.getTenureMonths() == null) {
			var opts = new ArrayList<ChatOption>();
			opts.add(new ChatOption("emi", ar ? "حساب القسط أولاً" : "Calculate EMI first", ""));
			return ChatReply.options(ar ? "أحتاج مبلغ التمويل والمدة أولاً — احسب القسط وسأجهز لك الجدول."
					: "I need your loan amount and tenure first — run an EMI calculation and I'll build the schedule.",
					withNav(opts, state.lang()));
		}
		double P = state.getAmount();
		int n = state.getTenureMonths();
		LoanType lt = state.getLoanType() == null ? LoanType.PERSONAL : state.getLoanType();
		double rate = rateService.getRate(lt, P, n, state.getEmployerType());
		if (Double.isNaN(rate) || rate <= 0)
			rate = fallbackRate(lt);
		double r = rate / 1200.0;
		double emi = tools.emi(P, rate, n);
		String cur = ar ? "ريال" : "SAR";

		StringBuilder sb = new StringBuilder();
		sb.append(ar
				? "**جدول السداد** لمبلغ %s %s على %d شهر بمعدل %.2f%% — القسط **%s %s** شهرياً:\n\n".formatted(
						fmtNum(P), cur, n, rate, fmtNum(emi), cur)
				: "**Payment schedule** for %s %s over %d months at %.2f%% — EMI **%s %s**/month:\n\n".formatted(
						fmtNum(P), cur, n, rate, fmtNum(emi), cur));

		double balance = P;
		int year = 1;
		double yPrincipal = 0, yProfit = 0;
		for (int m = 1; m <= n; m++) {
			double profit = balance * r;
			double principal = emi - profit;
			balance = Math.max(0, balance - principal);
			yPrincipal += principal;
			yProfit += profit;
			if (m % 12 == 0 || m == n) {
				sb.append(ar
						? "- السنة %d: أصل %s + أرباح %s ← المتبقي **%s %s**\n".formatted(year, fmtNum(yPrincipal),
								fmtNum(yProfit), fmtNum(balance), cur)
						: "- Year %d: principal %s + profit %s → balance **%s %s**\n".formatted(year,
								fmtNum(yPrincipal), fmtNum(yProfit), fmtNum(balance), cur));
				year++;
				yPrincipal = 0;
				yProfit = 0;
			}
		}
		double total = emi * n;
		sb.append(ar ? "\nالإجمالي: **%s %s** (أرباح %s %s)".formatted(fmtNum(total), cur, fmtNum(total - P), cur)
				: "\nTotal payable: **%s %s** (profit %s %s)".formatted(fmtNum(total), cur, fmtNum(total - P), cur));

		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("faq_settle", ar ? "تسعيرة السداد المبكر" : "Early settlement quote", ""));
		return ChatReply.options(sb.toString(), withNav(opts, state.lang()));
	}

	private ChatReply startSettlement(EligibilityState state) {
		boolean ar = state.isArabic();
		if (state.getAmount() == null || state.getTenureMonths() == null) {
			var opts = new ArrayList<ChatOption>();
			opts.add(new ChatOption("emi", ar ? "حساب القسط أولاً" : "Calculate EMI first", ""));
			opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", ""));
			return ChatReply.options(ar
					? "لحساب تسعيرة السداد المبكر أحتاج تفاصيل التمويل (المبلغ والمدة). احسب القسط أولاً أو تحدث مع موظف."
					: "To quote early settlement I need your loan details (amount & tenure). Run an EMI calc first, or talk to an agent.",
					withNav(opts, state.lang()));
		}
		state.setPendingFlow("SETTLE_MONTHS");
		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("st6", ar ? "6 أقساط" : "6 paid", "6"));
		opts.add(new ChatOption("st12", ar ? "12 قسطاً" : "12 paid", "12"));
		opts.add(new ChatOption("st24", ar ? "24 قسطاً" : "24 paid", "24"));
		return ChatReply.options(ar ? "كم قسطاً سددت حتى الآن؟ اكتب الرقم أو اختر:"
				: "How many installments have you paid so far? Type a number or pick:", opts);
	}

	private ChatReply computeSettlement(EligibilityState state, int paid) {
		boolean ar = state.isArabic();
		double P = state.getAmount();
		int n = state.getTenureMonths();
		LoanType lt = state.getLoanType() == null ? LoanType.PERSONAL : state.getLoanType();
		double rate = rateService.getRate(lt, P, n, state.getEmployerType());
		if (Double.isNaN(rate) || rate <= 0)
			rate = fallbackRate(lt);
		double r = rate / 1200.0;
		double emi = tools.emi(P, rate, n);

		double pk = Math.pow(1 + r, paid);
		double balance = P * pk - emi * (pk - 1) / r;
		if (balance <= 0) {
			return ChatReply.text(
					ar ? "وفق هذه الأرقام، تمويلك مسدد بالكامل تقريباً 🎉" : "By these numbers your loan is already fully repaid 🎉");
		}

		int remaining = n - paid;
		double fee = 0;
		double b = balance;
		for (int i = 0; i < Math.min(3, remaining); i++) {
			double profit = b * r;
			fee += profit;
			b -= (emi - profit);
		}
		double total = balance + fee;
		String cur = ar ? "ريال" : "SAR";

		String msg = ar
				? ("**تسعيرة السداد المبكر** (بعد سداد %d من %d قسطاً):\n\n- الرصيد المتبقي: **%s %s**\n- رسوم التسوية (بحد أقصى أرباح 3 أشهر وفق ساما): **%s %s**\n- الإجمالي للسداد اليوم: **%s %s**\n\n_تقدير إرشادي؛ التسعيرة النهائية من كشف حسابك._")
						.formatted(paid, n, fmtNum(balance), cur, fmtNum(fee), cur, fmtNum(total), cur)
				: ("**Early settlement quote** (after %d of %d installments):\n\n- Outstanding balance: **%s %s**\n- Settlement fee (capped at 3 months' profit per SAMA): **%s %s**\n- Total to settle today: **%s %s**\n\n_Indicative; the final figure comes from your account statement._")
						.formatted(paid, n, fmtNum(balance), cur, fmtNum(fee), cur, fmtNum(total), cur);

		var opts = new ArrayList<ChatOption>();
		opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", ""));
		return ChatReply.options(msg, withNav(opts, state.lang()));
	}

	// ====== Personalized document checklist ======

	private ChatReply docsChecklist(EligibilityState state) {
		boolean ar = state.isArabic();
		LoanType lt = state.getLoanType();
		String nat = state.getNationality();
		String emp = state.getEmployerTypeNormalized();
		boolean saudi = nat != null && nat.equalsIgnoreCase("Saudi");
		boolean home = lt == LoanType.MORTGAGE || lt == LoanType.HOME;

		var lines = new ArrayList<String>();
		if (ar) {
			lines.add(nat == null ? "بطاقة الهوية الوطنية أو الإقامة سارية المفعول"
					: (saudi ? "بطاقة الهوية الوطنية سارية المفعول" : "إقامة سارية المفعول + جواز السفر"));
			lines.add("تعريف بالراتب حديث (لا يتجاوز 30 يوماً)"
					+ ("government".equals(emp) ? " مصدق من جهة العمل الحكومية" : ""));
			if ("self-employed".equals(emp))
				lines.add("السجل التجاري + كشف حساب بنكي 6–12 شهراً");
			else
				lines.add("كشف حساب بنكي لآخر 3 أشهر");
			if ("contract".equals(emp))
				lines.add("نسخة من عقد العمل");
			if (home)
				lines.add("مستندات العقار (عرض البيع، الصك) + تقرير تثمين معتمد");
			if (lt == LoanType.AUTO)
				lines.add("عرض سعر السيارة من المعرض");
		} else {
			lines.add(nat == null ? "Valid National ID or Iqama"
					: (saudi ? "Valid National ID" : "Valid Iqama + passport"));
			lines.add("Recent salary certificate (≤ 30 days)"
					+ ("government".equals(emp) ? ", attested by your government employer" : ""));
			if ("self-employed".equals(emp))
				lines.add("Commercial registration + 6–12 months of bank statements");
			else
				lines.add("Bank statements for the last 3 months");
			if ("contract".equals(emp))
				lines.add("Copy of your employment contract");
			if (home)
				lines.add("Property documents (sale offer, deed) + accredited valuation report");
			if (lt == LoanType.AUTO)
				lines.add("Car quotation from the dealer");
		}

		StringBuilder sb = new StringBuilder(ar ? "**المستندات المطلوبة**" : "**Required documents**");
		String ctx = describeChecklistContext(state, ar);
		if (!ctx.isBlank())
			sb.append(" — ").append(ctx);
		sb.append(":\n\n");
		for (String l : lines)
			sb.append("- ").append(l).append('\n');

		// Progressive refinement: ask for the most impactful missing detail with
		// buttons, re-rendering this checklist after each answer (DOCS_REFINE flow)
		var opts = new ArrayList<ChatOption>();
		boolean refine = true;
		if (lt == null) {
			sb.append('\n').append(ar ? "_لأي نوع تمويل؟ اختر لقائمة أدق:_" : "_For which financing? Pick for a precise list:_");
			opts.add(new ChatOption("loan_personal", ar ? "تمويل شخصي" : "Personal Financing", ""));
			opts.add(new ChatOption("loan_mortgage", ar ? "تمويل عقاري" : "Mortgage / Home", ""));
			opts.add(new ChatOption("loan_auto", ar ? "تمويل سيارة" : "Car Loan", ""));
		} else if (nat == null) {
			sb.append('\n').append(ar ? "_هل أنت سعودي أم مقيم؟_" : "_Are you Saudi or an expat?_");
			opts.add(new ChatOption("nat_sa", ar ? "سعودي" : "Saudi", ""));
			opts.add(new ChatOption("nat_nonsa", ar ? "غير سعودي (مقيم)" : "Expat (Non-Saudi)", ""));
		} else if (emp == null) {
			sb.append('\n').append(ar ? "_ما جهة عملك؟_" : "_What's your employer type?_");
			opts.add(new ChatOption("emp_gov", ar ? "حكومي" : "Government", ""));
			opts.add(new ChatOption("emp_priv", ar ? "قطاع خاص" : "Private", ""));
			opts.add(new ChatOption("emp_cont", ar ? "متعاقد" : "Contract", ""));
			opts.add(new ChatOption("emp_self", ar ? "عمل حر" : "Self-employed", ""));
		} else {
			refine = false;
			opts.add(new ChatOption("elig", ar ? "التحقق من الأهلية" : "Check eligibility", ""));
		}
		state.setPendingFlow(refine ? "DOCS_REFINE" : null);
		opts.add(new ChatOption("contact", ar ? "التحدث إلى موظف" : "Talk to an agent", ""));
		return ChatReply.options(sb.toString(), withNav(opts, state.lang()));
	}

	private String describeChecklistContext(EligibilityState state, boolean ar) {
		var parts = new ArrayList<String>();
		if (state.getLoanType() != null) {
			parts.add(switch (state.getLoanType()) {
			case PERSONAL -> ar ? "تمويل شخصي" : "personal financing";
			case MORTGAGE, HOME -> ar ? "تمويل عقاري" : "home financing";
			case AUTO -> ar ? "تمويل سيارة" : "auto financing";
			});
		}
		if (state.getNationality() != null) {
			parts.add(ar ? (state.getNationality().equalsIgnoreCase("Saudi") ? "سعودي" : "غير سعودي")
					: state.getNationality());
		}
		String emp = state.getEmployerTypeNormalized();
		if (emp != null) {
			parts.add(switch (emp) {
			case "government" -> ar ? "موظف حكومي" : "government employee";
			case "private" -> ar ? "قطاع خاص" : "private sector";
			case "contract" -> ar ? "متعاقد" : "contractor";
			case "self-employed" -> ar ? "عمل حر" : "self-employed";
			default -> emp;
			});
		}
		return String.join(" • ", parts);
	}

	private ChatReply assistanceMenu(String lang) {
		if ("ar".equals(lang)) {
			return ChatReply.options("مرحباً! أنا مساعدك المالي. كيف أستطيع مساعدتك اليوم؟",
					withNav(List.of(new ChatOption("afford", "كم أقدر أقترض؟", ""),
							new ChatOption("elig", "التحقق من أهلية التمويل", ""),
							new ChatOption("emi", "حاسبة القسط الشهري (EMI)", ""),
							new ChatOption("track", "متابعة الطلب", ""),
							new ChatOption("docs", "الأسئلة الشائعة / المستندات", ""),
							new ChatOption("lang_en", "English", "")), "ar"));
		}
		return ChatReply.options("Hey! I’m your finance assistant. How can I help you today?",
				withNav(List.of(new ChatOption("afford", "How much can I borrow?", ""),
						new ChatOption("elig", "Check Loan Eligibility", ""),
						new ChatOption("emi", "Calculate EMI", ""), new ChatOption("track", "Track application", ""),
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
						new ChatOption("loan_auto", "Car Loan", "")), "en"));
	}

	private ChatReply afterLoanTypeChosen(EligibilityState state) {
		String lang = state.lang();
		String t = switch (state.getLoanType()) {
		case PERSONAL -> lang.equals("ar") ? "تمويل شخصي" : "Personal Financing";
		case MORTGAGE, HOME -> lang.equals("ar") ? "تمويل عقاري / رهن" : "Mortgage / Home Loan";
		case AUTO -> lang.equals("ar") ? "تمويل سيارة" : "Car Loan";
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

		// 4) Existing loans yes/no — ONLY exact answers or explicit loan-context
		// phrases. Bare substring matching here was a live bug: "yesterday"
		// contains "yes", and Arabic "لا" appears inside countless words
		// (السلام، الطلب…), silently flipping this flag on unrelated messages.
		String answer = lower.trim();
		boolean saysYesLoans = answer.equals("yes") || answer.equals("نعم")
				|| containsAny(lower, "loans yes", "have loans", "قروض موجودة نعم", "عندي قروض", "لدي قروض");
		boolean saysNoLoans = answer.equals("no") || answer.equals("لا") || answer.equals("لا يوجد")
				|| containsAny(lower, "loans no", "no loans", "no existing loans", "قروض موجودة لا",
						"لا يوجد قروض", "ما عندي قروض");
		if (saysYesLoans) {
			state.setHasExistingLoans(true);
			touched = true;
		} else if (saysNoLoans) {
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
		case AUTO -> 60; // product not offered; kept for enum completeness
		case MORTGAGE, HOME -> 240; // 20 years
		};
	}

	/** SAMA tenure limit — delegated to ProductPolicy. */
	private int productMaxTenureMonths(LoanType t) {
		return policy.maxTenureMonths(t);
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

		// Verdict cap if present, else SAMA's 33.33% salaried debt-burden cap
		double dtiCap = (v != null && v.suggestedDtiCap != null) ? v.suggestedDtiCap : (1.0 / 3.0);
		dtiCap = Math.max(0.20, Math.min(1.0 / 3.0, dtiCap)); // never exceed the SAMA cap

		// Max *new* EMI budget (income * cap minus existing obligations)
		double maxNewEmi = Math.max(0.0, dtiCap * income - other);

		// Choose tenure (clamped to the product/SAMA limit) and APR
		int n = (s.getTenureMonths() != null) ? s.getTenureMonths() : defaultTenureMonths(s.getLoanType());
		n = Math.min(n, productMaxTenureMonths(s.getLoanType()));
		double apr = rateService.getRate(s.getLoanType(),
				// we don't know amount yet, pass a rough guess
				Math.max(50_000, s.getAmount() == null ? 50_000 : s.getAmount()), n, s.getEmployerType());
		// Safety fallback if rate service returns 0/NaN
		if (Double.isNaN(apr) || apr <= 0.0)
			apr = fallbackRate(s.getLoanType());

		// Compute principal offer from EMI budget
		double rawAmount = principalFromEmi(maxNewEmi, apr, n);

		// Clamp to the company's nationality-aware product cap, apply a ~5%
		// conservative haircut, THEN round to the nearest 1,000
		double cap = policy.maxAmount(s.getLoanType(), s.getNationality());
		double offerAmount = Math.floor(Math.min(rawAmount, cap) * 0.95 / 1000.0) * 1000.0;

		// An "offer" below any meaningful financing amount is worse than none —
		// callers treat a null offer as "no offer line" and still show next steps.
		if (offerAmount < 10_000.0)
			return null;

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
