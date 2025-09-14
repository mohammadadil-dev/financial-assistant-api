package com.beginner_techies.chatbotapp.record;

import java.util.List;

public record ChatReply(String type, String text, java.util.List<ChatOption> options) {

	public static ChatReply text(String t) {
		return new ChatReply("text", t, List.of());
	}

	public static ChatReply options(String t, List<ChatOption> opts) {
		return new ChatReply("options", t, opts);
	}
}
