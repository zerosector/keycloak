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
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.Resource.SearchableFields;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.map.authorization.adapter.MapResourceAdapter;
import org.keycloak.models.map.authorization.entity.MapResourceEntity;
import org.keycloak.models.map.storage.MapKeycloakTransaction;
import org.keycloak.models.map.storage.MapStorage;
import org.keycloak.models.map.storage.ModelCriteriaBuilder;
import org.keycloak.models.map.storage.ModelCriteriaBuilder.Operator;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.map.common.MapStorageUtils.registerEntityForChanges;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

public class MapResourceStore<K extends Comparable<K>> implements ResourceStore {

    private static final Logger LOG = Logger.getLogger(MapResourceStore.class);
    private final AuthorizationProvider authorizationProvider;
    final MapKeycloakTransaction<K, MapResourceEntity<K>, Resource> tx;
    private final MapStorage<K, MapResourceEntity<K>, Resource> resourceStore;

    public MapResourceStore(KeycloakSession session, MapStorage<K, MapResourceEntity<K>, Resource> resourceStore, AuthorizationProvider provider) {
        this.resourceStore = resourceStore;
        this.tx = resourceStore.createTransaction(session);
        session.getTransactionManager().enlist(tx);
        authorizationProvider = provider;
    }

    private Resource entityToAdapter(MapResourceEntity<K> origEntity) {
        if (origEntity == null) return null;
        // Clone entity before returning back, to avoid giving away a reference to the live object to the caller
        return new MapResourceAdapter<K>(registerEntityForChanges(tx, origEntity), authorizationProvider.getStoreFactory()) {
            @Override
            public String getId() {
                return resourceStore.getKeyConvertor().keyToString(entity.getId());
            }
        };
    }
    
    private ModelCriteriaBuilder<Resource> forResourceServer(String resourceServerId) {
        ModelCriteriaBuilder<Resource> mcb = resourceStore.createCriteriaBuilder();

        return resourceServerId == null
                ? mcb
                : mcb.compare(SearchableFields.RESOURCE_SERVER_ID, Operator.EQ,
                              resourceServerId);
    }

    @Override
    public Resource create(String id, String name, ResourceServer resourceServer, String owner) {
        LOG.tracef("create(%s, %s, %s, %s)%s", id, name, resourceServer, owner, getShortStackTrace());
        // @UniqueConstraint(columnNames = {"NAME", "RESOURCE_SERVER_ID", "OWNER"})
        ModelCriteriaBuilder<Resource> mcb = forResourceServer(resourceServer.getId())
                .compare(SearchableFields.NAME, Operator.EQ, name)
                .compare(SearchableFields.OWNER, Operator.EQ, owner);

        if (tx.getCount(mcb) > 0) {
            throw new ModelDuplicateException("Resource with name '" + name + "' for " + resourceServer.getId() + " already exists for request owner " + owner);
        }

        K uid = id == null ? resourceStore.getKeyConvertor().yieldNewUniqueKey(): resourceStore.getKeyConvertor().fromString(id);
        MapResourceEntity<K> entity = new MapResourceEntity<>(uid);

        entity.setName(name);
        entity.setResourceServerId(resourceServer.getId());
        entity.setOwner(owner);

        tx.create(uid, entity);

        return entityToAdapter(entity);
    }

    @Override
    public void delete(String id) {
        LOG.tracef("delete(%s)%s", id, getShortStackTrace());

        tx.delete(resourceStore.getKeyConvertor().fromString(id));
    }

    @Override
    public Resource findById(String id, String resourceServerId) {
        LOG.tracef("findById(%s, %s)%s", id, resourceServerId, getShortStackTrace());

        return tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                .compare(SearchableFields.ID, Operator.EQ, id))
                .findFirst()
                .map(this::entityToAdapter)
                .orElse(null);
    }

    @Override
    public void findByOwner(String ownerId, String resourceServerId, Consumer<Resource> consumer) {
        findByOwnerFilter(ownerId, resourceServerId, consumer, -1, -1);
    }

    private void findByOwnerFilter(String ownerId, String resourceServerId, Consumer<Resource> consumer, int firstResult, int maxResult) {
        LOG.tracef("findByOwnerFilter(%s, %s, %s, %d, %d)%s", ownerId, resourceServerId, consumer, firstResult, maxResult, getShortStackTrace());
        Comparator<? super MapResourceEntity<K>> c = Comparator.comparing(MapResourceEntity::getId);
        paginatedStream(tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                    .compare(SearchableFields.OWNER, Operator.EQ, ownerId))
                    .sorted(c), firstResult, maxResult)
                .map(this::entityToAdapter)
                .forEach(consumer);
    }

    @Override
    public List<Resource> findByOwner(String ownerId, String resourceServerId, int first, int max) {
        List<Resource> resourceList = new LinkedList<>();

        findByOwnerFilter(ownerId, resourceServerId, resourceList::add, first, max);

        return resourceList;
    }

    @Override
    public List<Resource> findByUri(String uri, String resourceServerId) {
        LOG.tracef("findByUri(%s, %s)%s", uri, resourceServerId, getShortStackTrace());
        
        return tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                    .compare(SearchableFields.URI, Operator.EQ, uri))
                .map(this::entityToAdapter)
                .collect(Collectors.toList());
    }

    @Override
    public List<Resource> findByResourceServer(String resourceServerId) {
        LOG.tracef("findByResourceServer(%s)%s", resourceServerId, getShortStackTrace());

        return tx.getUpdatedNotRemoved(forResourceServer(resourceServerId))
                .map(this::entityToAdapter)
                .collect(Collectors.toList());
    }

    @Override
    public List<Resource> findByResourceServer(Map<Resource.FilterOption, String[]> attributes, String resourceServerId, int firstResult, int maxResult) {
        LOG.tracef("findByResourceServer(%s, %s, %d, %d)%s", attributes, resourceServerId, firstResult, maxResult, getShortStackTrace());
        ModelCriteriaBuilder<Resource> mcb = forResourceServer(resourceServerId).and(
                attributes.entrySet().stream()
                        .map(this::filterEntryToModelCriteriaBuilder)
                        .toArray(ModelCriteriaBuilder[]::new)
        );

        return paginatedStream(tx.getUpdatedNotRemoved(mcb)
                .sorted(MapResourceEntity.COMPARE_BY_NAME), firstResult, maxResult)
                .map(this::entityToAdapter)
                .collect(Collectors.toList());
    }

    private ModelCriteriaBuilder<Resource> filterEntryToModelCriteriaBuilder(Map.Entry<Resource.FilterOption, String[]> entry) {
        Resource.FilterOption name = entry.getKey();
        String[] value = entry.getValue();

        switch (name) {
            case ID:
            case SCOPE_ID:
            case OWNER:
            case URI:
                return resourceStore.createCriteriaBuilder()
                        .compare(name.getSearchableModelField(), Operator.IN, Arrays.asList(value));
            case URI_NOT_NULL:
                return resourceStore.createCriteriaBuilder().compare(SearchableFields.URI, Operator.EXISTS);
            case OWNER_MANAGED_ACCESS:
                return resourceStore.createCriteriaBuilder()
                        .compare(SearchableFields.OWNER_MANAGED_ACCESS, Operator.EQ, Boolean.valueOf(value[0]));
            case EXACT_NAME:
                return resourceStore.createCriteriaBuilder()
                        .compare(SearchableFields.NAME, Operator.EQ, value[0]);
            case NAME:
                return  resourceStore.createCriteriaBuilder()
                        .compare(SearchableFields.NAME, Operator.ILIKE, "%" + value[0] + "%");
            default:
                throw new IllegalArgumentException("Unsupported filter [" + name + "]");

        }
    }

    @Override
    public void findByScope(List<String> scopes, String resourceServerId, Consumer<Resource> consumer) {
        LOG.tracef("findByScope(%s, %s, %s)%s", scopes, resourceServerId, consumer, getShortStackTrace());

        tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                .compare(SearchableFields.SCOPE_ID, Operator.IN, scopes))
                .map(this::entityToAdapter)
                .forEach(consumer);
    }

    @Override
    public Resource findByName(String name, String resourceServerId) {
        return findByName(name, resourceServerId, resourceServerId);
    }

    @Override
    public Resource findByName(String name, String ownerId, String resourceServerId) {
        LOG.tracef("findByName(%s, %s, %s)%s", name, ownerId, resourceServerId, getShortStackTrace());
        return tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                    .compare(SearchableFields.OWNER, Operator.EQ, ownerId)
                    .compare(SearchableFields.NAME, Operator.EQ, name))
                .findFirst()
                .map(this::entityToAdapter)
                .orElse(null);
    }

    @Override
    public void findByType(String type, String resourceServerId, Consumer<Resource> consumer) {
        LOG.tracef("findByType(%s, %s, %s)%s", type, resourceServerId, consumer, getShortStackTrace());
        tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
            .compare(SearchableFields.TYPE, Operator.EQ, type))
            .map(this::entityToAdapter)
            .forEach(consumer);
    }

    @Override
    public void findByType(String type, String owner, String resourceServerId, Consumer<Resource> consumer) {
        LOG.tracef("findByType(%s, %s, %s, %s)%s", type, owner, resourceServerId, consumer, getShortStackTrace());

        ModelCriteriaBuilder<Resource> mcb = forResourceServer(resourceServerId)
                .compare(SearchableFields.TYPE, Operator.EQ, type);

        if (owner != null) {
            mcb = mcb.compare(SearchableFields.OWNER, Operator.EQ, owner);
        }

        tx.getUpdatedNotRemoved(mcb)
                .map(this::entityToAdapter)
                .forEach(consumer);
    }

    @Override
    public void findByTypeInstance(String type, String resourceServerId, Consumer<Resource> consumer) {
        LOG.tracef("findByTypeInstance(%s, %s, %s)%s", type, resourceServerId, consumer, getShortStackTrace());
        tx.getUpdatedNotRemoved(forResourceServer(resourceServerId)
                .compare(SearchableFields.OWNER, Operator.NE, resourceServerId)
                .compare(SearchableFields.TYPE, Operator.EQ, type))
                .map(this::entityToAdapter)
                .forEach(consumer);
    }
}
