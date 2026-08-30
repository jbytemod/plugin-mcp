package de.xbrowniecodez.jbytemod.mcp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpActivityLog {
    private static final int MAX_ACTIVITIES = 250;

    private final Deque<Activity> activities = new ArrayDeque<>();
    private final Map<String, MutableClient> clients = new LinkedHashMap<>();

    synchronized String observeClient(String key, String fallbackName, String reportedName, String reportedVersion) {
        Instant now = Instant.now();
        MutableClient client = clients.computeIfAbsent(key,
                ignored -> new MutableClient(fallbackName, "", now));
        if (reportedName != null && !reportedName.isBlank()) {
            client.name = reportedName;
        }
        if (reportedVersion != null && !reportedVersion.isBlank()) {
            client.version = reportedVersion;
        }
        client.lastSeen = now;
        client.requests++;
        return client.displayName();
    }

    synchronized void record(String client, String action, String result, long durationMillis) {
        activities.addFirst(new Activity(Instant.now(), client, action, result, durationMillis));
        while (activities.size() > MAX_ACTIVITIES) {
            activities.removeLast();
        }
    }

    synchronized List<Activity> activities() {
        return new ArrayList<>(activities);
    }

    synchronized List<Client> clients() {
        List<Client> snapshot = new ArrayList<>();
        for (MutableClient client : clients.values()) {
            snapshot.add(new Client(client.name, client.version, client.lastSeen, client.requests));
        }
        return snapshot;
    }

    synchronized void clearActivities() {
        activities.clear();
    }

    record Activity(Instant timestamp, String client, String action, String result, long durationMillis) {
    }

    record Client(String name, String version, Instant lastSeen, long requests) {
    }

    private static final class MutableClient {
        private String name;
        private String version;
        private Instant lastSeen;
        private long requests;

        private MutableClient(String name, String version, Instant lastSeen) {
            this.name = name;
            this.version = version;
            this.lastSeen = lastSeen;
        }

        private String displayName() {
            return version.isBlank() ? name : name + " " + version;
        }
    }
}
