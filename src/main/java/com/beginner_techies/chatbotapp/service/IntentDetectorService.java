package com.beginner_techies.chatbotapp.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.IntentResult;
import com.beginner_techies.chatbotapp.enums.Intent;
import com.beginner_techies.chatbotapp.util.EligibilityHeuristics;
import com.beginner_techies.chatbotapp.util.EmiHeuristics;

@Service
public class IntentDetectorService {

    private final ChatClient chatClient;
    private final EmiHeuristics emiHeuristics = new EmiHeuristics();

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public IntentDetectorService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public IntentResult detect(String userMessage, String lang) {
        if (userMessage == null || userMessage.isBlank()) {
            var r = new IntentResult();
            r.intent = Intent.ASSISTANCE;
            return r;
        }

        // fast-paths
        IntentResult fastEmi = emiHeuristics.tryParse(userMessage);
        if (fastEmi != null) return fastEmi;

        IntentResult fastElig = EligibilityHeuristics.tryParse(userMessage);
        if (fastElig != null) return fastElig;

        String system = """
            You are an NLU router for a bilingual (Arabic + English) banking assistant in KSA.
            Classify the user's intent and extract slots into a single MINIFIED JSON object.
            Return ONLY one minified JSON object (no prose, no code fences).

            INTENTS
            - ASSISTANCE: greetings/help.
            - EMI_CALC: user asks for EMI or provides amount/tenure.
            - ELIGIBILITY_CHECK: user asks about eligibility.
            - ELIGIBILITY_SLOT_UPDATE: user provides eligibility values (even unlabeled).
            - ACCOUNT_QUERY: user asks about his own account (needsAuth=true).
            - FAQ_RAG: general banking/loan FAQ.
            - UNKNOWN: anything else.

            RULES
            - Language may be Arabic or English; classification must work for both.
            - For EMI_CALC: set annualRate=null (rate is decided internally).
            - If you cannot infer a field, set it to null. Always include all keys.
            - Output keys exactly as below.

            OUTPUT:
            {"intent":"ASSISTANCE|EMI_CALC|ELIGIBILITY_CHECK|ELIGIBILITY_SLOT_UPDATE|ACCOUNT_QUERY|FAQ_RAG|UNKNOWN",
             "currency":null,"amount":null,"annualRate":null,"tenureMonths":null,
             "monthlyIncome":null,"employerType":null,"serviceMonths":null,"hasExistingLoans":null,
             "nationality":null,"needsAuth":false}
            """;

        String raw;
        try {
            raw = chatClient.prompt().system(system)
                    .user(userMessage + "\nReturn ONLY minified JSON.").call().content();
        } catch (Exception e) {
            var r = new IntentResult();
            r.intent = guessAssistance(userMessage) ? Intent.ASSISTANCE : Intent.UNKNOWN;
            r.needsAuth = false;
            return r;
        }

        String json = extractFirstJsonObject(raw);
        try {
            return MAPPER.readValue(json, IntentResult.class);
        } catch (Exception e) {
            var r = new IntentResult();
            r.intent = guessAssistance(userMessage) ? Intent.ASSISTANCE : Intent.UNKNOWN;
            r.needsAuth = false;
            return r;
        }
    }

    private static String extractFirstJsonObject(String s) {
        if (s == null) return "{}";
        s = s.replaceAll("(?s)```+.*?```+", "");
        int start = s.indexOf('{');
        int brace = 0;
        for (int i = start; i >= 0 && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') brace++;
            else if (c == '}') {
                brace--;
                if (brace == 0) return s.substring(start, i + 1);
            }
        }
        return "{}";
    }

    private static boolean guessAssistance(String msg) {
        String m = msg.toLowerCase();
        return m.matches(".*\\b(hi|hello|hey|help|good\\s*(morning|evening|afternoon))\\b.*")
                || msg.contains("مرحبا") || msg.contains("أهلًا") || msg.contains("السلام عليكم");
    }
}
