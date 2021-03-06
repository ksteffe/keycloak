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

package org.keycloak.keys.infinispan;

import java.security.PublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.infinispan.Cache;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.keys.KeyLoader;
import org.keycloak.keys.KeyStorageProvider;


/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class InfinispanKeyStorageProvider implements KeyStorageProvider {

    private static final Logger log = Logger.getLogger(InfinispanKeyStorageProvider.class);

    private final Cache<String, PublicKeysEntry> keys;

    private final Map<String, FutureTask<PublicKeysEntry>> tasksInProgress;

    private final int minTimeBetweenRequests ;

    public InfinispanKeyStorageProvider(Cache<String, PublicKeysEntry> keys,  Map<String, FutureTask<PublicKeysEntry>> tasksInProgress, int minTimeBetweenRequests) {
        this.keys = keys;
        this.tasksInProgress = tasksInProgress;
        this.minTimeBetweenRequests = minTimeBetweenRequests;
    }


    @Override
    public PublicKey getPublicKey(String modelKey, String kid, KeyLoader loader) {
        // Check if key is in cache
        PublicKeysEntry entry = keys.get(modelKey);
        if (entry != null) {
            PublicKey publicKey = getPublicKey(entry.getCurrentKeys(), kid);
            if (publicKey != null) {
                return publicKey;
            }
        }

        int lastRequestTime = entry==null ? 0 : entry.getLastRequestTime();
        int currentTime = Time.currentTime();

        // Check if we are allowed to send request
        if (currentTime > lastRequestTime + minTimeBetweenRequests) {

            WrapperCallable wrapperCallable = new WrapperCallable(modelKey, loader);
            FutureTask<PublicKeysEntry> task = new FutureTask<>(wrapperCallable);
            FutureTask<PublicKeysEntry> existing = tasksInProgress.putIfAbsent(modelKey, task);

            if (existing == null) {
                task.run();
            } else {
                task = existing;
            }

            try {
                entry = task.get();

                // Computation finished. Let's see if key is available
                PublicKey publicKey = getPublicKey(entry.getCurrentKeys(), kid);
                if (publicKey != null) {
                    return publicKey;
                }

            } catch (ExecutionException ee) {
                throw new RuntimeException("Error when loading public keys", ee);
            } catch (InterruptedException ie) {
                throw new RuntimeException("Error. Interrupted when loading public keys", ie);
            } finally {
                // Our thread inserted the task. Let's clean
                if (existing == null) {
                    tasksInProgress.remove(modelKey);
                }
            }
        } else {
            log.warnf("Won't load the keys for model '%s' . Last request time was %d", modelKey, lastRequestTime);
        }

        Set<String> availableKids = entry==null ? Collections.emptySet() : entry.getCurrentKeys().keySet();
        log.warnf("PublicKey wasn't found in the storage. Requested kid: '%s' . Available kids: '%s'", kid, availableKids);

        return null;
    }

    private PublicKey getPublicKey(Map<String, PublicKey> publicKeys, String kid) {
        // Backwards compatibility
        if (kid == null && !publicKeys.isEmpty()) {
            return publicKeys.values().iterator().next();
        } else {
            return publicKeys.get(kid);
        }
    }


    @Override
    public void close() {

    }


    private class WrapperCallable implements Callable<PublicKeysEntry> {

        private final String modelKey;
        private final KeyLoader delegate;

        public WrapperCallable(String modelKey, KeyLoader delegate) {
            this.modelKey = modelKey;
            this.delegate = delegate;
        }

        @Override
        public PublicKeysEntry call() throws Exception {
            PublicKeysEntry entry = keys.get(modelKey);

            int lastRequestTime = entry==null ? 0 : entry.getLastRequestTime();
            int currentTime = Time.currentTime();

            // Check again if we are allowed to send request. There is a chance other task was already finished and removed from tasksInProgress in the meantime.
            if (currentTime > lastRequestTime + minTimeBetweenRequests) {

                Map<String, PublicKey> publicKeys = delegate.loadKeys();

                if (log.isDebugEnabled()) {
                    log.debugf("Public keys retrieved successfully for model %s. New kids: %s", modelKey, publicKeys.keySet().toString());
                }

                entry = new PublicKeysEntry(currentTime, publicKeys);

                keys.put(modelKey, entry);
            }
            return entry;
        }
    }
}
