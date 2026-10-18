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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileTreeContextAdvisor implements StreamAdvisor {

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 256 * 1024;

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

        List<Message> allMessages = new ArrayList<>();

        if (systemMessage != null) {
            allMessages.add(systemMessage);
        }

        List<FileNode> fileTree = workspaceClient.getFileTree(projectId, authorization).files();
        if (fileTree == null) {
            fileTree = List.of();
        }

        allMessages.add(new SystemMessage("\n\n ---- FILE_TREE ----\n" + fileTree));
        allMessages.add(new SystemMessage(buildFileContents(projectId, fileTree, authorization)));

        allMessages.addAll(userMessages);

        return request
                .mutate()
                .prompt(new Prompt(allMessages, request.prompt().getOptions()))
                .build();
    }

    private String buildFileContents(Long projectId, List<FileNode> fileTree, String authorization) {
        StringBuilder contents = new StringBuilder("\n\n ---- FILE_CONTENTS ----\n");
        int totalBytes = 0;

        for (FileNode node : fileTree) {
            String path = node.path();
            if (path == null || path.isBlank() || isSkipped(path)) {
                continue;
            }
            if (totalBytes >= MAX_TOTAL_BYTES) {
                contents.append("\n(remaining files omitted: context budget reached)\n");
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
            contents.append("\n<file path=\"").append(path).append("\">\n")
                    .append(content)
                    .append("\n</file>\n");
        }

        return contents.toString();
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
