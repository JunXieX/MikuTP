package com.mikumc.mikutp.common.sync;

import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Redis-backed bus. Local SQLite stays the source of truth; this class only
 * moves change events and coordination state:
 *
 * <ul>
 *   <li>events: appended to a Redis Stream through the local outbox, consumed
 *       by every backend via its own consumer group (XREADGROUP + XACK) — one
 *       group per server makes delivery a broadcast, and each group keeps its
 *       own read position so a backend that was down replays everything it
 *       missed on reconnect;</li>
 *   <li>presence: per-player keys with short TTL, refreshed by heartbeats;</li>
 *   <li>pending teleports: per-player keys consumed with GETDEL.</li>
 * </ul>
 */
public final class RedisSyncBus implements SyncBus {

    private static final Gson GSON = new Gson();
    private static final String STREAM = "mikutp:sync";
    private static final String PENDING_KEY = "mikutp:pending:";
    private static final String PRESENCE_KEY = "mikutp:player:";
    private static final String MAILBOX_KEY = "mikutp:mailbox:";
    /** Poll intervals of the outbox publisher: fast when busy, backing off when idle. */
    private static final long ACTIVE_SLEEP_MS = 100;
    private static final long IDLE_SLEEP_MS = 1000;

    private final JedisPool pool;
    /**
     * One consumer group PER SERVER (mikutp-g:&lt;serverId&gt;): groups are the
     * broadcast unit of Redis Streams — every group receives every entry, and
     * each group keeps its own read position so a server that was offline
     * replays everything it missed. Within the group this server is a single
     * consumer named after itself.
     */
    private final String group;
    private final String consumer;
    private final long streamMaxLength;
    private final OutboxStore outbox;
    private final Thread publisherThread;
    private final Thread consumerThread;
    private volatile boolean running;

    public RedisSyncBus(String host, int port, String password, int database, boolean useSsl,
                        String serverId, long streamMaxLength, OutboxStore outbox) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(4);
        config.setMaxWait(Duration.ofSeconds(3));
        String pwd = password == null || password.isBlank() ? null : password;
        this.pool = new JedisPool(config, host, port, 5000, pwd, database, useSsl);
        this.group = "mikutp-g:" + serverId;
        this.consumer = serverId;
        this.streamMaxLength = streamMaxLength;
        this.outbox = outbox;
        this.publisherThread = new Thread(this::publishLoop, "mikutp-sync-publisher");
        this.consumerThread = new Thread(this::consumeLoop, "mikutp-sync-consumer");
        this.publisherThread.setDaemon(true);
        this.consumerThread.setDaemon(true);
    }

    @Override
    public void start(Consumer<SyncEvent> applier) {
        running = true;
        this.applier = applier;
        ensureGroup();
        publisherThread.start();
        consumerThread.start();
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        } catch (Exception e) {
            // Data is safe: events queue in the local outbox and sync once Redis
            // is reachable; the loops below keep retrying forever.
            org.slf4j.LoggerFactory.getLogger(RedisSyncBus.class)
                    .warn("Redis unreachable at startup ({}); cross-server sync will start automatically once it is reachable.",
                            e.getMessage());
        }
    }

    private volatile Consumer<SyncEvent> applier = event -> {
    };
    private volatile boolean groupReady;

    @Override
    public void publish(SyncEvent event) {
        outbox.append(GSON.toJson(event));
    }

    @Override
    public boolean crossServer() {
        return true;
    }

    // ------------------------------------------------------------------ workers

    private void ensureGroup() {
        try (Jedis jedis = pool.getResource()) {
            jedis.xgroupCreate(STREAM, group, new StreamEntryID("0-0"), true);
            groupReady = true;
        } catch (JedisDataException e) {
            // BUSYGROUP: the group already exists, which is fine.
            if (String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                groupReady = true;
            }
        } catch (Exception ignored) {
            // Redis unreachable: retried from the consumer loop.
        }
    }

    private void publishLoop() {
        // Back off while idle so the outbox poll stays cheap; snap back to fast
        // polling as soon as traffic appears.
        long idleSleep = ACTIVE_SLEEP_MS;
        while (running) {
            List<SyncBus.OutboxRow> rows = List.of();
            try {
                rows = outbox.take(128);
            } catch (Exception e) {
                sleep(3000);
            }
            if (rows.isEmpty()) {
                sleep(idleSleep);
                idleSleep = Math.min(IDLE_SLEEP_MS, idleSleep * 2);
                continue;
            }
            idleSleep = ACTIVE_SLEEP_MS;
            List<Long> sent = new ArrayList<>();
            try (Jedis jedis = pool.getResource()) {
                var pipe = jedis.pipelined();
                for (SyncBus.OutboxRow row : rows) {
                    pipe.xadd(STREAM,
                            XAddParams.xAddParams().maxLen(streamMaxLength).approximateTrimming(),
                            Map.of("data", row.payload()));
                    sent.add(row.seq());
                }
                pipe.sync();
            } catch (Exception e) {
                sleep(3000);
                continue;
            }
            try {
                outbox.remove(sent);
            } catch (Exception ignored) {
                // Rows stay in the outbox and are re-published; consumers apply idempotently.
            }
        }
    }

    private void consumeLoop() {
        while (running) {
            if (!groupReady) {
                ensureGroup();
                if (!groupReady) {
                    sleep(3000);
                    continue;
                }
            }
            List<StreamEntry> entries;
            try (Jedis jedis = pool.getResource()) {
                var read = jedis.xreadGroup(group, consumer,
                        XReadGroupParams.xReadGroupParams().block(2000).count(64),
                        Map.of(STREAM, new StreamEntryID(">")));
                entries = read == null || read.isEmpty()
                        ? List.of()
                        : read.get(0).getValue();
            } catch (Exception e) {
                // A deleted/recreated group surfaces as NOGROUP here; recreate it.
                if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                    groupReady = false;
                }
                sleep(3000);
                continue;
            }
            List<StreamEntryID> acks = new ArrayList<>();
            for (StreamEntry entry : entries) {
                try {
                    String payload = entry.getFields().get("data");
                    if (payload != null) {
                        SyncEvent event = GSON.fromJson(payload, SyncEvent.class);
                        if (event != null && event.type != null) {
                            applier.accept(event);
                        }
                    }
                    acks.add(entry.getID());
                } catch (Exception ignored) {
                    // Never let one bad event kill the consumer loop; the entry is
                    // still acked so it is not redelivered forever.
                }
            }
            ack(acks);
        }
    }

    private void ack(List<StreamEntryID> entryIds) {
        if (entryIds.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.xack(STREAM, group, entryIds.toArray(StreamEntryID[]::new));
        } catch (Exception ignored) {
            // Unacked entries are redelivered; appliers are idempotent.
        }
    }

    // ------------------------------------------------------------------ coordination state

    @Override
    public String presenceGet(UUID player) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(PRESENCE_KEY + player);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void presencePut(UUID player, String serverId, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(PRESENCE_KEY + player, ttlSeconds, serverId);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void presencePutAll(Map<UUID, String> players, int ttlSeconds) {
        if (players.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            var pipe = jedis.pipelined();
            for (Map.Entry<UUID, String> entry : players.entrySet()) {
                pipe.setex(PRESENCE_KEY + entry.getKey(), ttlSeconds, entry.getValue());
            }
            pipe.sync();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void presenceForget(UUID player) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(PRESENCE_KEY + player);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void pendingPut(String playerUuid, String payloadJson, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(PENDING_KEY + playerUuid, ttlSeconds, payloadJson);
        } catch (Exception ignored) {
        }
    }

    @Override
    public String pendingTake(String playerUuid) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getDel(PENDING_KEY + playerUuid);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void mailboxAdd(String playerUuid, String payloadJson, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            String key = MAILBOX_KEY + playerUuid;
            jedis.rpush(key, payloadJson);
            jedis.expire(key, ttlSeconds);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void mailboxRemove(String playerUuid, String payloadJson) {
        try (Jedis jedis = pool.getResource()) {
            jedis.lrem(MAILBOX_KEY + playerUuid, 1, payloadJson);
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<String> mailboxTake(String playerUuid) {
        // RENAME first: reading the original key directly could race with a
        // concurrent mailboxAdd (a request arriving exactly as the player joins).
        String key = MAILBOX_KEY + playerUuid;
        String taking = key + ":taking";
        try (Jedis jedis = pool.getResource()) {
            try {
                jedis.rename(key, taking);
            } catch (JedisDataException e) {
                return List.of(); // no mailbox for this player
            }
            List<String> payloads = jedis.lrange(taking, 0, -1);
            jedis.del(taking);
            return payloads;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            publisherThread.join(3000);
            consumerThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
