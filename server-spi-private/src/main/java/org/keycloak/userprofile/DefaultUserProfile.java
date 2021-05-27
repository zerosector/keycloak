/*
 *
 *  * Copyright 2021  Red Hat, Inc. and/or its affiliates
 *  * and other contributors as indicated by the @author tags.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.keycloak.userprofile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.keycloak.models.ModelException;
import org.keycloak.models.UserModel;

/**
 * <p>The default implementation for {@link UserProfile}. Should be reused as much as possible by the different implementations
 * of {@link UserProfileProvider}.
 *
 * <p>This implementation is not specific to any user profile implementation.
 *
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public final class DefaultUserProfile implements UserProfile {

    private final Function<Attributes, UserModel> userSupplier;
    private final Attributes attributes;
    private boolean validated;
    private UserModel user;

    public DefaultUserProfile(Attributes attributes, Function<Attributes, UserModel> userCreator, UserModel user) {
        this.userSupplier = userCreator;
        this.attributes = attributes;
        this.user = user;
    }

    @Override
    public void validate() {
        ValidationException validationException = new ValidationException();

        for (String attributeName : attributes.nameSet()) {
            this.attributes.validate(attributeName,
                    (error) -> validationException.addError(error));
        }

        if (validationException.hasError()) {
            throw validationException;
        }

        validated = true;
    }

    @Override
    public UserModel create() throws ValidationException {
        if (user != null) {
            throw new RuntimeException("User already created");
        }

        if (!validated) {
            validate();
        }

        user = userSupplier.apply(this.attributes);

        return updateInternal(user, false);
    }

    @Override
    public void update(boolean removeAttributes, BiConsumer<String, UserModel>... changeListener) {
        if (!validated) {
            validate();
        }

        updateInternal(user, removeAttributes, changeListener);
    }

    private UserModel updateInternal(UserModel user, boolean removeAttributes, BiConsumer<String, UserModel>... changeListener) {
        if (user == null) {
            throw new RuntimeException("No user model provided for persisting changes");
        }

        try {
            for (Map.Entry<String, List<String>> attribute : attributes.attributeSet()) {
                String name = attribute.getKey();

                if (attributes.isReadOnly(name)) {
                    continue;
                }

                List<String> currentValue = user.getAttributeStream(name).filter(Objects::nonNull).collect(Collectors.toList());
                List<String> updatedValue = attribute.getValue().stream().filter(Objects::nonNull).collect(Collectors.toList());

                if (currentValue.size() != updatedValue.size() || !currentValue.containsAll(updatedValue)) {
                    user.setAttribute(name, updatedValue);
                    for (BiConsumer<String, UserModel> listener : changeListener) {
                        listener.accept(name, user);
                    }
                }
            }

            // this is a workaround for supporting contexts where the decision to whether attributes should be removed depends on
            // specific aspect. For instance, old account should never remove attributes, the admin rest api should only remove if
            // the attribute map was sent.
            if (removeAttributes) {
                Set<String> attrsToRemove = new HashSet<>(user.getAttributes().keySet());
                attrsToRemove.removeAll(attributes.nameSet());

                for (String attr : attrsToRemove) {
                    if (this.attributes.isReadOnly(attr)) {
                        continue;
                    }
                    user.removeAttribute(attr);
                }
            }
        } catch (ModelException me) {
            // some client code relies on this exception to react to exceptions from the storage
            throw me;
        } catch (Exception cause) {
            throw new RuntimeException("Unexpected error when persisting user profile", cause);
        }

        return user;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }
}
