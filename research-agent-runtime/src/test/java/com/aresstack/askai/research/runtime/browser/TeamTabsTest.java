package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan T5: one user context for the whole team, one tab per page worker, closed with the last tab. */
public class TeamTabsTest {

    @Test
    public void everyWorkerGetsItsOwnTabInOneUserContext() throws Exception {
        FakePool pool = new FakePool(3);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        TeamTabs.Tab second = tabs.acquire("q2");
        TeamTabs.Tab third = tabs.acquire("q3");
        assertEquals(1, pool.leases.size()); // one user context, not one browser per worker
        assertSame(pool.leases.get(0).firstTab, first.tools());
        assertNotSame(first.tools(), second.tools());
        assertNotSame(second.tools(), third.tools());
        assertEquals(2, pool.leases.get(0).extraTabs);
        assertEquals(3, tabs.openTabs());
        assertEquals("uc-1", second.userContextId());
        second.close();
        second.close(); // idempotent
        assertEquals(1, pool.leases.get(0).closedTabs);
        first.close();
        assertTrue(pool.leases.get(0).open); // a worker still holds a tab
        TeamTabs.Tab again = tabs.acquire("q4");
        assertSame(pool.leases.get(0).firstTab, again.tools()); // the free first tab is used again
        again.close();
        third.close();
        assertFalse(pool.leases.get(0).open); // the last tab closes the user context
        assertEquals(0, tabs.openTabs());
        tabs.acquire("q5");
        assertEquals(2, pool.leases.size()); // the next worker opens a fresh one
    }

    @Test
    public void theTabLimitFailsHonestlyAndKeepsTheCount() throws Exception {
        FakePool pool = new FakePool(1);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        try {
            tabs.acquire("q2");
            fail("the tab limit is reached");
        } catch (BrowserCapacityException expected) {
            // the plan keeps the query pending
        }
        assertEquals(1, tabs.openTabs());
        assertTrue(pool.leases.get(0).open);
        first.close();
        assertFalse(pool.leases.get(0).open);
    }

    @Test
    public void withoutWorkerBrowsersTheTeamIsNotAvailable() {
        assertFalse(new TeamTabs(BrowserUserContextPool.unavailable("none")).available());
        assertTrue(new TeamTabs(new FakePool(2)).available());
    }

    private static ToolInvoker tools() {
        return new ToolInvoker() {
            public String call(String toolName, Map<String, Object> args) {
                return "";
            }
        };
    }

    private static final class FakePool implements BrowserUserContextPool {
        final List<FakeLease> leases = new ArrayList<FakeLease>();
        final int tabsPerContext;

        FakePool(int tabsPerContext) {
            this.tabsPerContext = tabsPerContext;
        }

        public BrowserUserContextLease acquire(BrowserUserContextRequest request) {
            FakeLease lease = new FakeLease("uc-" + (leases.size() + 1), request, tabsPerContext);
            leases.add(lease);
            return lease;
        }

        public int capacity() {
            return 2;
        }
    }

    private static final class FakeLease implements BrowserUserContextLease {
        final String id;
        final BrowserUserContextRequest request;
        final int limit;
        final ToolInvoker firstTab = tools();
        int extraTabs;
        int closedTabs;
        boolean open = true;

        FakeLease(String id, BrowserUserContextRequest request, int limit) {
            this.id = id;
            this.request = request;
            this.limit = limit;
        }

        public String userContextId() {
            return id;
        }

        public BrowserUserContextRequest request() {
            return request;
        }

        public ToolInvoker browser() {
            return firstTab;
        }

        public BrowsingContextLease openBrowsingContext() throws BrowserCapacityException {
            if (1 + extraTabs - closedTabs >= limit) {
                throw new BrowserCapacityException("tab limit reached");
            }
            extraTabs++;
            final ToolInvoker tab = tools();
            final String tabId = id + "-tab-" + extraTabs;
            return new BrowsingContextLease() {
                public String browsingContextId() {
                    return tabId;
                }

                public ToolInvoker tools() {
                    return tab;
                }

                public void close() {
                    closedTabs++;
                }
            };
        }

        public boolean isOpen() {
            return open;
        }

        public void close() {
            open = false;
        }
    }
}
