/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.keys.loader;

import java.security.PublicKey;

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.keys.KeyStorageProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class KeyStorageManager {

    public static PublicKey getClientPublicKey(KeycloakSession session, ClientModel client, JWSInput input) {
        String kid = input.getHeader().getKeyId();

        KeyStorageProvider keyStorage = session.getProvider(KeyStorageProvider.class);

        String modelKey = getModelKey(client);
        ClientPublicKeyLoader loader = new ClientPublicKeyLoader(session, client);
        return keyStorage.getPublicKey(modelKey, kid, loader);
    }

    private static String getModelKey(ClientModel client) {
        return client.getRealm().getId() + "::client::" + client.getId();
    }


    public static PublicKey getIdentityProviderPublicKey(KeycloakSession session, RealmModel realm, OIDCIdentityProviderConfig idpConfig, JWSInput input) {
        String kid = input.getHeader().getKeyId();

        KeyStorageProvider keyStorage = session.getProvider(KeyStorageProvider.class);

        String modelKey = getModelKey(realm, idpConfig);
        OIDCIdentityProviderLoader loader = new OIDCIdentityProviderLoader(session, idpConfig);
        return keyStorage.getPublicKey(modelKey, kid, loader);
    }

    private static String getModelKey(RealmModel realm, OIDCIdentityProviderConfig idpConfig) {
        return realm.getId() + "::idp::" + idpConfig.getInternalId();
    }
}
