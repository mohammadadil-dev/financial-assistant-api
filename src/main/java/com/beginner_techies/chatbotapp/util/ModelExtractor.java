package com.beginner_techies.chatbotapp.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModelExtractor {

	public static Path extractModel(String resourceFolder) throws IOException {
		Path tempDir = Files.createTempDirectory("tf-model");
		copyFolderFromResources(resourceFolder, tempDir);
		return tempDir;
	}

	private static void copyFolderFromResources(String resourceFolder, Path targetDir) throws IOException {
		var resource = ModelExtractor.class.getClassLoader().getResource(resourceFolder);
		if (resource == null) {
			throw new FileNotFoundException("Resource folder not found: " + resourceFolder);
		}
		try (var stream = resource.openStream()) {
			// We need to copy recursively: saved_model.pb and variables folder
			// For simplicity, unzip resourceFolder or use a pre-extracted folder in dev
			throw new UnsupportedOperationException("Recursive copy not implemented in this snippet.");
		}
	}
}