package com.mikumc.mikutp.paper.service;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * <p>Membership and eviction order live in one map behind one lock. Keeping a
 * second mirror set would let {@link #contains} observe an id as absent between
 * the two writes and process the same event twice.
 */
final class BoundedIdSet {

    private static final int MAX_SIZE = 4096;

    private final Map<String, Boolean> order = new LinkedHashMap<>();

    /** Returns true when {@code id} had not been seen before. */
    boolean add(String id) {
        synchronized (order) {
            if (order.putIfAbsent(id, Boolean.TRUE) != null) {
                return false;
            }
            while (order.size() > MAX_SIZE) {
                String oldest = order.keySet().iterator().next();
                order.remove(oldest);
            }
            return true;
        }
    }

    boolean contains(String id) {
        synchronized (order) {
            return order.containsKey(id);
        }
    }
}
