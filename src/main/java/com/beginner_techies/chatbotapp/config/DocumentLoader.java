package com.beginner_techies.chatbotapp.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Knowledge-base ingestion + retrieval.
 *
 * Ingestion: auto-discovers every markdown file under resources/knowledge/,
 * splits it into heading-based chunks, tags each chunk with metadata
 * {source, product, lang, chunk} and a DETERMINISTIC id — so re-seeding on
 * every boot upserts the same vectors instead of piling up duplicates.
 *
 * Retrieval: staged metadata filtering (lang+product → lang → unfiltered)
 * with a similarity threshold, so weak matches never reach the LLM.
 */
@Component
@Slf4j
public class DocumentLoader {

	private static final int CHUNK_SOFT_LIMIT = 1500;
	private static final int CHUNK_TARGET = 1200;

	private final VectorStore vectorStore;
	private final double similarityThreshold;

	public DocumentLoader(VectorStore vectorStore,
			@Value("${app.rag.similarity-threshold:0.45}") double similarityThreshold) {
		this.vectorStore = vectorStore;
		this.similarityThreshold = similarityThreshold;
	}

	@PostConstruct
	public void loadDocuments() {
		try {
			List<Document> docs = loadKnowledgeBaseChunks();
			if (docs.isEmpty()) {
				log.warn("No knowledge base documents were loaded; RAG context will be empty.");
				return;
			}
			vectorStore.add(docs); // deterministic ids → idempotent upsert
			log.info("Seeded {} knowledge chunk(s) into the vector store", docs.size());
		} catch (Exception e) {
			// don't crash the app if Pinecone is down; RAG will just be empty
			log.error("RAG seed failed : {} ", e.getMessage(), e);
		}
	}

	private List<Document> loadKnowledgeBaseChunks() throws IOException {
		var resolver = new PathMatchingResourcePatternResolver();
		Resource[] files = resolver.getResources("classpath:knowledge/*.md");
		var docs = new ArrayList<Document>();
		for (Resource res : files) {
			String name = res.getFilename() == null ? "unknown.md" : res.getFilename();
			String content;
			try (var in = res.getInputStream()) {
				content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			} catch (IOException e) {
				log.error("Failed to read knowledge base file {}: {}", name, e.getMessage());
				continue;
			}
			if (content.isEmpty())
				continue;
			String product = productOf(name);
			int i = 0;
			for (String chunk : chunkByHeadings(content)) {
				String lang = containsArabic(chunk) ? "ar" : "en";
				docs.add(new Document(chunkId(name, i, chunk), chunk,
						Map.of("source", name, "product", product, "lang", lang, "chunk", i)));
				i++;
			}
		}
		return docs;
	}

	/** Split on markdown headings, carrying the heading into each chunk; break oversized sections on paragraphs. */
	static List<String> chunkByHeadings(String content) {
		var sections = new ArrayList<String>();
		String heading = "";
		var body = new StringBuilder();
		for (String line : content.split("\n")) {
			if (line.startsWith("#")) {
				flushSection(sections, heading, body);
				heading = line.replaceAll("^#+\\s*", "").trim();
				body = new StringBuilder();
			} else {
				body.append(line).append('\n');
			}
		}
		flushSection(sections, heading, body);

		var out = new ArrayList<String>();
		for (String section : sections) {
			if (section.length() <= CHUNK_SOFT_LIMIT) {
				out.add(section);
				continue;
			}
			var part = new StringBuilder();
			for (String para : section.split("\n\\s*\n")) {
				if (part.length() + para.length() > CHUNK_TARGET && part.length() > 0) {
					out.add(part.toString().trim());
					part = new StringBuilder();
				}
				part.append(para).append("\n\n");
			}
			if (part.length() > 0)
				out.add(part.toString().trim());
		}
		out.removeIf(String::isBlank);
		return out;
	}

	private static void flushSection(List<String> sections, String heading, StringBuilder body) {
		String b = body.toString().trim();
		if (b.isEmpty())
			return;
		sections.add(heading.isEmpty() ? b : heading + "\n" + b);
	}

	private static String productOf(String filename) {
		String f = filename.toLowerCase();
		if (f.contains("personal"))
			return "personal";
		if (f.contains("home") || f.contains("mortgage"))
			return "home";
		if (f.contains("auto") || f.contains("car"))
			return "auto";
		return "general";
	}

	private static String chunkId(String source, int index, String content) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256")
					.digest((source + "#" + index + "#" + content).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			return source + "-" + index;
		}
	}

	/**
	 * Session-aware retrieval: filter by language and (when known) product first,
	 * relaxing stage by stage only when nothing matches.
	 */
	public List<Document> search(String query, String lang, String product, int topK) {
		var b = new FilterExpressionBuilder();
		if (product != null) {
			var hits = trySearch(query, topK, b.and(b.eq("lang", lang), b.eq("product", product)).build());
			if (!hits.isEmpty())
				return dedup(hits, topK);
		}
		var hits = trySearch(query, topK, b.eq("lang", lang).build());
		if (!hits.isEmpty())
			return dedup(hits, topK);
		return dedup(trySearch(query, topK, null), topK);
	}

	private List<Document> trySearch(String query, int topK, Filter.Expression filter) {
		try {
			var builder = SearchRequest.builder().query(query).topK(topK * 2)
					.similarityThreshold(similarityThreshold);
			if (filter != null)
				builder = builder.filterExpression(filter);
			return vectorStore.similaritySearch(builder.build());
		} catch (Exception e) {
			log.warn("RAG search failed: {}", e.getMessage());
			return List.of();
		}
	}

	private static List<Document> dedup(List<Document> hits, int topK) {
		var seen = new HashSet<String>();
		var out = new ArrayList<Document>();
		for (var d : hits) {
			String c = d.getFormattedContent() == null ? "" : d.getFormattedContent().trim();
			if (!c.isBlank() && seen.add(c))
				out.add(d);
			if (out.size() >= topK)
				break;
		}
		return out;
	}

	/** Kept for compatibility — delegates to the filtered search without a product. */
	public List<Document> searchByLangDiversified(String query, String lang, int finalTopK, int maxPerSource) {
		return search(query, lang, null, finalTopK);
	}

	/** Joins chunk contents with their source file, so the model can cite it. */
	public String joinContents(List<Document> docs) {
		if (docs == null || docs.isEmpty())
			return "";
		return docs.stream().map(d -> {
			Object src = d.getMetadata() == null ? null : d.getMetadata().get("source");
			return "- " + (src != null ? "[" + src + "] " : "") + d.getFormattedContent();
		}).collect(Collectors.joining("\n"));
	}

	private static boolean containsArabic(String s) {
		if (s == null || s.isBlank())
			return false;
		return s.codePoints().anyMatch(cp -> (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F)
				|| (cp >= 0x08A0 && cp <= 0x08FF) || (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF));
	}
}
