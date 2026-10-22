/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounds session acquisition plus the service response, independently for each SDK operation. */
final class BoundedRequest {
    static <T> T execute(
            ConnectionAttempt attempt, long timeoutMillis, Supplier<CompletableFuture<T>> operation)
            throws UaException {
        attempt.check();
        try {
            return attempt.await(operation.get(), timeoutMillis);
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException | TimeoutException e) {
            // Milo catches UaException and may continue with more partitions. These failures
            // must escape that loop; ConnectionAttempt.await also cancels the pending future.
            throw new CompletionException("OPC UA operation interrupted or timed out", e);
        } catch (Exception e) {
            UaException serviceFailure = UaException.extract(e).orElse(null);
            if (serviceFailure != null
                    && serviceFailure.getStatusCode().getValue() != StatusCodes.Bad_Timeout)
                throw serviceFailure;
            throw new CompletionException(e);
        }
    }
}
