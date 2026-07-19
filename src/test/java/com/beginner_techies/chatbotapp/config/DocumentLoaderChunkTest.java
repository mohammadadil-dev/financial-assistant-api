package com.beginner_techies.chatbotapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentLoaderChunkTest {

	@Test
	void splitsOnHeadingsAndCarriesHeadingIntoChunk() {
		String md = "# Fees\nProcessing fee is 1%.\n\n## Early settlement\nCapped at 3 months' profit.";
		var chunks = DocumentLoader.chunkByHeadings(md);
		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0)).startsWith("Fees").contains("1%");
		assertThat(chunks.get(1)).startsWith("Early settlement").contains("3 months");
	}

	@Test
	void contentWithoutHeadingsBecomesOneChunk() {
		var chunks = DocumentLoader.chunkByHeadings("Just a plain paragraph of policy text.");
		assertThat(chunks).hasSize(1);
	}

	@Test
	void oversizedSectionsAreSplitOnParagraphs() {
		String para = "A sentence of policy text that repeats. ".repeat(20); // ~800 chars
		String md = "# Big section\n" + para + "\n\n" + para + "\n\n" + para;
		var chunks = DocumentLoader.chunkByHeadings(md);
		assertThat(chunks.size()).isGreaterThan(1);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(2000));
	}

	@Test
	void emptyAndBlankInputYieldNothing() {
		assertThat(DocumentLoader.chunkByHeadings("")).isEmpty();
		assertThat(DocumentLoader.chunkByHeadings("# Heading only\n\n")).isEmpty();
	}
}
