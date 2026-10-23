/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessSession;

import java.util.*;

/** The same content, attributes and provenance for scheduled and stop-time delivery. */
final class NotificationBatch {
    static void write(
            ProcessSession session,
            List<byte[]> records,
            String endpoint,
            String securityPolicy,
            String securityMode) {
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
        attributes.put("opcua.endpoint", endpoint);
        attributes.put("opcua.security.policy", securityPolicy);
        attributes.put("opcua.security.mode", securityMode);
        flow = session.putAllAttributes(flow, attributes);
        session.getProvenanceReporter().receive(flow, endpoint);
        session.transfer(flow, SubscribeOpcUa.SUCCESS);
    }
}
