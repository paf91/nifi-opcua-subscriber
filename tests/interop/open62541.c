/* SPDX-License-Identifier: Apache-2.0 */
/* Isolated test fixture. Credentials, files and listening port are test-only. */
#include <open62541/server.h>
#include <open62541/server_config_default.h>
#include <open62541/plugin/accesscontrol_default.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>

static volatile sig_atomic_t running = 1;
static UA_NodeId valueId;
static unsigned previousSequence = 0;
static void stopServer(int signalNumber) { (void)signalNumber; running = 0; }
static void check(UA_StatusCode status) {
    if(status != UA_STATUSCODE_GOOD) {
        fprintf(stderr, "Fixture operation failed: %s\n", UA_StatusCode_name(status));
        exit(2);
    }
}
static UA_ByteString load(const char *path) {
    FILE *file = fopen(path, "rb");
    if(!file) exit(2);
    fseek(file, 0, SEEK_END); long size = ftell(file); rewind(file);
    if(size < 1) exit(2);
    UA_ByteString bytes = UA_BYTESTRING_NULL;
    check(UA_ByteString_allocBuffer(&bytes, (size_t)size));
    if(fread(bytes.data, 1, (size_t)size, file) != (size_t)size) exit(2);
    fclose(file); return bytes;
}
static UA_NodeId addVariable(UA_Server *server, UA_UInt16 ns, const char *name, double value) {
    UA_VariableAttributes attr = UA_VariableAttributes_default;
    UA_Variant_setScalar(&attr.value, &value, &UA_TYPES[UA_TYPES_DOUBLE]);
    attr.dataType = UA_NODEID_NUMERIC(0, UA_NS0ID_DOUBLE);
    attr.displayName = UA_LOCALIZEDTEXT("en", (char*)name);
    attr.accessLevel = UA_ACCESSLEVELMASK_READ;
    attr.minimumSamplingInterval = 50;
    UA_NodeId id = UA_NODEID_STRING(ns, (char*)name);
    check(UA_Server_addVariableNode(server, id, UA_NODEID_NUMERIC(0, UA_NS0ID_OBJECTSFOLDER),
        UA_NODEID_NUMERIC(0, UA_NS0ID_ORGANIZES), UA_QUALIFIEDNAME(ns, (char*)name),
        UA_NODEID_NUMERIC(0, UA_NS0ID_BASEDATAVARIABLETYPE), attr, NULL, NULL));
    return id;
}
static void applyControl(UA_Server *server, void *unused) {
    (void)unused;
    FILE *file = fopen("control.txt", "r");
    if(!file) return;
    unsigned seq, status; double value;
    int read = fscanf(file, "%u %lf %u", &seq, &value, &status);
    fclose(file);
    if(read != 3 || seq == previousSequence) return;
    UA_DataValue update; UA_DataValue_init(&update);
    update.hasValue = true; update.hasStatus = true; update.status = status;
    update.hasSourceTimestamp = true; update.sourceTimestamp = UA_DateTime_now();
    UA_Variant_setScalar(&update.value, &value, &UA_TYPES[UA_TYPES_DOUBLE]);
    check(UA_Server_writeDataValue(server, valueId, update));
    previousSequence = seq;
    file = fopen("applied.txt", "w");
    if(!file) exit(2);
    fprintf(file, "%u", seq); fclose(file);
}
int main(int argc, char **argv) {
    if(argc != 4) return 2; /* port, pad namespace (0/1), username token policy */
    signal(SIGINT, stopServer); signal(SIGTERM, stopServer);
    UA_Server *server = UA_Server_new();
    UA_ServerConfig *config = UA_Server_getConfig(server);
    UA_ByteString cert = load("server.der"), key = load("server-key.der"), trust = load("client.der");
    check(UA_ServerConfig_setDefaultWithSecurityPolicies(config, (UA_UInt16)atoi(argv[1]), &cert, &key, &trust, 1, NULL, 0, NULL, 0));
    /* Explicit legacy endpoints solely to exercise the client's compatibility options. */
    check(UA_ServerConfig_addSecurityPolicyBasic128Rsa15(config, &cert, &key));
    check(UA_ServerConfig_addSecurityPolicyBasic256(config, &cert, &key));
    UA_String_clear(&config->applicationDescription.applicationUri);
    config->applicationDescription.applicationUri = UA_STRING_ALLOC("urn:nifi:interop:server");
    char url[128]; snprintf(url, sizeof(url), "opc.tcp://localhost:%s", argv[1]);
    UA_Array_delete(config->serverUrls, config->serverUrlsSize, &UA_TYPES[UA_TYPES_STRING]);
    config->serverUrls = UA_Array_new(1, &UA_TYPES[UA_TYPES_STRING]); config->serverUrlsSize = 1;
    config->serverUrls[0] = UA_STRING_ALLOC(url);
    config->allowNonePolicyPassword = true;
    config->accessControl.clear(&config->accessControl);
    UA_UsernamePasswordLogin login = {UA_STRING("test-user"), UA_STRING("test-password")};
    UA_String tokenPolicy = UA_STRING(argv[3]);
    check(UA_AccessControl_default(config, true, &tokenPolicy, 1, &login));
    /* Rebuild endpoints after changing the advertised token policies. */
    UA_Array_delete(config->endpoints, config->endpointsSize, &UA_TYPES[UA_TYPES_ENDPOINTDESCRIPTION]);
    config->endpoints = NULL; config->endpointsSize = 0;
    check(UA_ServerConfig_addAllEndpoints(config));
    UA_ByteString_clear(&cert); UA_ByteString_clear(&key); UA_ByteString_clear(&trust);
    if(atoi(argv[2])) {
        UA_UInt16 wrong = UA_Server_addNamespace(server, "urn:nifi:interop:other");
        addVariable(server, wrong, "Value", -9999); /* Catch stale numeric resolution. */
    }
    UA_UInt16 ns = UA_Server_addNamespace(server, "urn:nifi:interop:tags");
    valueId = addVariable(server, ns, "Value", 0);
    addVariable(server, ns, "NoRange", 0);
    UA_Range range = {0, 100};
    UA_VariableAttributes rangeAttr = UA_VariableAttributes_default;
    rangeAttr.displayName = UA_LOCALIZEDTEXT("en", "EURange");
    rangeAttr.dataType = UA_NODEID_NUMERIC(0, UA_NS0ID_RANGE);
    UA_Variant_setScalar(&rangeAttr.value, &range, &UA_TYPES[UA_TYPES_RANGE]);
    check(UA_Server_addVariableNode(server, UA_NODEID_STRING(ns, "Value.EURange"), valueId,
        UA_NODEID_NUMERIC(0, UA_NS0ID_HASPROPERTY), UA_QUALIFIEDNAME(0, "EURange"),
        UA_NODEID_NUMERIC(0, UA_NS0ID_PROPERTYTYPE), rangeAttr, NULL, NULL));
    check(UA_Server_addRepeatedCallback(server, applyControl, NULL, 50, NULL));
    check(UA_Server_run_startup(server));
    FILE *ready = fopen("ready.txt", "w"); if(!ready) return 2;
    fprintf(ready, "%u", ns); fclose(ready);
    while(running) UA_Server_run_iterate(server, true);
    UA_Server_run_shutdown(server); UA_Server_delete(server);
    return 0;
}
