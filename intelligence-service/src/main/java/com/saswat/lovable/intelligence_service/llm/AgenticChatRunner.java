package com.saswat.lovable.intelligence_service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgenticChatRunner {

    private static final int MAX_SELECTION_ROUNDS = 4;
    private static final int MAX_FILES_PER_ROUND = 10;

    private static final Pattern TOOL_ARGS_PATTERN = Pattern.compile(
            "<tool[^>]*\\bargs=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private final ChatClient chatClient;
    private final ProjectContextBuilder projectContextBuilder;

    public Flux<ChatResponse> stream(String userMessage, Long projectId, String authorization) {
        List<Message> selectionMessages =
                projectContextBuilder.buildSelectionMessages(userMessage, projectId, authorization);
        Map<String, String> knownContents = new LinkedHashMap<>();
        return runSelection(
                selectionMessages, projectId, authorization, new LinkedHashSet<>(), knownContents, 1, userMessage);
    }

    private Flux<ChatResponse> runSelection(
            List<Message> messages, Long projectId, String authorization, Set<String> readPaths,
            Map<String, String> knownContents, int roundNumber, String userMessage) {

        StringBuilder roundText = new StringBuilder();

        return chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String text = extractText(response);
                    if (text != null && !text.isEmpty()) {
                        roundText.append(text);
                    }
                })
                .concatWith(Flux.defer(() -> afterSelection(
                        messages, roundText.toString(), projectId, authorization, readPaths, knownContents, roundNumber, userMessage)));
    }

    private Flux<ChatResponse> afterSelection(
            List<Message> messages, String roundText, Long projectId, String authorization,
            Set<String> readPaths, Map<String, String> knownContents, int roundNumber, String userMessage) {

        List<String> requested = extractRequestedPaths(roundText, readPaths);
        if (requested.isEmpty() || roundNumber >= MAX_SELECTION_ROUNDS) {
            return generate(userMessage, projectId, authorization, knownContents);
        }

        Map<String, String> roundContents = projectContextBuilder.readContents(requested, projectId, authorization);
        knownContents.putAll(roundContents);
        readPaths.addAll(requested);

        List<Message> nextMessages = new ArrayList<>(messages);
        nextMessages.add(new AssistantMessage(roundText));
        nextMessages.add(new UserMessage(renderRequestedContents(requested, roundContents)));

        return runSelection(nextMessages, projectId, authorization, readPaths, knownContents, roundNumber + 1, userMessage);
    }

    private Flux<ChatResponse> generate(
            String userMessage, Long projectId, String authorization, Map<String, String> knownContents) {

        List<Message> generationMessages =
                projectContextBuilder.buildGenerationMessages(userMessage, projectId, authorization, knownContents);

        return chatClient.prompt()
                .messages(generationMessages)
                .stream()
                .chatResponse();
    }

    private List<String> extractRequestedPaths(String roundText, Set<String> readPaths) {
        List<String> paths = new ArrayList<>();
        if (roundText == null || roundText.isBlank()) {
            return paths;
        }

        Matcher matcher = TOOL_ARGS_PATTERN.matcher(roundText);
        while (matcher.find() && paths.size() < MAX_FILES_PER_ROUND) {
            for (String raw : matcher.group(1).split(",")) {
                String path = raw.trim();
                boolean alreadyHandled = path.isEmpty() || readPaths.contains(path) || paths.contains(path);
                if (alreadyHandled) {
                    continue;
                }
                paths.add(path);
                if (paths.size() >= MAX_FILES_PER_ROUND) {
                    break;
                }
            }
        }

        return paths;
    }

    private String renderRequestedContents(List<String> paths, Map<String, String> contents) {
        StringBuilder result = new StringBuilder("Here are the requested file contents.\n");
        for (String path : paths) {
            String content = contents.get(path);
            if (content == null) {
                result.append("\n--- FILE: ").append(path).append(" (not found) ---\n");
            } else {
                result.append("\n--- START OF FILE: ").append(path).append(" ---\n")
                        .append(content)
                        .append("\n--- END OF FILE ---\n");
            }
        }
        return result.toString();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }
}
