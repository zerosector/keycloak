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

package org.keycloak.models.map.authorization.adapter;

import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.StoreFactory;

import org.keycloak.models.map.authorization.entity.MapResourceEntity;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class MapResourceAdapter<K> extends AbstractResourceModel<MapResourceEntity<K>> {

    public MapResourceAdapter(MapResourceEntity<K> entity, StoreFactory storeFactory) {
        super(entity, storeFactory);
    }

    @Override
    public String getName() {
        return entity.getName();
    }

    @Override
    public void setName(String name) {
        throwExceptionIfReadonly();
        entity.setName(name);
    }

    @Override
    public String getDisplayName() {
        return entity.getDisplayName();
    }

    @Override
    public void setDisplayName(String name) {
        throwExceptionIfReadonly();
        entity.setDisplayName(name);
    }

    @Override
    public Set<String> getUris() {
        return entity.getUris();
    }

    @Override
    public void updateUris(Set<String> uri) {
        throwExceptionIfReadonly();
        entity.setUris(uri);
    }

    @Override
    public String getType() {
        return entity.getType();
    }

    @Override
    public void setType(String type) {
        throwExceptionIfReadonly();
        entity.setType(type);
    }

    @Override
    public List<Scope> getScopes() {
        return entity.getScopeIds().stream()
                .map(id -> storeFactory
                        .getScopeStore().findById(id, entity.getResourceServerId()))
                .collect(Collectors.toList());
    }

    @Override
    public String getIconUri() {
        return entity.getIconUri();
    }

    @Override
    public void setIconUri(String iconUri) {
        throwExceptionIfReadonly();
        entity.setIconUri(iconUri);
    }

    @Override
    public String getResourceServer() {
        return entity.getResourceServerId();
    }

    @Override
    public String getOwner() {
        return entity.getOwner();
    }

    @Override
    public boolean isOwnerManagedAccess() {
        return entity.isOwnerManagedAccess();
    }

    @Override
    public void setOwnerManagedAccess(boolean ownerManagedAccess) {
        throwExceptionIfReadonly();
        entity.setOwnerManagedAccess(ownerManagedAccess);
    }

    @Override
    public void updateScopes(Set<Scope> scopes) {
        throwExceptionIfReadonly();
        entity.setScopeIds(scopes.stream().map(Scope::getId).collect(Collectors.toSet()));
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(entity.getAttributes()));
    }

    @Override
    public String getSingleAttribute(String name) {
        return entity.getSingleAttribute(name);
    }

    @Override
    public List<String> getAttribute(String name) {
        return entity.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        throwExceptionIfReadonly();
        entity.setAttribute(name, values);
    }

    @Override
    public void removeAttribute(String name) {
        throwExceptionIfReadonly();
        entity.removeAttribute(name);
    }

    @Override
    public String toString() {
        return String.format("%s@%08x", getId(), System.identityHashCode(this));
    }
}
