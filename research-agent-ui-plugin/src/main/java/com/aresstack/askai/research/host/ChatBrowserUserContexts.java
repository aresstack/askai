package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.playwright.PlaywrightUserContextPool;
import com.aresstack.askai.browser.playwright.WorkerBrowsingContext;
import com.aresstack.askai.browser.playwright.WorkerUserContext;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.runtime.browser.BrowserCapacityException;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextLease;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextPool;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextRequest;
import com.aresstack.askai.research.runtime.browser.BrowsingContextLease;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The research session's {@link BrowserUserContextPool}: worker user contexts of THIS chat from the plugin's
 * shared worker browser pool, each tab behind the session's own browser-bridge tool contract. That is the
 * point where the isolated browsers rejoin the one research process: every page a worker opens, reads or
 * follows is recorded into the session's {@link CaptureStore} with a {@code capture_id}, exactly like a page
 * of the session browser, so source acceptance, scope checks, duplicate detection and the user's approvals
 * stay the session's — there is no second source management. Closing it (session end) ends every lease.
 */
final class ChatBrowserUserContexts implements BrowserUserContextPool, AutoCloseable {

    private final PlaywrightUserContextPool.Chat chat;
    private final CaptureStore captures;
    private final WorkerUserContextStateStore states;
    private final String sessionKey;
    private final Set<Lease> open = new LinkedHashSet<Lease>();
    private volatile boolean closed;

    ChatBrowserUserContexts(PlaywrightUserContextPool.Chat chat, CaptureStore captures,
                            WorkerUserContextStateStore states, String sessionKey) {
        this.chat = chat;
        this.captures = captures;
        this.states = states;
        this.sessionKey = sessionKey;
    }

    @Override
    public BrowserUserContextLease acquire(BrowserUserContextRequest request)
            throws BrowserCapacityException, InterruptedException {
        if (closed) {
            throw new BrowserCapacityException("The research session is closed.");
        }
        String state = request.stateKey() == null ? null : states.load(request.stateKey());
        WorkerUserContext userContext;
        try {
            userContext = chat.acquire(request.toString(), state, request.acquireTimeoutMillis());
        } catch (BrowserException unavailable) {
            throw new BrowserCapacityException(unavailable.getMessage(), unavailable);
        }
        Lease lease = new Lease(userContext, request);
        synchronized (this) {
            if (closed) {
                lease.close();
                throw new BrowserCapacityException("The research session is closed.");
            }
            open.add(lease);
        }
        return lease;
    }

    @Override
    public int capacity() {
        return closed ? 0 : chat.pool().capacity();
    }

    /** Leases currently held in this chat (diagnostics, tests). */
    synchronized int openLeases() {
        return open.size();
    }

    @Override
    public void close() {
        List<Lease> leases;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            leases = new ArrayList<Lease>(open);
        }
        for (Lease lease : leases) {
            lease.close();
        }
        chat.close();
    }

    private synchronized void forget(Lease lease) {
        open.remove(lease);
    }

    /** One tab behind the bridge contract (capture_id convention, session capture store). */
    private final class Tab implements BrowsingContextLease {
        final WorkerBrowsingContext browsingContext;
        final BrowserBridgeEndpoint bridge;
        final ToolInvoker tools;

        Tab(WorkerBrowsingContext browsingContext) {
            this.browsingContext = browsingContext;
            this.bridge = new BrowserBridgeEndpoint(null, new WorkerTabRuntimePort(browsingContext), captures,
                    sessionKey + ".worker", 0L);
            this.bridge.openInProcess();
            this.tools = new LostAwareInvoker(bridge.toolInvoker());
        }

        public String browsingContextId() {
            return browsingContext.id();
        }

        public ToolInvoker tools() {
            return tools;
        }

        public void close() {
            bridge.close();
            browsingContext.close();
        }
    }

    private final class Lease implements BrowserUserContextLease {
        final WorkerUserContext userContext;
        final BrowserUserContextRequest request;
        final Tab first;
        final List<Tab> tabs = new ArrayList<Tab>();
        volatile boolean leaseClosed;

        Lease(WorkerUserContext userContext, BrowserUserContextRequest request) {
            this.userContext = userContext;
            this.request = request;
            this.first = new Tab(userContext.firstBrowsingContext());
            tabs.add(first);
        }

        public String userContextId() {
            return userContext.id();
        }

        public BrowserUserContextRequest request() {
            return request;
        }

        public ToolInvoker browser() {
            return first.tools;
        }

        public BrowsingContextLease openBrowsingContext() throws BrowserCapacityException {
            try {
                Tab tab = new Tab(userContext.openBrowsingContext());
                synchronized (this) {
                    tabs.add(tab);
                }
                return tab;
            } catch (BrowserException refused) {
                throw new BrowserCapacityException(refused.getMessage(), refused);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BrowserCapacityException("Interrupted while opening a browsing context.", interrupted);
            }
        }

        public boolean isOpen() {
            return !leaseClosed && userContext.isOpen();
        }

        public void close() {
            List<Tab> open;
            synchronized (this) {
                if (leaseClosed) {
                    return;
                }
                leaseClosed = true;
                open = new ArrayList<Tab>(tabs);
            }
            if (request.stateKey() != null && userContext.isOpen()) {
                saveState();
            }
            for (Tab tab : open) {
                tab.bridge.close();
            }
            userContext.close();
            forget(this);
        }

        private void saveState() {
            boolean interrupted = Thread.interrupted(); // a cancelled worker still gets its sign-in saved
            try {
                states.save(request.stateKey(), userContext.exportState());
            } catch (BrowserException | IOException | InterruptedException failed) {
                System.err.println("[browser-workers] could not save the state of " + userContext.id() + ": "
                        + failed.getMessage());
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** A lost user context is the endpoint going away for the worker, not one tool's failure. */
    private static final class LostAwareInvoker implements ToolInvoker {
        private final ToolInvoker delegate;

        LostAwareInvoker(ToolInvoker delegate) {
            this.delegate = delegate;
        }

        public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
            try {
                return delegate.call(toolName, args);
            } catch (ToolFailure failure) {
                if (failure.getMessage() != null
                        && failure.getMessage().contains(PlaywrightUserContextPool.USER_CONTEXT_LOST)) {
                    throw new EndpointUnavailable(failure.getMessage());
                }
                throw failure;
            }
        }

        @Override
        public List<String> toolNames() {
            return delegate.toolNames();
        }
    }
}
