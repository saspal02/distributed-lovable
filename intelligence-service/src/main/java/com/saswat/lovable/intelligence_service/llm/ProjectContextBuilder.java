package com.saswat.lovable.intelligence_service.llm;

import com.saswat.lovable.common_lib.dto.FileNode;
import com.saswat.lovable.intelligence_service.client.WorkspaceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

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
public class ProjectContextBuilder {

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 256 * 1024;

    private static final String PACKAGE_JSON = "package.json";
    private static final String FILE_OPEN = "<file path=\"";

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

    public List<Message> buildSelectionMessages(String userMessage, Long projectId, String authorization) {
        String fileTree = formatFileTree(projectId, authorization);
        String userContent = "---- FILE_TREE ----\n" + fileTree + "\n\n" + userMessage;
        return List.of(
                new SystemMessage(PromptUtils.FILE_SELECTION_SYSTEM_PROMPT),
                new UserMessage(userContent));
    }

    public List<Message> buildGenerationMessages(
            String userMessage, Long projectId, String authorization, Map<String, String> knownContents) {

        List<FileNode> fileTree = fetchFileTree(projectId, authorization);
        Set<String> available = availablePaths(fileTree);

        Set<String> paths = new LinkedHashSet<>();
        if (available.contains(PACKAGE_JSON)) {
            paths.add(PACKAGE_JSON);
        }
        paths.addAll(extractRequestedPaths(userMessage, available));
        paths.addAll(knownContents.keySet());
        if (paths.isEmpty() || (paths.size() == 1 && paths.contains(PACKAGE_JSON))) {
            paths.addAll(available);
        }

        String userContent = buildContentBlock(fileTree, paths, projectId, authorization, knownContents)
                + "\n\n" + userMessage;

        return List.of(
                new SystemMessage(PromptUtils.CODE_GENERATION_SYSTEM_PROMPT),
                new UserMessage(userContent));
    }

    public Map<String, String> readContents(List<String> paths, Long projectId, String authorization) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (String path : paths) {
            String content = fetchFileContent(projectId, path, authorization);
            if (content != null) {
                contents.put(path, content);
            }
        }
        return contents;
    }

    private String buildContentBlock(
            List<FileNode> fileTree, Set<String> paths, Long projectId,
            String authorization, Map<String, String> knownContents) {

        StringBuilder context = new StringBuilder();
        context.append("---- FILE_TREE ----\n").append(fileTree);
        context.append("\n\n---- FILE_CONTENTS ----\n");
        context.append("(The current contents of the relevant project files are provided below inside `")
                .append(FILE_OPEN)
                .append("...\">` tags.)\n");

        int totalBytes = 0;
        for (String path : paths) {
            if (totalBytes >= MAX_TOTAL_BYTES) {
                context.append("\n(remaining files omitted: context budget reached)\n");
                break;
            }
            String content = knownContents.get(path);
            if (content == null) {
                content = fetchFileContent(projectId, path, authorization);
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_FILE_BYTES) {
                content = content.substring(0, MAX_FILE_BYTES) + "\n... (truncated)\n";
            }
            totalBytes += content.length();
            context.append("\n").append(FILE_OPEN).append(path).append("\">\n")
                    .append(content)
                    .append("\n</file>\n");
        }

        return context.toString();
    }

    private String formatFileTree(Long projectId, String authorization) {
        return String.valueOf(fetchFileTree(projectId, authorization));
    }

    private List<FileNode> fetchFileTree(Long projectId, String authorization) {
        List<FileNode> fileTree = workspaceClient.getFileTree(projectId, authorization).files();
        if (fileTree == null) {
            return List.of();
        }
        return fileTree;
    }

    private Set<String> availablePaths(List<FileNode> fileTree) {
        Set<String> available = new LinkedHashSet<>();
        for (FileNode node : fileTree) {
            String path = node.path();
            boolean skip = path == null || path.isBlank() || isSkipped(path);
            if (skip) {
                continue;
            }
            available.add(path);
        }
        return available;
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
}
