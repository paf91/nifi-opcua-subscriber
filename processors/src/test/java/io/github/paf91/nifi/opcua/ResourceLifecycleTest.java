/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.TestRunner;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Timeout(90)
class ResourceLifecycleTest {
    @TempDir Path dir;

    static long ownedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(
                        t ->
                                t.getName().startsWith("nifi-opcua-subscriber-transport")
                                        || t.getName().equals("certificate-store-watcher")
                                        || t.getName().equals("milo-trust-list-watcher"))
                .count();
    }

    static long fds() {
        return ((com.sun.management.UnixOperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean())
                .getOpenFileDescriptorCount();
    }

    static void awaitCondition(java.util.function.BooleanSupplier condition, int seconds)
            throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }

    SecureSubscriptionTest server() throws Exception {
        SecureSubscriptionTest fixture = new SecureSubscriptionTest();
        fixture.dir = dir;
        fixture.startServer();
        return fixture;
    }

    static WeakReference<OpcConnection> cycle(OpcSettings settings) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        OpcConnection connection =
                new OpcConnection(
                        settings, (tag, value) -> received.countDown(), () -> {}, () -> {});
        WeakReference<OpcConnection> ref = new WeakReference<>(connection);
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS));
        } finally {
            connection.close();
        }
        return ref;
    }

    @Test
    void repeatedSecureConnectionsReleaseOwnedResourcesAndKeepOtherClientAlive() throws Exception {
        SecureSubscriptionTest fixture = server();
        try {
            OpcSettings settings =
                    fixture.settings(dir.resolve("pki"), "test-password", "ns=0;i=2258");
            AtomicLong survivorValues = new AtomicLong();
            try (OpcConnection survivor =
                    new OpcConnection(
                            settings,
                            (tag, value) -> survivorValues.incrementAndGet(),
                            () -> {},
                            () -> {})) {
                awaitCondition(() -> survivorValues.get() > 0, 5);
                cycle(settings);
                long baselineThreads = ownedThreads(), baselineFds = fds();
                List<WeakReference<OpcConnection>> references = new ArrayList<>();
                for (int i = 0; i < 12; i++) references.add(cycle(settings));
                long before = survivorValues.get();
                awaitCondition(() -> survivorValues.get() > before, 5);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (references.stream().anyMatch(r -> r.get() != null)
                        && System.nanoTime() < until) {
                    System.gc();
                    Thread.sleep(50);
                }
                long reachable = references.stream().filter(r -> r.get() != null).count();
                System.out.printf(
                        Locale.ROOT,
                        "RESOURCE secure-cycles count=12 ownedThreadsBefore=%d ownedThreadsAfter=%d"
                                + " fdsBefore=%d fdsAfter=%d closedConnectionsReachable=%d"
                                + " survivorValues=%d%n",
                        baselineThreads,
                        ownedThreads(),
                        baselineFds,
                        fds(),
                        reachable,
                        survivorValues.get());
                assertEquals(0, reachable);
                assertTrue(ownedThreads() <= baselineThreads);
                assertTrue(fds() <= baselineFds + 2);
            }
        } finally {
            fixture.closeServer();
        }
    }

    @Test
    void aSlowLiveValueCallbackDoesNotBlockStopOrLeakALateValue() throws Exception {
        SecureSubscriptionTest fixture = server();
        CountDownLatch callback = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<ConnectionAttempt> generation = new AtomicReference<>();
        AtomicReference<NotificationBuffer> destination = new AtomicReference<>();
        SubscribeOpcUa processor =
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable openConnection(
                            ProcessContext context, ConnectionAttempt attempt) throws Exception {
                        generation.set(attempt);
                        return super.openConnection(context, attempt);
                    }

                    @Override
                    void accept(
                            NotificationBuffer buffer,
                            TagDefinition tag,
                            DataValue value,
                            ConnectionAttempt attempt) {
                        destination.set(buffer);
                        callback.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        super.accept(buffer, tag, value, attempt);
                    }
                };
        TestRunner runner = ProcessorTest.configured(processor);
        runner.setProperty(SubscribeOpcUa.ENDPOINT, fixture.endpoint);
        runner.setProperty(SubscribeOpcUa.CERTIFICATE, dir.resolve("client.der").toString());
        runner.setProperty(SubscribeOpcUa.PRIVATE_KEY, dir.resolve("client.pem").toString());
        runner.setProperty(SubscribeOpcUa.PKI, dir.resolve("pki").toString());
        runner.setProperty(SubscribeOpcUa.PASSWORD, "test-password");
        runner.setProperty("DemoSwitch", "ns=0;i=2258");
        processor.start(runner.getProcessContext());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> trigger =
                workers.submit(
                        () ->
                                processor.onTrigger(
                                        runner.getProcessContext(),
                                        runner.getProcessSessionFactory().createSession()));
        Future<?> stop = null;
        try {
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            stop = workers.submit(processor::unscheduled);
            stop.get(4, TimeUnit.SECONDS);
            assertTrue(generation.get().isCancelled());
            assertEquals(0, destination.get().size());
        } finally {
            release.countDown();
            trigger.get(5, TimeUnit.SECONDS);
            if (stop != null) stop.get(5, TimeUnit.SECONDS);
            processor.stop();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            fixture.closeServer();
        }
        assertEquals(0, destination.get().size(), "Late callback must not enqueue after Stop");
    }

    @Test
    void repeatedSilentHandshakesCloseSocketsAndTransportThreads() throws Exception {
        try (SilentEndpoint warmup = new SilentEndpoint()) {
            OpcSettings settings =
                    new OpcSettings(
                            warmup.url(),
                            null,
                            null,
                            null,
                            "",
                            null,
                            null,
                            null,
                            100,
                            150,
                            Collections.singletonList(
                                    TagDefinition.parse("time", "ns=0;i=2258", 100, 10)),
                            new OpcSecurity("None", "None", "Anonymous"));
            assertThrows(
                    Exception.class,
                    () -> new OpcConnection(settings, (tag, value) -> {}, () -> {}, () -> {}));
            assertTrue(warmup.disconnected.await(2, TimeUnit.SECONDS));
        }
        long baseline = ownedThreads(), baselineFds = fds();
        for (int i = 0; i < 12; i++) {
            try (SilentEndpoint silent = new SilentEndpoint()) {
                OpcSettings settings =
                        new OpcSettings(
                                silent.url(),
                                null,
                                null,
                                null,
                                "",
                                null,
                                null,
                                null,
                                100,
                                150,
                                Collections.singletonList(
                                        TagDefinition.parse("time", "ns=0;i=2258", 100, 10)),
                                new OpcSecurity("None", "None", "Anonymous"));
                assertThrows(
                        Exception.class,
                        () -> new OpcConnection(settings, (tag, value) -> {}, () -> {}, () -> {}));
                assertTrue(silent.accepted.await(2, TimeUnit.SECONDS));
                assertTrue(silent.disconnected.await(2, TimeUnit.SECONDS));
            }
        }
        awaitCondition(() -> ownedThreads() <= baseline, 5);
        System.out.printf(
                Locale.ROOT,
                "RESOURCE silent-handshakes count=12 ownedThreadsBefore=%d ownedThreadsAfter=%d"
                        + " fdsBefore=%d fdsAfter=%d%n",
                baseline,
                ownedThreads(),
                baselineFds,
                fds());
        assertTrue(fds() <= baselineFds + 4);
    }
}
