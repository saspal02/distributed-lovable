package com.saswat.lovable.intelligence_service.llm;

import com.saswat.lovable.common_lib.dto.FileNode;
import com.saswat.lovable.intelligence_service.client.WorkspaceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileTreeContextAdvisor implements StreamAdvisor {

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 256 * 1024;

    private static final String PATH_ATTRIBUTE = "path=\"";
    private static final String FILE_OPEN = "<file ";

    private static final Pattern FILE_REFERENCE_PATTERN = Pattern.compile(
            "[A-Za-z0-9_./-]+\\.(?:tsx|ts|jsx|js|css|json|html)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> SKIPPED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp",
            "woff", "woff2", "ttf", "eot", "otf",
            "mp4", "webm", "mp3", "wav", "pdf", "zip", "gz", "lock"
    );

    private static final Set<String> SKIPPED_FILES = Set.of(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lockb"
    );

    private final WorkspaceClient workspaceClient;

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain streamAdvisorChain) {
        Map<String, Object> context = request.context();
        Long projectId = Long.parseLong(context.getOrDefault("projectId", 0).toString());
        String authorization = context.getOrDefault("authorization", "").toString();

        ChatClientRequest augmentedChatClientRequest = augmentRequestWithFileTree(request, projectId, authorization);

        return streamAdvisorChain.nextStream(augmentedChatClientRequest);
    }

    private ChatClientRequest augmentRequestWithFileTree(ChatClientRequest request, Long projectId, String authorization) {

        List<Message> incomingMessages = request.prompt().getInstructions();

        Message systemMessage = incomingMessages.stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .findFirst()
                .orElse(null);

        List<Message> userMessages = incomingMessages.stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                .toList();

        List<FileNode> fileTree = workspaceClient.getFileTree(projectId, authorization).files();
        if (fileTree == null) {
            fileTree = List.of();
        }

        String userMessage = userMessages.isEmpty() ? "" : userMessages.get(0).getText();
        String projectContext = buildProjectContext(projectId, fileTree, authorization, userMessage);

        List<Message> allMessages = new ArrayList<>();
        if (systemMessage != null) {
            allMessages.add(systemMessage);
        }

        if (userMessages.isEmpty()) {
            allMessages.add(new UserMessage(projectContext));
        } else {
            Message firstUserMessage = userMessages.get(0);
            allMessages.add(new UserMessage(projectContext + "\n\n" + firstUserMessage.getText()));
            allMessages.addAll(userMessages.subList(1, userMessages.size()));
        }

        return request
                .mutate()
                .prompt(new Prompt(allMessages, request.prompt().getOptions()))
                .build();
    }

    private String buildProjectContext(Long projectId, List<FileNode> fileTree, String authorization, String userMessage) {
        StringBuilder context = new StringBuilder();
        context.append("---- FILE_TREE ----\n").append(fileTree);
        context.append("\n\n ---- FILE_CONTENTS ----\n");
        context.append("(The current contents of the relevant project files are provided below inside `")
                .append(FILE_OPEN).append(PATH_ATTRIBUTE).append("...\">` tags.)\n");

        int totalBytes = 0;
        for (String path : selectFiles(fileTree, userMessage)) {
            if (totalBytes >= MAX_TOTAL_BYTES) {
                context.append("\n(remaining files omitted: context budget reached)\n");
                break;
            }
            String content = fetchFileContent(projectId, path, authorization);
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_FILE_BYTES) {
                content = content.substring(0, MAX_FILE_BYTES) + "\n... (truncated)\n";
            }
            totalBytes += content.length();
            context.append("\n").append(FILE_OPEN).append(PATH_ATTRIBUTE).append(path).append("\">\n")
                    .append(content)
                    .append("\n</file>\n");
        }

        return context.toString();
    }

    private List<String> selectFiles(List<FileNode> fileTree, String userMessage) {
        Set<String> available = new LinkedHashSet<>();
        for (FileNode node : fileTree) {
            String path = node.path();
            if (path == null || path.isBlank() || isSkipped(path)) {
                continue;
            }
            available.add(path);
        }

        Set<String> selected = new LinkedHashSet<>();
        if (available.contains("package.json")) {
            selected.add("package.json");
        }

        Set<String> requested = extractRequestedPaths(userMessage, available);
        if (requested.isEmpty()) {
            selected.addAll(available);
        } else {
            selected.addAll(requested);
        }

        return new ArrayList<>(selected);
    }

    private Set<String> extractRequestedPaths(String userMessage, Set<String> available) {
        Set<String> paths = new LinkedHashSet<>();
        if (userMessage == null || userMessage.isBlank()) {
            return paths;
        }

        Matcher matcher = FILE_REFERENCE_PATTERN.matcher(userMessage);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (available.contains(candidate)) {
                paths.add(candidate);
                continue;
            }
            for (String path : available) {
                if (path.endsWith("/" + candidate)) {
                    paths.add(path);
                }
            }
        }

        return paths;
    }

    private String fetchFileContent(Long projectId, String path, String authorization) {
        try {
            return workspaceClient.getFileContent(projectId, path, authorization);
        } catch (Exception e) {
            log.warn("Could not load file {} for project {}", path, projectId);
            return null;
        }
    }

    private boolean isSkipped(String path) {
        String lower = path.toLowerCase();
        int slash = lower.lastIndexOf('/');
        String fileName = slash >= 0 ? lower.substring(slash + 1) : lower;

        if (SKIPPED_FILES.contains(fileName)) {
            return true;
        }

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }

        return SKIPPED_EXTENSIONS.contains(fileName.substring(dot + 1));
    }

    @Override
    public String getName() {
        return "FileTreeContextAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
