/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.models.map.authorization;

import org.jboss.logging.Logger;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;
import org.keycloak.models.map.authorization.adapter.MapResourceServerAdapter;
import org.keycloak.models.map.authorization.entity.MapResourceServerEntity;
import org.keycloak.models.map.storage.MapKeycloakTransaction;
import org.keycloak.models.map.storage.MapStorage;
import org.keycloak.storage.StorageId;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.map.common.MapStorageUtils.registerEntityForChanges;

public class MapResourceServerStore<K> implements ResourceServerStore {

    private static final Logger LOG = Logger.getLogger(MapResourceServerStore.class);
    private final AuthorizationProvider authorizationProvider;
    final MapKeycloakTransaction<K, MapResourceServerEntity<K>, ResourceServer> tx;
    private final MapStorage<K, MapResourceServerEntity<K>, ResourceServer> resourceServerStore;

    public MapResourceServerStore(KeycloakSession session, MapStorage<K, MapResourceServerEntity<K>, ResourceServer> resourceServerStore, AuthorizationProvider provider) {
        this.resourceServerStore = resourceServerStore;
        this.tx = resourceServerStore.createTransaction(session);
        this.authorizationProvider = provider;
        session.getTransactionManager().enlist(tx);
    }

    private ResourceServer entityToAdapter(MapResourceServerEntity<K> origEntity) {
        if (origEntity == null) return null;
        // Clone entity before returning back, to avoid giving away a reference to the live object to the caller
        return new MapResourceServerAdapter<K>(registerEntityForChanges(tx, origEntity), authorizationProvider.getStoreFactory()) {
            @Override
            public String getId() {
                return resourceServerStore.getKeyConvertor().keyToString(entity.getId());
            }
        };
    }

    @Override
    public ResourceServer create(String clientId) {
        LOG.tracef("create(%s)%s", clientId, getShortStackTrace());
        
        if (clientId == null) return null;

        if (!StorageId.isLocalStorage(clientId)) {
            throw new ModelException("Creating resource server from federated ClientModel not supported");
        }

        if (tx.read(resourceServerStore.getKeyConvertor().fromString(clientId)) != null) {
            throw new ModelDuplicateException("Resource server already exists: " + clientId);
        }

        MapResourceServerEntity<K> entity = new MapResourceServerEntity<>(resourceServerStore.getKeyConvertor().fromString(clientId));

        tx.create(entity.getId(), entity);

        return entityToAdapter(entity);
    }

    @Override
    public void delete(String id) {
        LOG.tracef("delete(%s, %s)%s", id, getShortStackTrace());
        if (id == null) return;

        PolicyStore policyStore = authorizationProvider.getStoreFactory().getPolicyStore();
        policyStore.findByResourceServer(id).stream()
            .map(Policy::getId)
            .forEach(policyStore::delete);

        PermissionTicketStore permissionTicketStore = authorizationProvider.getStoreFactory().getPermissionTicketStore();
        permissionTicketStore.findByResourceServer(id).stream()
                .map(PermissionTicket::getId)
                .forEach(permissionTicketStore::delete);

        ResourceStore resourceStore = authorizationProvider.getStoreFactory().getResourceStore();
        resourceStore.findByResourceServer(id).stream()
                .map(Resource::getId)
                .forEach(resourceStore::delete);

        ScopeStore scopeStore = authorizationProvider.getStoreFactory().getScopeStore();
        scopeStore.findByResourceServer(id).stream()
                .map(Scope::getId)
                .forEach(scopeStore::delete);

        tx.delete(resourceServerStore.getKeyConvertor().fromString(id));
    }

    @Override
    public ResourceServer findById(String id) {
        LOG.tracef("findById(%s)%s", id, getShortStackTrace());

        if (id == null) {
            return null;
        }


        MapResourceServerEntity<K> entity = tx.read(resourceServerStore.getKeyConvertor().fromStringSafe(id));
        return entityToAdapter(entity);
    }
}
