package com.beginner_techies.chatbotapp.config;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DocumentLoader {

	private final VectorStore vectorStore;

	public DocumentLoader(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	@PostConstruct
	public void loadDocuments() {
		try {
			// seed minimal facts
			List<Document> docs = List.of(new Document("Loan interest rate is 7% per annum for personal loans."),
					new Document("Required documents: National ID, Salary Certificate, Bank Statements."),
					new Document("SAMA regulations require customer verification before loan disbursement."),
					// Arabic variants
					new Document("المستندات المطلوبة: الهوية الوطنية، شهادة الراتب، كشوف الحساب."),
					new Document("تتطلب لوائح البنك المركزي السعودي (ساما) التحقق من هوية العميل قبل صرف القرض."));
			vectorStore.add(docs);
		} catch (Exception e) {
			// don't crash the app if Pinecone is down; RAG will just be empty
			log.error("RAG seed failed : {} ", e.getMessage(), e);
		}
	}

	public List<Document> searchByLangDiversified(String query, String lang, int finalTopK, int maxPerSource) {
		List<Document> hits = new ArrayList<>();
		try {
			var req = SearchRequest.builder().query(query).topK(finalTopK * 2).build();
			hits = vectorStore.similaritySearch(req);
		} catch (Exception e) {
			return List.of();
		}
		// naive diversification by dedup content
		var seen = new java.util.HashSet<String>();
		var out = new ArrayList<Document>();
		for (var d : hits) {
			String c = (d.getFormattedContent() == null ? "" : d.getFormattedContent().trim());
			if (lang.equals("ar") && containsArabic(c)) {
				if (seen.add(c))
					out.add(d);
			} else if ("en".equals(lang) && !containsArabic(c)) {
				if (seen.add(c))
					out.add(d);
			} else {
				// allow cross-language if not enough results
				if (seen.add(c))
					out.add(d);
			}
			if (out.size() >= finalTopK)
				break;
		}
		return out;
	}

	public String joinContents(List<Document> docs) {
		if (docs == null || docs.isEmpty())
			return "";
		return docs.stream().map(d -> "- " + d.getFormattedContent()).collect(Collectors.joining("\n"));
	}

	private static boolean containsArabic(String s) {
		if (s == null || s.isBlank())
			return false;
		return s.codePoints().anyMatch(cp -> (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F)
				|| (cp >= 0x08A0 && cp <= 0x08FF) || (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF));
	}
}