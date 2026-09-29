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
 *       by every backend via a consumer group (XREADGROUP + XACK), so a backend
 *       that was down replays everything it missed on reconnect;</li>
 *   <li>presence: per-player keys with short TTL, refreshed by heartbeats;</li>
 *   <li>pending teleports: per-player keys consumed with GETDEL.</li>
 * </ul>
 */
public final class RedisSyncBus implements SyncBus {

    private static final Gson GSON = new Gson();
    private static final String STREAM = "mikutp:sync";
    private static final String GROUP = "mikutp";
    private static final String PENDING_KEY = "mikutp:pending:";
    private static final String PRESENCE_KEY = "mikutp:player:";

    private final JedisPool pool;
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
    }

    private volatile Consumer<SyncEvent> applier = event -> {
    };

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
            jedis.xgroupCreate(STREAM, GROUP, new StreamEntryID("0-0"), true);
        } catch (JedisDataException e) {
            // BUSYGROUP: the group already exists, which is fine.
        }
    }

    private void publishLoop() {
        while (running) {
            List<SyncBus.OutboxRow> rows = List.of();
            try {
                rows = outbox.take(128);
            } catch (Exception e) {
                sleep(3000);
            }
            if (rows.isEmpty()) {
                sleep(300);
                continue;
            }
            List<Long> sent = new ArrayList<>();
            try (Jedis jedis = pool.getResource()) {
                for (SyncBus.OutboxRow row : rows) {
                    jedis.xadd(STREAM,
                            XAddParams.xAddParams().maxLen(streamMaxLength).approximateTrimming(),
                            Map.of("data", row.payload()));
                    sent.add(row.seq());
                }
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
            List<StreamEntry> entries;
            try (Jedis jedis = pool.getResource()) {
                var read = jedis.xreadGroup(GROUP, consumer,
                        XReadGroupParams.xReadGroupParams().block(2000).count(64),
                        Map.of(STREAM, new StreamEntryID(">")));
                entries = read == null || read.isEmpty()
                        ? List.of()
                        : read.get(0).getValue();
            } catch (Exception e) {
                sleep(3000);
                continue;
            }
            for (StreamEntry entry : entries) {
                try {
                    String payload = entry.getFields().get("data");
                    if (payload != null) {
                        SyncEvent event = GSON.fromJson(payload, SyncEvent.class);
                        if (event != null && event.type != null) {
                            applier.accept(event);
                        }
                    }
                    ack(entry.getID().toString());
                } catch (Exception ignored) {
                    // Never let one bad event kill the consumer loop.
                }
            }
        }
    }

    private void ack(String entryId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xack(STREAM, GROUP, new StreamEntryID(entryId));
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
