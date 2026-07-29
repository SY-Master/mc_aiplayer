package io.github.zoyluo.aibot.persist;

public record PersistedBot(BotRecord bot, MissionRuntimeRecord missions, ConversationRecord conversation) {
    public PersistedBot {
        missions = missions == null ? MissionRuntimeRecord.empty() : missions;
        // conversation stays nullable — null means "no saved context" (backward compatible)
    }
}
