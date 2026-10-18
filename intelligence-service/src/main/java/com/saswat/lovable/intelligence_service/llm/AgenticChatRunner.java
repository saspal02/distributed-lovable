package com.saswat.lovable.intelligence_service.llm;

import com.saswat.lovable.intelligence_service.client.WorkspaceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgenticChatRunner {

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final int MAX_FILES_PER_ROUND = 10;

    private static final Pattern TOOL_ARGS_PATTERN = Pattern.compile(
            "<tool[^>]*\\bargs=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private final ChatClient chatClient;
    private final WorkspaceClient workspaceClient;

    public Flux<ChatResponse> stream(List<Message> messages, Long projectId, String authorization) {
        return runRound(messages, projectId, authorization, new LinkedHashSet<>(), 1);
    }

    private Flux<ChatResponse> runRound(
            List<Message> messages, Long projectId, String authorization, Set<String> readPaths, int roundNumber) {

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
                .concatWith(Flux.defer(() ->
                        nextRound(messages, roundText.toString(), projectId, authorization, readPaths, roundNumber)));
    }

    private Flux<ChatResponse> nextRound(
            List<Message> messages, String roundText, Long projectId,
            String authorization, Set<String> readPaths, int roundNumber) {

        List<String> requested = extractRequestedPaths(roundText, readPaths);
        if (requested.isEmpty()) {
            return Flux.empty();
        }
        if (roundNumber >= MAX_TOOL_ROUNDS) {
            log.warn("Tool round cap reached for projectId: {}", projectId);
            return Flux.empty();
        }

        String toolResult = readFiles(requested, projectId, authorization);
        readPaths.addAll(requested);

        List<Message> nextMessages = new ArrayList<>(messages);
        nextMessages.add(new AssistantMessage(roundText));
        nextMessages.add(new UserMessage(toolResult));

        return runRound(nextMessages, projectId, authorization, readPaths, roundNumber + 1);
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

    private String readFiles(List<String> paths, Long projectId, String authorization) {
        StringBuilder result = new StringBuilder("Here are the requested file contents.\n");
        CodeGenerationTools tools = new CodeGenerationTools(projectId, workspaceClient, authorization);

        for (String path : paths) {
            try {
                List<String> content = tools.readFiles(List.of(path));
                if (content.isEmpty()) {
                    result.append("\n--- FILE: ").append(path).append(" (empty) ---\n");
                } else {
                    result.append("\n").append(content.get(0)).append("\n");
                }
            } catch (Exception e) {
                log.warn("Failed to read {} for project {}: {}", path, projectId, e.getMessage());
                result.append("\n--- ERROR: could not read ").append(path).append(" ---\n");
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
