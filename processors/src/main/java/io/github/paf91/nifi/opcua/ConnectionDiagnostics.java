/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;

/** Bounded metadata only: never stores passwords, private keys or sensor values. */
final class ConnectionDiagnostics {
    private final java.util.function.UnaryOperator<String> redact;

    ConnectionDiagnostics() {
        this(java.util.function.UnaryOperator.identity());
    }

    ConnectionDiagnostics(java.util.function.UnaryOperator<String> redact) {
        this.redact = redact;
    }

    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final List<Map<String, Object>> items = new ArrayList<>();
    private String stage = "Idle";
    private boolean sessionActive;
    private int sessionActivations;
    private int itemCount;
    private Instant lastKeepAlive;
    private Instant lastNotification;
    private Instant lastGoodValue;
    private long notifications;
    private NodeId firstSession;
    private boolean settingsMayBeStale;

    synchronized void stage(String value) {
        stage = value;
    }

    synchronized void serverCertificateValidation(ServerCertificateValidation validation) {
        metadata.put("serverCertificateValidation", validation.value());
    }

    synchronized boolean sessionActive(NodeId id) {
        sessionActive = true;
        sessionActivations++;
        if (firstSession == null) {
            firstSession = id;
            return false;
        }
        boolean changed = !firstSession.equals(id);
        if (changed) settingsMayBeStale = true;
        return changed;
    }

    synchronized void sessionInactive() {
        sessionActive = false;
    }

    synchronized void keepAlive() {
        lastKeepAlive = Instant.now();
    }

    synchronized void notification(DataValue value) {
        notifications++;
        lastNotification = Instant.now();
        if (value.getStatusCode() != null && value.getStatusCode().isGood())
            lastGoodValue = lastNotification;
    }

    synchronized void endpoints(List<EndpointDescription> endpoints) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (EndpointDescription endpoint : endpoints) {
            if (endpoint != null && choices.size() < 50) choices.add(describe(endpoint));
        }
        metadata.put("advertisedEndpointCount", endpoints.size());
        metadata.put("advertisedEndpoints", choices);
    }

    synchronized void selected(EndpointDescription endpoint) {
        metadata.put("selectedEndpoint", describe(endpoint));
        try {
            if (endpoint.getServerCertificate() != null
                    && !endpoint.getServerCertificate().isNull()
                    && endpoint.getServerCertificate().length() > 0) {
                X509Certificate certificate =
                        (X509Certificate)
                                CertificateFactory.getInstance("X.509")
                                        .generateCertificate(
                                                new ByteArrayInputStream(
                                                        endpoint.getServerCertificate().bytes()));
                certificate("serverCertificate", certificate);
            }
        } catch (CertificateException e) {
            metadata.put("serverCertificate", "Could not decode advertised certificate");
        }
    }

    synchronized void certificate(String label, X509Certificate certificate) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("subject", clean(certificate.getSubjectX500Principal().getName()));
        detail.put("expiresAt", certificate.getNotAfter().toInstant().toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder fingerprint = new StringBuilder();
            for (byte b : hash) fingerprint.append(String.format("%02x", b & 255));
            detail.put("sha256", fingerprint.toString());
        } catch (Exception e) {
            detail.put("sha256", "unavailable");
        }
        metadata.put(label, detail);
    }

    synchronized void namespaces(String[] namespaces) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < Math.min(128, namespaces.length); i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            entry.put("uri", clean(namespaces[i]));
            entries.add(entry);
        }
        metadata.put("namespaceCount", namespaces.length);
        metadata.put("namespaces", entries);
    }

    synchronized void namespaceUnavailable() {
        metadata.put("namespaceRead", "Unavailable; numeric NodeIds do not require URI resolution");
    }

    synchronized void subscription(double requested, double revised) {
        metadata.put("requestedPublishingIntervalMs", requested);
        metadata.put("revisedPublishingIntervalMs", revised);
    }

    synchronized void item(TagDefinition tag, double sampling, long queue) {
        itemCount++;
        if (items.size() >= 100) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tag", clean(tag.alias()));
        item.put("configuredNodeId", clean(tag.address().text()));
        item.put("resolvedNodeId", clean(tag.nodeId().toParseableString()));
        item.put("requestedSamplingIntervalMs", tag.samplingIntervalMs());
        item.put("revisedSamplingIntervalMs", sampling);
        item.put("requestedQueueSize", tag.queueSize());
        item.put("revisedQueueSize", queue);
        if (tag.filter() != null) {
            item.put(
                    "deadbandType",
                    tag.filter().getDeadbandType().intValue() == 1 ? "Absolute" : "Percent");
            item.put("deadbandValue", tag.filter().getDeadbandValue());
        }
        items.add(item);
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.put("stage", stage);
        result.put("sessionActive", sessionActive);
        result.put("sessionActivations", sessionActivations);
        result.put("settingsMayBeStale", settingsMayBeStale);
        result.put("lastKeepAliveReceivedAt", timestamp(lastKeepAlive));
        result.put("lastNotificationReceivedAt", timestamp(lastNotification));
        result.put("lastGoodValueReceivedAt", timestamp(lastGoodValue));
        result.put("notificationsReceived", notifications);
        result.put("monitoredItemCount", itemCount);
        result.put("monitoredItems", new ArrayList<>(items));
        result.put("itemDetailsTruncated", itemCount > items.size());
        return result;
    }

    private static String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }

    String clean(String value) {
        if (value == null) return null;
        String cleaned = redact.apply(value).replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return cleaned.length() > 512 ? cleaned.substring(0, 512) + "…" : cleaned;
    }

    private Map<String, Object> describe(EndpointDescription endpoint) {
        Map<String, Object> choice = new LinkedHashMap<>();
        try {
            URI uri = URI.create(endpoint.getEndpointUrl());
            choice.put(
                    "url",
                    clean(
                            new URI(
                                            uri.getScheme(),
                                            null,
                                            uri.getHost(),
                                            uri.getPort(),
                                            uri.getPath(),
                                            null,
                                            null)
                                    .toString()));
        } catch (Exception e) {
            choice.put("url", "Invalid advertised URL");
        }
        choice.put("policy", clean(endpoint.getSecurityPolicyUri()));
        choice.put("mode", String.valueOf(endpoint.getSecurityMode()));
        List<String> tokens = new ArrayList<>();
        if (endpoint.getUserIdentityTokens() != null) {
            for (UserTokenPolicy token : endpoint.getUserIdentityTokens()) {
                if (token != null && tokens.size() < 20)
                    tokens.add(
                            token.getTokenType()
                                    + " ("
                                    + clean(token.getSecurityPolicyUri())
                                    + ")");
            }
        }
        choice.put("authentication", tokens);
        return choice;
    }
}
