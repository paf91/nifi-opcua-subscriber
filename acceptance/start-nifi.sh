#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Isolated acceptance containers only: no authentication, loopback host ports.
set -eu
. /opt/nifi/scripts/common.sh
prop_replace 'nifi.web.https.port' ''
prop_replace 'nifi.web.https.host' ''
prop_replace 'nifi.web.http.port' '8080'
if [ "${ACCEPTANCE_CLUSTER:-false}" = true ]; then
    prop_replace 'nifi.web.http.host' "$HOSTNAME"
else
    prop_replace 'nifi.web.http.host' '0.0.0.0'
fi
prop_replace 'nifi.remote.input.secure' 'false'
prop_replace 'nifi.cluster.protocol.is.secure' 'false'
prop_replace 'nifi.security.keystore' ''
prop_replace 'nifi.security.keystoreType' ''
prop_replace 'nifi.security.truststore' ''
prop_replace 'nifi.security.truststoreType' ''
prop_replace 'nifi.security.user.login.identity.provider' ''
# Avoid unrelated host disk utilization triggering archive throttling in this disposable test.
prop_replace 'nifi.content.repository.archive.enabled' 'false'
prop_replace 'nifi.sensitive.props.key' "$NIFI_SENSITIVE_PROPS_KEY"
prop_replace 'nifi.cluster.is.node' "${ACCEPTANCE_CLUSTER:-false}"
prop_replace 'nifi.cluster.node.address' "$HOSTNAME"
prop_replace 'nifi.cluster.node.protocol.port' '11443'
prop_replace 'nifi.cluster.flow.election.max.wait.time' '20 sec'
prop_replace 'nifi.cluster.flow.election.max.candidates' '2'
prop_replace 'nifi.zookeeper.connect.string' 'zk:2181'
prop_replace 'nifi.zookeeper.root.node' '/opcua-acceptance'
prop_replace 'nifi.cluster.load.balance.host' "$HOSTNAME"
prop_replace 'java.arg.2' '-Xms512m' "$nifi_bootstrap_file"
prop_replace 'java.arg.3' '-Xmx1g' "$nifi_bootstrap_file"
export NIFI_ZK_CONNECT_STRING=zk:2181
export NIFI_ZK_ROOT_NODE=/opcua-acceptance
set +u
. /opt/nifi/scripts/update_cluster_state_management.sh
set -u
exec "$NIFI_HOME/bin/nifi.sh" run
