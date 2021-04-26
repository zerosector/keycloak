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

package org.keycloak.models.map.group;

import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.GroupProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleContainerModel.RoleRemovedEvent;
import org.keycloak.models.RoleModel;
import org.keycloak.models.map.common.AbstractMapProviderFactory;
import org.keycloak.models.map.storage.MapStorage;
import org.keycloak.models.map.storage.MapStorageProvider;

import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import java.util.UUID;

/**
 *
 * @author mhajas
 */
public class MapGroupProviderFactory extends AbstractMapProviderFactory<GroupProvider> implements GroupProviderFactory, ProviderEventListener {

    private MapStorage<UUID, MapGroupEntity, GroupModel> store;

    private Runnable onClose;

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        MapStorageProvider sp = (MapStorageProvider) factory.getProviderFactory(MapStorageProvider.class);
        this.store = sp.getStorage("groups", UUID.class, MapGroupEntity.class, GroupModel.class);

        factory.register(this);
        onClose = () -> factory.unregister(this);
    }


    @Override
    public MapGroupProvider create(KeycloakSession session) {
        return new MapGroupProvider(session, store);
    }

    @Override
    public void close() {
        super.close();
        onClose.run();
    }

    @Override
    public void onEvent(ProviderEvent event) {
        if (event instanceof RoleContainerModel.RoleRemovedEvent) {
            RoleRemovedEvent e = (RoleContainerModel.RoleRemovedEvent) event;
            RoleModel role = e.getRole();
            RoleContainerModel container = role.getContainer();
            RealmModel realm;
            if (container instanceof RealmModel) {
                realm = (RealmModel) container;
            } else if (container instanceof ClientModel) {
                realm = ((ClientModel) container).getRealm();
            } else {
                return;
            }
            create(e.getKeycloakSession()).preRemove(realm, role);
        }
    }
}
