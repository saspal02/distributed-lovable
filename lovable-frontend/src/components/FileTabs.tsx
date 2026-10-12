import { X } from "lucide-react";
import { cn } from "@/lib/utils";

interface FileTabsProps {
  openTabs: string[];
  activeTab: string | null;
  onSelectTab: (path: string) => void;
  onCloseTab: (path: string) => void;
}

// Helper to get filename from path
const getFileName = (path: string) => path.split('/').pop() || path;

// Get file icon based on extension
const getFileIcon = (path: string) => {
  const ext = path.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'tsx':
    case 'ts':
      return 'text-blue-400';
    case 'jsx':
    case 'js':
      return 'text-yellow-400';
    case 'css':
      return 'text-pink-400';
    case 'json':
      return 'text-green-400';
    default:
      return 'text-muted-foreground';
  }
};

export function FileTabs({ openTabs, activeTab, onSelectTab, onCloseTab }: FileTabsProps) {
  if (openTabs.length === 0) return null;

  return (
    <div className="flex items-center border-b border-border/50 bg-panel overflow-x-auto overscroll-x-contain [-webkit-overflow-scrolling:touch]">
      {openTabs.map((path) => (
        <div
          key={path}
          className={cn(
            "group flex items-center gap-2 px-3 py-2 min-h-[44px] md:min-h-0 text-sm border-r border-border/30 cursor-pointer transition-colors min-w-0 shrink-0",
            activeTab === path
              ? "bg-background text-foreground"
              : "text-muted-foreground hover:text-foreground hover:bg-muted/30"
          )}
          onClick={() => onSelectTab(path)}
        >
          <span className={cn("shrink-0 w-2 h-2 rounded-full", getFileIcon(path))} />
          <span className="truncate max-w-[100px] sm:max-w-[120px]">{getFileName(path)}</span>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onCloseTab(path);
            }}
            aria-label={`Close ${getFileName(path)}`}
            className={cn(
              "shrink-0 p-2 md:p-0.5 -m-1 md:m-0 rounded hover:bg-muted/50 transition-opacity min-h-[44px] min-w-[44px] md:min-h-0 md:min-w-0 flex items-center justify-center",
              activeTab === path ? "opacity-100" : "opacity-100 md:opacity-0 md:group-hover:opacity-100"
            )}
          >
            <X className="w-4 h-4 md:w-3 md:h-3" />
          </button>
        </div>
      ))}
    </div>
  );
}
