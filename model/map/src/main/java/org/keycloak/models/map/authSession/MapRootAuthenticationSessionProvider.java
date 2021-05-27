/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.models.map.authSession;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.map.storage.MapKeycloakTransaction;
import org.keycloak.models.map.storage.MapStorage;
import org.keycloak.models.map.storage.ModelCriteriaBuilder;
import org.keycloak.models.map.storage.ModelCriteriaBuilder.Operator;
import org.keycloak.models.utils.RealmInfoUtil;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import org.keycloak.sessions.RootAuthenticationSessionModel.SearchableFields;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.map.common.MapStorageUtils.registerEntityForChanges;

/**
 * @author <a href="mailto:mkanis@redhat.com">Martin Kanis</a>
 */
public class MapRootAuthenticationSessionProvider<K> implements AuthenticationSessionProvider {

    private static final Logger LOG = Logger.getLogger(MapRootAuthenticationSessionProvider.class);
    private final KeycloakSession session;
    protected final MapKeycloakTransaction<K, MapRootAuthenticationSessionEntity<K>, RootAuthenticationSessionModel> tx;
    private final MapStorage<K, MapRootAuthenticationSessionEntity<K>, RootAuthenticationSessionModel> sessionStore;

    private static final String AUTHENTICATION_SESSION_EVENTS = "AUTHENTICATION_SESSION_EVENTS";

    public MapRootAuthenticationSessionProvider(KeycloakSession session, MapStorage<K, MapRootAuthenticationSessionEntity<K>, RootAuthenticationSessionModel> sessionStore) {
        this.session = session;
        this.sessionStore = sessionStore;
        this.tx = sessionStore.createTransaction(session);

        session.getTransactionManager().enlistAfterCompletion(tx);
    }

    private Function<MapRootAuthenticationSessionEntity<K>, RootAuthenticationSessionModel> entityToAdapterFunc(RealmModel realm) {
        // Clone entity before returning back, to avoid giving away a reference to the live object to the caller

        return origEntity -> new MapRootAuthenticationSessionAdapter<K>(session, realm, registerEntityForChanges(tx, origEntity)) {
            @Override
            public String getId() {
                return sessionStore.getKeyConvertor().keyToString(entity.getId());
            }
        };
    }

    private Predicate<MapRootAuthenticationSessionEntity<K>> entityRealmFilter(String realmId) {
        if (realmId == null) {
            return c -> false;
        }
        return entity -> Objects.equals(realmId, entity.getRealmId());
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
        Objects.requireNonNull(realm, "The provided realm can't be null!");
        return createRootAuthenticationSession(realm, null);
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm, String id) {
        Objects.requireNonNull(realm, "The provided realm can't be null!");

        final K entityId = id == null ? sessionStore.getKeyConvertor().yieldNewUniqueKey() : sessionStore.getKeyConvertor().fromString(id);

        LOG.tracef("createRootAuthenticationSession(%s)%s", realm.getName(), getShortStackTrace());

        // create map authentication session entity
        MapRootAuthenticationSessionEntity<K> entity = new MapRootAuthenticationSessionEntity<>(entityId, realm.getId());
        entity.setRealmId(realm.getId());
        entity.setTimestamp(Time.currentTime());

        if (tx.read(entity.getId()) != null) {
            throw new ModelDuplicateException("Root authentication session exists: " + entity.getId());
        }

        tx.create(entity.getId(), entity);

        return entityToAdapterFunc(realm).apply(entity);
    }

    @Override
    public RootAuthenticationSessionModel getRootAuthenticationSession(RealmModel realm, String authenticationSessionId) {
        Objects.requireNonNull(realm, "The provided realm can't be null!");
        if (authenticationSessionId == null) {
            return null;
        }

        LOG.tracef("getRootAuthenticationSession(%s, %s)%s", realm.getName(), authenticationSessionId, getShortStackTrace());

        MapRootAuthenticationSessionEntity<K> entity = tx.read(sessionStore.getKeyConvertor().fromStringSafe(authenticationSessionId));
        return (entity == null || !entityRealmFilter(realm.getId()).test(entity))
                ? null
                : entityToAdapterFunc(realm).apply(entity);
    }

    @Override
    public void removeRootAuthenticationSession(RealmModel realm, RootAuthenticationSessionModel authenticationSession) {
        Objects.requireNonNull(authenticationSession, "The provided root authentication session can't be null!");
        tx.delete(sessionStore.getKeyConvertor().fromString(authenticationSession.getId()));
    }

    @Override
    public void removeAllExpired() {
        session.realms().getRealmsStream().forEach(this::removeExpired);
    }

    @Override
    public void removeExpired(RealmModel realm) {
        Objects.requireNonNull(realm, "The provided realm can't be null!");
        LOG.debugf("Removing expired sessions");

        int expired = Time.currentTime() - RealmInfoUtil.getDettachedClientSessionLifespan(realm);

        ModelCriteriaBuilder<RootAuthenticationSessionModel> mcb = sessionStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId())
          .compare(SearchableFields.TIMESTAMP, Operator.LT, expired);

        long deletedCount = tx.delete(sessionStore.getKeyConvertor().yieldNewUniqueKey(), mcb);

        LOG.debugf("Removed %d expired authentication sessions for realm '%s'", deletedCount, realm.getName());
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        Objects.requireNonNull(realm, "The provided realm can't be null!");
        ModelCriteriaBuilder<RootAuthenticationSessionModel> mcb = sessionStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId());

        sessionStore.delete(mcb);
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {

    }

    @Override
    public void updateNonlocalSessionAuthNotes(AuthenticationSessionCompoundId compoundId, Map<String, String> authNotesFragment) {
        if (compoundId == null) {
            return;
        }
        Objects.requireNonNull(authNotesFragment, "The provided authentication's notes map can't be null!");

        ClusterProvider cluster = session.getProvider(ClusterProvider.class);
        cluster.notify(
                AUTHENTICATION_SESSION_EVENTS,
                MapAuthenticationSessionAuthNoteUpdateEvent.create(compoundId.getRootSessionId(), compoundId.getTabId(),
                        compoundId.getClientUUID(), authNotesFragment),
                true,
                ClusterProvider.DCNotify.ALL_BUT_LOCAL_DC
        );
    }

    @Override
    public void close() {

    }
}
