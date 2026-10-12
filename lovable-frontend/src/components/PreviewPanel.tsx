import { useState, useEffect, useRef, useCallback } from "react";
import { Play, Loader2, ExternalLink, RefreshCw, Globe, Square } from "lucide-react";
import { Button } from "@/components/ui/button";
import { api, getPreviewUrlKey, PREVIEW_URL_KEY } from "@/lib/api";
import { useToast } from "@/hooks/use-toast";

import { RuntimeErrorAlert, RuntimeError } from "@/components/RuntimeErrorAlert";

interface PreviewPanelProps {
  projectId: string;
  runtimeError: RuntimeError | null;
  onDismiss: () => void;
  onFix: (error: RuntimeError) => void;
}

const isPreviewUrlForProject = (url: string | null, projectId: string): url is string =>
  !!url && url.includes(`project-${projectId}.`);

function loadPreviewUrl(projectId: string): string | null {
  const scoped = localStorage.getItem(getPreviewUrlKey(projectId));
  if (isPreviewUrlForProject(scoped, projectId)) return scoped;

  // One-time adoption of the legacy global key when it belongs to this project
  const legacy = localStorage.getItem(PREVIEW_URL_KEY);
  if (isPreviewUrlForProject(legacy, projectId)) {
    localStorage.setItem(getPreviewUrlKey(projectId), legacy);
    return legacy;
  }

  return null;
}

// Post-deploy reload schedule. Vite needs minutes for npm install + boot,
// so remount the iframe a few times before giving up to manual refresh.
const RETRY_DELAYS_MS = [10000, 20000, 30000, 45000, 60000, 90000];

export function PreviewPanel({ projectId, runtimeError, onDismiss, onFix }: PreviewPanelProps) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(() => loadPreviewUrl(projectId));
  const [isDeploying, setIsDeploying] = useState(false);
  const [iframeKey, setIframeKey] = useState(0);
  const [retryCount, setRetryCount] = useState(0);
  const [autoRetrying, setAutoRetrying] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const retryTimerRef = useRef<number | null>(null);
  const { toast } = useToast();

  const clearRetryTimer = () => {
    if (retryTimerRef.current !== null) {
      window.clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
  };

  const stopAutoRetry = useCallback(() => {
    clearRetryTimer();
    setAutoRetrying(false);
  }, []);

  const startAutoRetry = useCallback(() => {
    clearRetryTimer();
    setRetryCount(0);
    setAutoRetrying(true);
  }, []);

  // Reload the stored URL when switching projects so one project's
  // preview is never framed inside another project.
  useEffect(() => {
    stopAutoRetry();
    setPreviewUrl(loadPreviewUrl(projectId));
  }, [projectId, stopAutoRetry]);

  // Store previewUrl under the per-project key when it changes
  useEffect(() => {
    if (previewUrl && isPreviewUrlForProject(previewUrl, projectId)) {
      localStorage.setItem(getPreviewUrlKey(projectId), previewUrl);
    }
  }, [previewUrl, projectId]);

  // Bounded blind-retry schedule: the preview is cross-origin, so its HTTP
  // status can't be read. After a deploy the pod needs minutes for
  // npm install + vite boot, so remount the iframe a few times, then stop.
  useEffect(() => {
    if (!autoRetrying) return;
    if (retryCount >= RETRY_DELAYS_MS.length) {
      setAutoRetrying(false);
      return;
    }
    retryTimerRef.current = window.setTimeout(() => {
      setIframeKey((key) => key + 1);
      setRetryCount((count) => count + 1);
    }, RETRY_DELAYS_MS[retryCount]);
    return clearRetryTimer;
  }, [autoRetrying, retryCount]);

  // Never leak timers across unmounts
  useEffect(() => clearRetryTimer, []);

  const handleDeploy = async () => {
    setIsDeploying(true);

    try {
      const response = await api.deploy(projectId);
      setPreviewUrl(response.previewUrl);
      startAutoRetry();
      toast({
        title: "Deployment successful",
        description: "Preview is starting — it reloads automatically",
      });
    } catch (error) {
      toast({
        title: "Deployment failed",
        description: error instanceof Error ? error.message : "Something went wrong",
        variant: "destructive",
      });
    } finally {
      setIsDeploying(false);
    }
  };

  const reloadIframe = () => setIframeKey((key) => key + 1);

  const handleRefresh = () => {
    stopAutoRetry();
    if (iframeRef.current) {
      iframeRef.current.src = previewUrl ?? "";
    } else {
      reloadIframe();
    }
  };

  return (
    <div className="flex flex-col h-full min-h-0 min-w-0 bg-background">
      {/* URL Bar */}
      <div className="min-h-[56px] md:h-12 shrink-0 flex items-center gap-1 md:gap-2 px-2 md:px-3 py-2 md:py-0 border-b border-border/50 bg-panel">
        <div className="flex items-center gap-1 shrink-0">
          <Button
            variant="ghost"
            size="icon"
            onClick={handleRefresh}
            disabled={!previewUrl}
            aria-label="Refresh preview"
            className="h-11 w-11 md:h-7 md:w-7 text-muted-foreground hover:text-foreground"
          >
            <RefreshCw className="w-4 h-4 md:w-3.5 md:h-3.5" />
          </Button>
        </div>

        <div className="flex-1 flex items-center min-w-0 min-h-[44px] md:h-8 px-3 rounded-md bg-muted/50 text-sm text-muted-foreground">
          <Globe className="w-3.5 h-3.5 mr-2 shrink-0" />
          <span className="truncate">
            {previewUrl || "Click 'Run Preview' to deploy"}
          </span>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          {previewUrl && (
            <Button
              variant="ghost"
              size="icon"
              onClick={() => window.open(previewUrl, "_blank")}
              aria-label="Open preview in new tab"
              className="h-11 w-11 md:h-7 md:w-7 text-muted-foreground hover:text-foreground"
            >
              <ExternalLink className="w-4 h-4 md:w-3.5 md:h-3.5" />
            </Button>
          )}
          <Button
            onClick={handleDeploy}
            disabled={isDeploying}
            size="sm"
            className="min-h-[44px] md:min-h-0 md:h-7 px-3 bg-primary hover:bg-primary/90 text-xs font-medium"
          >
            {isDeploying ? (
              <>
                <Loader2 className="w-3 h-3 mr-1.5 animate-spin" />
                Deploying
              </>
            ) : (
              <>
                <Play className="w-3 h-3 mr-1.5" />
                Run Preview
              </>
            )}
          </Button>
        </div>
      </div>

      {/* Preview Area */}
      <div className="flex-1 min-h-0 bg-[#1a1a1a] relative">
        {autoRetrying && (
          <div className="absolute top-2 left-1/2 -translate-x-1/2 z-10 flex items-center gap-2 px-3 py-2 min-h-[44px] rounded-full bg-card/95 border border-border/50 shadow-lg text-xs text-muted-foreground max-w-[calc(100vw-2rem)] whitespace-nowrap overflow-hidden">
            <Loader2 className="w-3 h-3 animate-spin text-primary" />
            <span>
              Starting preview… retry {Math.min(retryCount + 1, RETRY_DELAYS_MS.length)}/{RETRY_DELAYS_MS.length}
            </span>
            <button
              onClick={reloadIframe}
              className="font-medium text-primary hover:underline"
            >
              Refresh now
            </button>
            <button
              onClick={stopAutoRetry}
              className="hover:text-foreground"
              aria-label="Stop automatic retries"
            >
              <Square className="w-3 h-3" />
            </button>
          </div>
        )}
        {previewUrl ? (
          <iframe
            key={iframeKey}
            ref={iframeRef}
            src={previewUrl}
            className="w-full h-full border-0"
            title="Preview"
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
          />
        ) : (
          <div className="flex flex-col items-center justify-center h-full text-center p-8">
            <div className="w-16 h-16 rounded-xl bg-muted/20 flex items-center justify-center mb-4">
              <Globe className="w-8 h-8 text-muted-foreground/50" />
            </div>
            <p className="text-sm text-muted-foreground">
              No preview available yet
            </p>
          </div>
        )}
      </div>

      {/* Error Alert Overlay - Inside the Preview Panel */}
      <RuntimeErrorAlert
        error={runtimeError}
        onDismiss={onDismiss}
        onFix={onFix}
      />
    </div>
  );
}
