/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.*;
import org.apache.nifi.annotation.lifecycle.*;
import org.apache.nifi.annotation.notification.*;
import org.apache.nifi.components.*;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.util.*;
import java.util.concurrent.atomic.*;

@Tags({"opcua", "opc", "subscription", "industrial", "iot"})
@CapabilityDescription(
        "Subscribes to OPC UA Value changes with Basic256Sha256 / SignAndEncrypt, a client"
            + " certificate and username/password. Paste a list of NodeIds into Tags, or add"
            + " individual tag properties. Outputs JSON arrays. Requires mutual certificate trust."
            + " Buffers are in memory; overflow drops newest notifications and raises a bulletin.")
@DynamicProperty(
        name = "Tag alias",
        value = "NodeId or JSON with nodeId, samplingIntervalMs, queueSize, discardOldest",
        description =
                "Add one property per monitored tag using the + button. Fictional example:"
                        + " PumpRunning = ns=2;s=Example.Pump.Running. JSON options override the"
                        + " processor defaults.")
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@TriggerSerially
@PrimaryNodeOnly
@DefaultSchedule(period = "100 ms")
@Restricted(
        restrictions = {
            @Restriction(
                    requiredPermission = RequiredPermission.READ_FILESYSTEM,
                    explanation = "Reads client certificates and private keys."),
            @Restriction(
                    requiredPermission = RequiredPermission.WRITE_FILESYSTEM,
                    explanation = "Initializes and maintains the OPC UA PKI trust directory.")
        })
public class SubscribeOpcUa extends AbstractProcessor {
    private static PropertyDescriptor text(
            String name, String description, boolean sensitive, boolean required) {
        return new PropertyDescriptor.Builder()
                .name(name)
                .description(description)
                .required(required)
                .sensitive(sensitive)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();
    }

    private static PropertyDescriptor number(
            String name, String description, String defaultValue, int max) {
        return new PropertyDescriptor.Builder()
                .name(name)
                .description(description)
                .required(true)
                .defaultValue(defaultValue)
                .addValidator(
                        (subject, input, context) -> {
                            boolean valid;
                            try {
                                int n = Integer.parseInt(input);
                                valid = n > 0 && n <= max;
                            } catch (Exception e) {
                                valid = false;
                            }
                            return new ValidationResult.Builder()
                                    .subject(subject)
                                    .valid(valid)
                                    .explanation("Must be an integer between 1 and " + max)
                                    .build();
                        })
                .build();
    }

    public static final PropertyDescriptor ENDPOINT =
            text(
                    "Endpoint URL",
                    "OPC TCP discovery URL, e.g. opc.tcp://server:4840. Selected endpoint must"
                            + " support Basic256Sha256, SignAndEncrypt and username identity.",
                    false,
                    true);
    public static final PropertyDescriptor APPLICATION_URI =
            text(
                    "Application URI",
                    "Optional advanced override. Leave unset to read the client application"
                        + " identity from the URI subject alternative name in your client"
                        + " certificate. This is not the server URL. An override must exactly match"
                        + " the certificate.",
                    false,
                    false);
    public static final PropertyDescriptor CERTIFICATE =
            text(
                    "Client Certificate File",
                    "Absolute path to X.509 PEM chain (leaf first), or DER leaf certificate, on the"
                            + " NiFi host.",
                    false,
                    true);
    public static final PropertyDescriptor PRIVATE_KEY =
            text(
                    "Client Private Key File",
                    "Absolute path to RSA PEM private key (PKCS#1/PKCS#8, optional encryption) or"
                            + " unencrypted PKCS#8 DER.",
                    false,
                    true);
    public static final PropertyDescriptor KEY_PASSWORD =
            text(
                    "Private Key Password",
                    "Password for encrypted private key; leave unset for an unencrypted key.",
                    true,
                    false);
    public static final PropertyDescriptor PKI =
            text(
                    "PKI Directory",
                    "Server trust folder on the NiFi host (absolute path), not a login or client"
                        + " key location. Put the approved OPC server certificate in trusted/certs;"
                        + " for a CA chain also supply issuer certificates and CRLs. This lets NiFi"
                        + " verify the server, while your client certificate lets the server verify"
                        + " NiFi. All certificate checks remain enabled; certificates are never"
                        + " automatically trusted.",
                    false,
                    true);
    public static final PropertyDescriptor TAG_LIST =
            new PropertyDescriptor.Builder()
                    .name("Tags")
                    .description(
                            "Paste one complete NodeId per line, or alias | NodeId. Fictional"
                                + " example: Temperature | ns=2;s=Example.Tank.Temperature. Blank"
                                + " lines and lines starting with # are ignored. Alternatively"
                                + " paste a JSON array of objects with nodeId, optional alias,"
                                + " samplingIntervalMs, queueSize and discardOldest. Entries use"
                                + " processor defaults unless overridden. Individual tag properties"
                                + " are merged; duplicate aliases or NodeIds are rejected. Stop,"
                                + " edit and restart to apply changes.")
                    .required(false)
                    .addValidator(
                            (subject, input, context) ->
                                    new ValidationResult.Builder()
                                            .subject(subject)
                                            .valid(true)
                                            .build())
                    .build();
    public static final PropertyDescriptor USERNAME =
            text("Username", "OPC UA username.", false, true);
    public static final PropertyDescriptor PASSWORD =
            text("Password", "OPC UA password. Use a sensitive NiFi parameter.", true, true);
    public static final PropertyDescriptor PUBLISHING =
            number(
                    "Publishing Interval (ms)",
                    "Requested subscription publishing interval; server may revise it.",
                    "1000",
                    86400000);
    public static final PropertyDescriptor SAMPLING =
            number(
                    "Default Sampling Interval (ms)",
                    "Default requested sampling interval. Per-tag JSON can override it, including 0"
                            + " for fastest supported sampling.",
                    "1000",
                    86400000);
    public static final PropertyDescriptor SERVER_QUEUE =
            number(
                    "Default Monitored Item Queue Size",
                    "Requested per-tag queue on the OPC server; server may revise it. This is"
                            + " independent of the local buffer.",
                    "100",
                    1000000);
    public static final PropertyDescriptor BUFFER =
            number(
                    "Notification Buffer Capacity",
                    "Maximum notifications in memory. Drop NEWEST on overflow to preserve"
                            + " uncommitted batches; report drops in logs and counters.",
                    "10000",
                    1000000);
    public static final PropertyDescriptor BATCH =
            number(
                    "Max Records Per FlowFile",
                    "Maximum notifications per JSON array.",
                    "500",
                    100000);
    public static final PropertyDescriptor TIMEOUT =
            number(
                    "Request Timeout (ms)",
                    "Timeout per OPC operation, including discovery connect and GetEndpoints."
                        + " Startup has multiple operations; item creation is chunked in batches of"
                        + " 100. Stop cancels startup. Cleanup waits at most 1 second per"
                        + " disconnect.",
                    "10000",
                    300000);
    public static final PropertyDescriptor RETRY =
            number(
                    "Reconnect Delay (ms)",
                    "Delay between attempts to recreate a failed subscription. Milo handles normal"
                            + " transport reconnects automatically.",
                    "5000",
                    300000);
    public static final Relationship SUCCESS =
            new Relationship.Builder()
                    .name("success")
                    .description("JSON arrays of data changes, including Bad/Uncertain quality.")
                    .build();
    private static final List<PropertyDescriptor> PROPERTIES =
            Arrays.asList(
                    ENDPOINT,
                    TAG_LIST,
                    CERTIFICATE,
                    PRIVATE_KEY,
                    KEY_PASSWORD,
                    PKI,
                    USERNAME,
                    PASSWORD,
                    APPLICATION_URI,
                    PUBLISHING,
                    SAMPLING,
                    SERVER_QUEUE,
                    BUFFER,
                    BATCH,
                    TIMEOUT,
                    RETRY);
    private volatile NotificationBuffer buffer;
    private AutoCloseable connection;
    private final Object lifecycle = new Object();
    private volatile boolean running;
    private volatile ProcessContext scheduledContext;
    private volatile boolean primaryRevoked;
    private volatile ConnectionAttempt attempt;
    private String bufferEndpoint;
    private boolean replaceBufferWhenDrained;
    private final AtomicBoolean recovery = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong dataLost = new AtomicLong();
    private long nextRetry;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return Collections.singleton(SUCCESS);
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(String name) {
        return new PropertyDescriptor.Builder()
                .name(name)
                .dynamic(true)
                .required(false)
                .description(
                        "NodeId or JSON tag options. NodeIds are case-sensitive; namespace indexes"
                                + " must match the server.")
                .addValidator(
                        (subject, input, context) -> {
                            try {
                                TagDefinition.parse(subject, input, 1000, 100);
                                return new ValidationResult.Builder()
                                        .subject(subject)
                                        .valid(true)
                                        .build();
                            } catch (IllegalArgumentException e) {
                                return new ValidationResult.Builder()
                                        .subject(subject)
                                        .valid(false)
                                        .explanation(e.getMessage())
                                        .build();
                            }
                        })
                .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext context) {
        List<ValidationResult> errors = new ArrayList<>();
        try {
            TagList.parse(
                    context.getProperty(TAG_LIST).getValue(),
                    individualTags(context.getProperties()),
                    1000,
                    100);
        } catch (IllegalArgumentException e) {
            errors.add(
                    new ValidationResult.Builder()
                            .subject("Tags")
                            .valid(false)
                            .explanation(e.getMessage())
                            .build());
        }
        String endpoint = context.getProperty(ENDPOINT).getValue();
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            if (!"opc.tcp".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (Exception e) {
            errors.add(
                    new ValidationResult.Builder()
                            .subject(ENDPOINT.getName())
                            .valid(false)
                            .explanation(
                                    "Use an opc.tcp URL with a host and no embedded credentials,"
                                            + " query or fragment")
                            .build());
        }
        for (PropertyDescriptor path : Arrays.asList(CERTIFICATE, PRIVATE_KEY, PKI)) {
            try {
                if (!java.nio.file.Paths.get(context.getProperty(path).getValue()).isAbsolute())
                    throw new IllegalArgumentException();
            } catch (Exception e) {
                errors.add(
                        new ValidationResult.Builder()
                                .subject(path.getName())
                                .valid(false)
                                .explanation("Use an absolute path on the NiFi host")
                                .build());
            }
        }
        return errors;
    }

    @OnScheduled
    public void start(ProcessContext context) throws Exception {
        synchronized (lifecycle) {
            if (buffer == null || buffer.size() == 0) {
                buffer = new NotificationBuffer(context.getProperty(BUFFER).asInteger());
                bufferEndpoint = context.getProperty(ENDPOINT).getValue();
                replaceBufferWhenDrained = false;
            } else {
                // Preserve old data and its source identity, even if the next configuration
                // differs.
                replaceBufferWhenDrained = true;
            }
            recovery.set(false);
            nextRetry = 0;
            scheduledContext = context;
            running = true;
        }
    }

    private boolean ownsSubscription() {
        return !primaryRevoked
                && (!getNodeTypeProvider().isConfiguredForClustering()
                        || (scheduledContext != null
                                && scheduledContext.isConnectedToCluster()
                                && getNodeTypeProvider().isPrimary()));
    }

    @OnPrimaryNodeStateChange
    public void primaryChanged(PrimaryNodeState state) {
        if (state == PrimaryNodeState.PRIMARY_NODE_REVOKED) {
            primaryRevoked = true;
            cancelConnection();
        } else {
            synchronized (lifecycle) {
                primaryRevoked = false;
                nextRetry = 0;
            }
        }
    }

    protected AutoCloseable openConnection(ProcessContext context, ConnectionAttempt generation)
            throws Exception {
        List<TagDefinition> tags =
                TagList.parse(
                        context.getProperty(TAG_LIST).getValue(),
                        individualTags(context.getProperties()),
                        context.getProperty(SAMPLING).asInteger(),
                        context.getProperty(SERVER_QUEUE).asInteger());
        OpcSettings settings =
                new OpcSettings(
                        context.getProperty(ENDPOINT).getValue(),
                        context.getProperty(APPLICATION_URI).getValue(),
                        java.nio.file.Paths.get(context.getProperty(CERTIFICATE).getValue()),
                        java.nio.file.Paths.get(context.getProperty(PRIVATE_KEY).getValue()),
                        Optional.ofNullable(context.getProperty(KEY_PASSWORD).getValue())
                                .orElse(""),
                        java.nio.file.Paths.get(context.getProperty(PKI).getValue()),
                        context.getProperty(USERNAME).getValue(),
                        context.getProperty(PASSWORD).getValue(),
                        context.getProperty(PUBLISHING).asInteger(),
                        context.getProperty(TIMEOUT).asInteger(),
                        tags);
        NotificationBuffer destination = buffer;
        return new OpcConnection(
                settings,
                (tag, value) ->
                        generation.ifActive(
                                () -> {
                                    if (running && ownsSubscription())
                                        accept(destination, tag, value);
                                }),
                () -> generation.ifActive(() -> recovery.set(true)),
                () -> generation.ifActive(() -> dataLost.incrementAndGet()),
                generation);
    }

    private static Map<String, String> individualTags(Map<PropertyDescriptor, String> properties) {
        Map<String, String> tags = new LinkedHashMap<>();
        properties.forEach(
                (descriptor, value) -> {
                    if (descriptor.isDynamic()) tags.put(descriptor.getName(), value);
                });
        return tags;
    }

    void accept(NotificationBuffer destination, TagDefinition tag, DataValue value) {
        try {
            if (!destination.offer(NotificationJson.encode(tag, value))) dropped.incrementAndGet();
        } catch (Exception e) {
            dropped.incrementAndGet();
        }
    }

    void acceptForTest(TagDefinition tag, DataValue value) {
        accept(buffer, tag, value);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        synchronized (lifecycle) {
            if (!running || !ownsSubscription() || buffer == null) {
                closeConnection();
                context.yield();
                return;
            }
            if (replaceBufferWhenDrained && buffer.size() == 0) {
                buffer = new NotificationBuffer(context.getProperty(BUFFER).asInteger());
                bufferEndpoint = context.getProperty(ENDPOINT).getValue();
                replaceBufferWhenDrained = false;
            }
            // Stop the failed generation before draining, so it cannot keep filling the buffer.
            if (recovery.get() && connection != null) closeConnection();
            // Drain retained data even if the OPC server is unavailable.
            if (buffer.size() == 0
                    && (connection == null || recovery.get())
                    && (nextRetry == 0 || System.nanoTime() - nextRetry >= 0)) {
                closeConnection();
                recovery.set(false);
                ConnectionAttempt generation = new ConnectionAttempt();
                attempt = generation;
                if (!running || !ownsSubscription()) generation.cancel();
                try {
                    generation.check();
                    connection = openConnection(context, generation);
                    generation.check();
                } catch (Exception e) {
                    generation.cancel();
                    closeConnection();
                    if (running && ownsSubscription()) {
                        recovery.set(true);
                        getLogger()
                                .error(
                                        "Could not establish OPC UA subscription ({}); retrying"
                                                + " after configured delay",
                                        new Object[] {e.getClass().getSimpleName()});
                        context.yield();
                    }
                }
                nextRetry = System.nanoTime() + context.getProperty(RETRY).asInteger() * 1000000L;
            }
            if (!running || !ownsSubscription()) return;
            long lost = dropped.getAndSet(0);
            if (lost > 0) {
                session.adjustCounter("OPC UA notifications dropped", lost, true);
                getLogger()
                        .error(
                                "Dropped {} OPC UA notifications due to local buffer overflow or"
                                        + " encoding failure",
                                new Object[] {lost});
            }
            long gaps = dataLost.getAndSet(0);
            if (gaps > 0) {
                session.adjustCounter("OPC UA server data loss events", gaps, true);
                getLogger()
                        .error(
                                "OPC UA server could not republish notifications ({} events); there"
                                        + " is a data gap",
                                new Object[] {gaps});
            }
            final NotificationBuffer pending = buffer;
            List<byte[]> records = pending.reserve(context.getProperty(BATCH).asInteger());
            if (records.isEmpty()) {
                context.yield();
                return;
            }
            try {
                FlowFile flow = session.create();
                flow =
                        session.write(
                                flow,
                                out -> {
                                    out.write('[');
                                    for (int i = 0; i < records.size(); i++) {
                                        if (i > 0) out.write(',');
                                        out.write(records.get(i));
                                    }
                                    out.write(']');
                                });
                Map<String, String> attributes = new HashMap<>();
                attributes.put("mime.type", "application/json");
                attributes.put("record.count", Integer.toString(records.size()));
                attributes.put("opcua.endpoint", bufferEndpoint);
                attributes.put("opcua.security.policy", "Basic256Sha256");
                attributes.put("opcua.security.mode", "SignAndEncrypt");
                flow = session.putAllAttributes(flow, attributes);
                session.getProvenanceReporter().receive(flow, bufferEndpoint);
                session.transfer(flow, SUCCESS);
                session.commitAsync(
                        () -> pending.acknowledge(records.size()),
                        error -> {
                            pending.release();
                            getLogger()
                                    .error(
                                            "OPC UA FlowFile commit failed; notifications retained"
                                                    + " for retry");
                        });
            } catch (RuntimeException e) {
                pending.release();
                session.rollback();
                throw new ProcessException(
                        "Could not commit OPC UA batch; notifications retained for retry", e);
            }
        }
    }

    @OnUnscheduled
    public void unscheduled() {
        running = false;
        cancelConnection();
    }

    @OnStopped
    public void stop() {
        unscheduled();
        if (buffer != null && buffer.size() > 0)
            getLogger()
                    .warn(
                            "Stopping with {} uncommitted notifications in memory; retained for"
                                + " Start and drained before the next subscription; lost if the JVM"
                                + " exits or processor is removed",
                            new Object[] {buffer.size()});
        long lost = dropped.getAndSet(0);
        if (lost > 0)
            getLogger()
                    .error(
                            "{} additional notifications were dropped before stop",
                            new Object[] {lost});
    }

    private void cancelConnection() {
        ConnectionAttempt current = attempt;
        if (current != null) current.cancel();
        synchronized (lifecycle) {
            closeConnection();
        }
    }

    @OnRemoved
    public void removed() {
        unscheduled();
        synchronized (lifecycle) {
            buffer = null;
            bufferEndpoint = null;
            scheduledContext = null;
        }
    }

    private void closeConnection() {
        if (attempt != null) attempt.cancel();
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                getLogger()
                        .warn(
                                "OPC UA connection cleanup failed ({})",
                                new Object[] {e.getClass().getSimpleName()});
            } finally {
                connection = null;
            }
        }
    }
}
