package com.beginner_techies.chatbotapp.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DocumentLoader {

	// Known-good knowledge base files under src/main/resources/knowledge/.
	private static final List<String> KNOWLEDGE_BASE_FILES = List.of("knowledge/loans_personal_basics_en.md",
			"knowledge/loans_personal_docs_en.md");

	private final VectorStore vectorStore;

	public DocumentLoader(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	@PostConstruct
	public void loadDocuments() {
		try {
			List<Document> docs = loadKnowledgeBaseDocuments();
			if (docs.isEmpty()) {
				log.warn("No knowledge base documents were loaded; RAG context will be empty.");
				return;
			}
			vectorStore.add(docs);
			log.info("Seeded {} knowledge base document(s) into the vector store", docs.size());
		} catch (Exception e) {
			// don't crash the app if Pinecone is down; RAG will just be empty
			log.error("RAG seed failed : {} ", e.getMessage(), e);
		}
	}

	private List<Document> loadKnowledgeBaseDocuments() {
		var docs = new ArrayList<Document>();
		for (String path : KNOWLEDGE_BASE_FILES) {
			var resource = new ClassPathResource(path);
			if (!resource.exists()) {
				log.warn("Knowledge base file not found on classpath: {}", path);
				continue;
			}
			try (var in = resource.getInputStream()) {
				String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!content.isEmpty()) {
					docs.add(new Document(content, Map.of("filename", resource.getFilename())));
				}
			} catch (IOException e) {
				log.error("Failed to read knowledge base file {}: {}", path, e.getMessage(), e);
			}
		}
		return docs;
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