package io.github.zoyluo.aibot.persist;

import io.github.zoyluo.aibot.brain.ChatMessage;

import java.util.List;

/** Serializable snapshot of the LLM conversation context for a single bot. */
public record ConversationRecord(
        List<ChatMessage> history,
        long lastGoalResultSequence
) {
    public ConversationRecord {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }

    public static ConversationRecord empty() {
        return new ConversationRecord(List.of(), 0L);
    }
}
