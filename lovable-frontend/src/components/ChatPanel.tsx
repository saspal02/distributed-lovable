import { useState, useRef, useEffect } from "react";
import { Send, Loader2, Bot, ThumbsUp, ThumbsDown, Copy, RotateCcw, MoreHorizontal, FileCode } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { format } from "date-fns";
import { useStreamParser } from "../hooks/use-stream-parser";
import { ChatEventRenderer } from './ChatEventRenderer';
import { ChatEvent } from "@/lib/types";

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  isStreaming?: boolean;
  createdAt?: string;
  events?: ChatEvent[]; // Structured events from the database
  editedFiles?: string[];
}

interface ChatPanelProps {
  messages: ChatMessage[];
  onSendMessage: (message: string) => void;
  isStreaming: boolean;
  isLoading?: boolean;
  readOnly?: boolean;
}

export function ChatPanel({ messages, onSendMessage, isStreaming, isLoading, readOnly }: ChatPanelProps) {
  const [input, setInput] = useState("");
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isStreaming) return;

    onSendMessage(input.trim());
    setInput("");

    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value);
    const textarea = e.target;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
  };

  return (
    <div className="flex flex-col h-full min-h-0 bg-background">
      {/* Messages */}
      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain [-webkit-overflow-scrolling:touch]">
        {isLoading ? (
          <div className="flex items-center justify-center h-full">
            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
          </div>
        ) : messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-center p-8">
            <div className="w-14 h-14 rounded-xl bg-primary/20 flex items-center justify-center mb-4">
              <Bot className="w-7 h-7 text-primary" />
            </div>
            <h3 className="text-base font-medium mb-1">Start a conversation</h3>
            <p className="text-sm text-muted-foreground max-w-xs">
              Describe what you want to build or modify
            </p>
          </div>
        ) : (
          <div className="flex flex-col">
            {messages.map((message) => (
              <MessageItem key={message.id} message={message}
                isStreaming={isStreaming && message.isStreaming} />
            ))}
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input Area */}
      <div className="shrink-0 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] border-t border-border/50 bg-card">
        <form onSubmit={handleSubmit} className="relative">
          <Textarea
            ref={textareaRef}
            value={input}
            onChange={handleTextareaChange}
            onKeyDown={handleKeyDown}
            placeholder={readOnly ? "You have view-only access to this project" : "Describe what you want to build..."}
            className="min-h-[48px] max-h-[200px] pr-14 resize-none bg-muted/30 border-border/30 focus:border-primary/50 rounded-xl text-base md:text-sm"
            disabled={isStreaming || readOnly}
            rows={1}
          />
          <Button
            type="submit"
            size="icon"
            disabled={!input.trim() || isStreaming || readOnly}
            aria-label="Send message"
            className="absolute right-2 bottom-2 h-11 w-11 md:h-8 md:w-8 rounded-lg shrink-0"
          >
            {isStreaming ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Send className="w-4 h-4" />
            )}
          </Button>
        </form>

        <div className="flex items-center justify-between mt-2 px-1">
          <div className="flex items-center gap-1 text-xs text-muted-foreground">
            <span>✨ AI-Powered Design System</span>
          </div>
          {isStreaming && (
            <span className="text-xs text-muted-foreground flex items-center gap-1 font-medium">
              <Loader2 className="w-3 h-3 animate-spin text-primary" />
              Thinking...
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// Inner Component to handle logic per message
function MessageItem({ message, isStreaming }: { message: ChatMessage, isStreaming: boolean }) {
  // Use the stream parser to turn raw XML text into Event objects live
  // 1. Parse content live if we are streaming OR if we don't have DB events yet
  const liveEvents = useStreamParser(message.content || "");

  // 2. Logic: If we have DB events, use them. Otherwise, use the parsed content.
  const eventsToRender = (message.events && message.events.length > 0)
    ? message.events
    : liveEvents;

  return (
    <div className={`p-3 sm:p-5 border-b border-border/10 ${message.role === 'user' ? 'bg-muted/10' : 'bg-background'}`}>
      <div className="max-w-4xl mx-auto min-w-0">
        {message.role === "user" ? (
          <div className="flex flex-col items-end gap-2 min-w-0">
            <div className="bg-primary/10 text-primary-foreground text-sm py-2.5 px-4 rounded-2xl rounded-tr-none border border-primary/20 max-w-[92%] sm:max-w-[85%] min-w-0">
              <p className="text-foreground leading-relaxed whitespace-pre-wrap break-words">{message.content}</p>
            </div>
            {message.createdAt && (
              <span className="text-[10px] text-muted-foreground px-1 uppercase tracking-tight">
                {format(new Date(message.createdAt), "HH:mm")}
              </span>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            {/* Render granular events (Thought, Tool, Message, File) */}
            <div className="flex flex-col gap-3">
              {eventsToRender.map((event, idx) => {
                const isLast = idx === eventsToRender.length - 1;
                return (
                  <ChatEventRenderer
                    key={idx}
                    event={event}
                    // It is "loading" only if:
                    // 1. The message is currently streaming
                    // 2. AND this is the last event in the list
                    isLoading={isStreaming && isLast}
                  />
                );
              })}
            </div>

            {/* Action buttons for assistant message */}
            {!message.isStreaming && eventsToRender.length > 0 && (
              <div className="flex items-center gap-1 pt-2">
                <Button variant="ghost" size="icon" aria-label="Retry" className="h-11 w-11 md:h-8 md:w-8 text-muted-foreground hover:text-primary">
                  <RotateCcw className="w-3.5 h-3.5" />
                </Button>
                <Button variant="ghost" size="icon" aria-label="Good response" className="h-11 w-11 md:h-8 md:w-8 text-muted-foreground hover:text-primary">
                  <ThumbsUp className="w-3.5 h-3.5" />
                </Button>
                <Button variant="ghost" size="icon" aria-label="Bad response" className="h-11 w-11 md:h-8 md:w-8 text-muted-foreground hover:text-primary">
                  <ThumbsDown className="w-3.5 h-3.5" />
                </Button>
                <Button variant="ghost" size="icon" aria-label="Copy response" className="h-11 w-11 md:h-8 md:w-8 text-muted-foreground hover:text-primary">
                  <Copy className="w-3.5 h-3.5" />
                </Button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}