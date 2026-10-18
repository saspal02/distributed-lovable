package com.saswat.lovable.intelligence_service.llm;

import com.saswat.lovable.common_lib.dto.FileNode;
import com.saswat.lovable.intelligence_service.client.WorkspaceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectContextBuilder {

    private final WorkspaceClient workspaceClient;

    public List<Message> buildInitialMessages(
            String systemPrompt, String userMessage, Long projectId, String authorization) {

        List<FileNode> fileTree = workspaceClient.getFileTree(projectId, authorization).files();
        if (fileTree == null) {
            fileTree = List.of();
        }

        String userContent = "---- FILE_TREE ----\n" + fileTree + "\n\n" + userMessage;

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userContent));
        return messages;
    }
}
