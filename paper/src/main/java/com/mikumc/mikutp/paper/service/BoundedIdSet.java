package com.mikumc.mikutp.paper.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A set of recently-seen identifiers with a hard size cap. Idempotency guards
 * need to remember what they have already handled, but an unbounded set would
 * grow with traffic; this keeps the newest entries and evicts the oldest.
 *
 * <p>Practical correctness: every id guarded here belongs to a request or event
 * that is only replayable within the configured request/sync window, which is
 * far shorter than the time it takes to push {@code MAX_SIZE} ids through the
 * set. Clearing the whole set (as an eviction strategy) would instead forget
 * <em>recent</em> ids and re-open exactly the replay window it exists to close.
 */
final class BoundedIdSet {

    private static final int MAX_SIZE = 4096;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> order = new LinkedHashMap<>();

    boolean add(String id) {
        synchronized (order) {
            if (seen.contains(id)) {
                return false;
            }
            Boolean previous = order.put(id, Boolean.TRUE);
            if (previous == null) {
                seen.add(id);
            }
            while (order.size() > MAX_SIZE) {
                String oldest = order.keySet().iterator().next();
                order.remove(oldest);
                seen.remove(oldest);
            }
            return true;
        }
    }

    boolean contains(String id) {
        return seen.contains(id);
    }
}
