package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The page browser of the research team (plan T5): ONE user context of this chat's worker browsers, and in it one
 * tab per page worker. Every worker navigates only its own tab; all tabs share the window, the site leases of the
 * worker browsers (two tabs never on the same site at the same time) and their engine refusal (a tab never opens a
 * search engine; searching is the search browser's job). The user context is opened with the first worker and
 * closed when the last one has given its tab back.
 *
 * <p>Thread-safe. The number of tabs is bounded by the "Tabs/user context" setting of the worker browsers.</p>
 */
public final class TeamTabs {

    /** One worker's tab; {@link #close()} gives it back. */
    public final class Tab implements AutoCloseable {
        private final ToolInvoker tools;
        private final BrowsingContextLease extra;
        /** The user context this tab belongs to: a returned first tab frees only its own context's first tab. */
        private final BrowserUserContextLease owner;
        private boolean closed;

        private Tab(ToolInvoker tools, BrowsingContextLease extra, BrowserUserContextLease owner) {
            this.tools = tools;
            this.extra = extra;
            this.owner = owner;
        }

        /** The {@code web_*} tools of this tab. */
        public ToolInvoker tools() {
            return tools;
        }

        /** The user context the tab lives in (diagnostics). */
        public String userContextId() {
            return userContextIdOf();
        }

        @Override
        public void close() {
            synchronized (TeamTabs.this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            release(this);
        }
    }

    /** The worker id the team's user context carries (diagnostics and per-chat bookkeeping in the pool). */
    public static final String WORKER_ID = "page-team";

    private final BrowserUserContextPool pool;
    private BrowserUserContextLease lease;
    private boolean firstTabFree;
    /** A worker is opening the team's user context right now; the others wait for it. */
    private boolean opening;
    private int open;

    public TeamTabs(BrowserUserContextPool pool) {
        this.pool = pool;
    }

    /** Whether this chat has worker browsers at all. */
    public boolean available() {
        return UserContextWebAcquisition.browserSlots(pool) > 0;
    }

    /**
     * A tab of its own for one page worker; opens the team's user context first when none is open (waits at most
     * the worker browsers' wait setting).
     *
     * @throws BrowserCapacityException no worker browser came free, or the tab limit is reached
     * @throws InterruptedException     the waiting worker was cancelled
     */
    public Tab acquire(String taskId) throws BrowserCapacityException, InterruptedException {
        BrowserUserContextLease current;
        boolean opener = false;
        synchronized (this) {
            // one worker opens the user context; the others wait here, interruptibly, instead of on the monitor
            while (opening) {
                wait();
            }
            if (lease != null && !lease.isOpen()) {
                lease = null; // the worker browser was lost: the next worker opens a fresh user context
            }
            if (lease == null) {
                opening = true;
                opener = true;
            }
        }
        if (opener) {
            BrowserUserContextLease opened = null;
            try {
                opened = pool.acquire(BrowserUserContextRequest.of(WORKER_ID, taskId));
            } finally {
                synchronized (this) {
                    if (opened != null) {
                        lease = opened;
                        firstTabFree = true;
                    }
                    opening = false;
                    notifyAll();
                }
            }
        }
        synchronized (this) {
            if (lease == null) {
                throw new BrowserCapacityException("the team's user context is not open");
            }
            current = lease;
            open++; // counted before the tab opens, so a worker giving its tab back meanwhile never closes the context
            if (firstTabFree) {
                firstTabFree = false;
                return new Tab(current.browser(), null, current);
            }
        }
        BrowsingContextLease tab;
        try {
            tab = current.openBrowsingContext();
        } catch (BrowserCapacityException | RuntimeException failed) {
            giveBack(); // the slot counted above
            throw failed;
        }
        return new Tab(tab.tools(), tab, current);
    }

    /**
     * Up to {@code max} tabs for one search: waits for the first like {@link #acquire}, then takes as many more as
     * the tab limit lets it have right now, never waiting for them.
     */
    public java.util.List<Tab> acquireUpTo(String taskId, int max) throws BrowserCapacityException,
            InterruptedException {
        java.util.List<Tab> tabs = new java.util.ArrayList<Tab>();
        tabs.add(acquire(taskId));
        while (tabs.size() < max) {
            try {
                tabs.add(acquire(taskId + "#" + (tabs.size() + 1)));
            } catch (BrowserCapacityException full) {
                break; // the other searches hold the rest; this search reads with what it has
            }
        }
        return tabs;
    }

    private void giveBack() {
        BrowserUserContextLease toClose = null;
        synchronized (this) {
            open = Math.max(0, open - 1);
            if (open == 0 && lease != null) {
                toClose = lease;
                lease = null;
            }
        }
        if (toClose != null) {
            toClose.close();
        }
    }

    private void release(Tab tab) {
        if (tab.extra != null) {
            tab.extra.close();
        } else {
            synchronized (this) {
                if (tab.owner == lease) {
                    firstTabFree = true; // a first tab of a lost context frees nothing in the new one
                }
            }
        }
        giveBack();
    }

    private synchronized String userContextIdOf() {
        return lease == null ? "" : lease.userContextId();
    }

    /** How many tabs are given out right now (tests, diagnostics). */
    public synchronized int openTabs() {
        return open;
    }
}
