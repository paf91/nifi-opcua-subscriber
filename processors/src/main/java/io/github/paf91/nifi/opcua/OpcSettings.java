/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import java.nio.file.Path;
import java.util.List;

final class OpcSettings {
    private final String endpoint;
    private final String applicationUri;
    private final Path certificate;
    private final Path privateKey;
    private final String privateKeyPassword;
    private final Path pki;
    private final String username;
    private final String password;
    private final int publishingInterval;
    private final int requestTimeout;
    private final List<TagDefinition> tags;
    private final OpcSecurity security;

    OpcSettings(
            String endpoint,
            String applicationUri,
            Path certificate,
            Path privateKey,
            String privateKeyPassword,
            Path pki,
            String username,
            String password,
            int publishingInterval,
            int requestTimeout,
            List<TagDefinition> tags) {
        this(
                endpoint,
                applicationUri,
                certificate,
                privateKey,
                privateKeyPassword,
                pki,
                username,
                password,
                publishingInterval,
                requestTimeout,
                tags,
                OpcSecurity.DEFAULT);
    }

    OpcSettings(
            String endpoint,
            String applicationUri,
            Path certificate,
            Path privateKey,
            String privateKeyPassword,
            Path pki,
            String username,
            String password,
            int publishingInterval,
            int requestTimeout,
            List<TagDefinition> tags,
            OpcSecurity security) {
        this.endpoint = endpoint;
        this.applicationUri = applicationUri;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.privateKeyPassword = privateKeyPassword;
        this.pki = pki;
        this.username = username;
        this.password = password;
        this.publishingInterval = publishingInterval;
        this.requestTimeout = requestTimeout;
        this.tags = tags;
        this.security = security;
    }

    String endpoint() {
        return endpoint;
    }

    String applicationUri() {
        return applicationUri;
    }

    Path certificate() {
        return certificate;
    }

    Path privateKey() {
        return privateKey;
    }

    String privateKeyPassword() {
        return privateKeyPassword;
    }

    Path pki() {
        return pki;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    int publishingInterval() {
        return publishingInterval;
    }

    int requestTimeout() {
        return requestTimeout;
    }

    List<TagDefinition> tags() {
        return tags;
    }

    OpcSecurity security() {
        return security;
    }
}
