package com.saswat.lovable.intelligence_service.llm;

import com.saswat.lovable.common_lib.enums.ChatEventStatus;
import com.saswat.lovable.common_lib.enums.ChatEventType;
import com.saswat.lovable.intelligence_service.entity.ChatEvent;
import com.saswat.lovable.intelligence_service.entity.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class LlmResponseParser {

    /**
     * Regex Breakdown:
     * Group 1: Opening Tag (<tag ...>)
     * Group 2: Tag Name (message|file|tool)
     * Group 3: Attributes part (e.g., ' path="foo"' or ' args="a,b"')
     * Group 4: Content (The stuff inside)
     * Group 5: Closing Tag (</tag>)
     */

    private static final Pattern GENERIC_TAG_PATTERN = Pattern.compile(
            "(<(message|file|tool)([^>]*)>)([\\s\\S]*?)(</\\2>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern UNCLOSED_FILE_PATTERN = Pattern.compile(
            "<file([^>]*)>((?:(?!</file>)[\\s\\S])*)$",
            Pattern.CASE_INSENSITIVE
    );

    // Helper to extract specific attributes (path="..." or args="...") from Group 3
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "(path|args)=\"([^\"]+)\""
    );

    public List<ChatEvent> parseChatEvents(String fullResponse, ChatMessage parentMessage) {
        List<ChatEvent> events = new ArrayList<>();
        int orderCounter = 1;

        Matcher matcher = GENERIC_TAG_PATTERN.matcher(fullResponse);
        int lastMatchEnd = 0;

        while (matcher.find()) {
            String tagName = matcher.group(2).toLowerCase();
            String attributes = matcher.group(3);
            String content = matcher.group(4).trim();
            lastMatchEnd = matcher.end();

            // Extract attributes map
            Map<String, String> attrMap = extractAttributes(attributes);

            ChatEvent.ChatEventBuilder builder = ChatEvent.builder()
                    .status(ChatEventStatus.CONFIRMED)
                    .chatMessage(parentMessage)
                    .content(content) // This is your Markdown content
                    .sequenceOrder(orderCounter++);

            switch (tagName) {
                case "message" -> builder.type(ChatEventType.MESSAGE);
                case "file" -> {
                    builder.status(ChatEventStatus.PENDING);
                    builder.type(ChatEventType.FILE_EDIT);
                    builder.filePath(attrMap.get("path")); // Required for files
//                    builder.content(null);
                }
                case "tool" -> {
                    builder.type(ChatEventType.TOOL_LOG);
                    builder.metadata(attrMap.get("args")); // Store raw file list in metadata
                }
                default -> { continue; }
            }

            events.add(builder.build());
        }

        Matcher unclosedMatcher = UNCLOSED_FILE_PATTERN.matcher(fullResponse.substring(lastMatchEnd));
        if (unclosedMatcher.find()) {
            String attributes = unclosedMatcher.group(1);
            String content = unclosedMatcher.group(2).trim();
            Map<String, String> attrMap = extractAttributes(attributes);
            String path = attrMap.get("path");

            if (path != null && !content.isEmpty()) {
                log.warn("Recovered unclosed <file> tag for path {}", path);
                events.add(ChatEvent.builder()
                        .status(ChatEventStatus.PENDING)
                        .type(ChatEventType.FILE_EDIT)
                        .chatMessage(parentMessage)
                        .filePath(path)
                        .content(content)
                        .sequenceOrder(orderCounter)
                        .build());
            }
        }

        return events;
    }

    private Map<String, String> extractAttributes(String attributeString) {
        Map<String, String> attributes = new HashMap<>();
        if (attributeString == null) return attributes;

        Matcher matcher = ATTRIBUTE_PATTERN.matcher(attributeString);
        while (matcher.find()) {
            attributes.put(matcher.group(1), matcher.group(2));
        }
        return attributes;
    }

}
