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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidationError;

/**
 * <p>The default implementation for {@link Attributes}. Should be reused as much as possible by the different implementations
 * of {@link UserProfileProvider}.
 *
 * <p>One of the main aspects of this implementation is to allow normalizing attributes accordingly to the profile
 * configuration and current context. As such, it provides some common normalization to common profile attributes (e.g.: username,
 * email, first and last names, dynamic read-only attributes).
 *
 * <p>This implementation is not specific to any user profile implementation.
 *
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public final class DefaultAttributes extends HashMap<String, List<String>> implements Attributes {

    /**
     * To reference dynamic attributes that can be configured as read-only when setting up the provider.
     * We should probably remove that once we remove the legacy provider, because this will come from the configuration.
     */
    public static final String READ_ONLY_ATTRIBUTE_KEY = "kc.read.only";

    private final UserProfileContext context;
    private final KeycloakSession session;
    private final Map<String, AttributeMetadata> metadataByAttribute;
    private final UserModel user;

    public DefaultAttributes(UserProfileContext context, Map<String, ?> attributes, UserModel user,
            UserProfileMetadata profileMetadata,
            KeycloakSession session) {
        this.context = context;
        this.user = user;
        this.session = session;
        this.metadataByAttribute = configureMetadata(profileMetadata.getAttributes());
        putAll(Collections.unmodifiableMap(normalizeAttributes(attributes)));
    }

    @Override
    public boolean isReadOnly(String attributeName) {
        return isReadOnlyFromMetadata(attributeName) || isReadOnlyInternalAttribute(attributeName);
    }

    private boolean isReadOnlyFromMetadata(String attributeName) {
        AttributeMetadata attributeMetadata = metadataByAttribute.get(attributeName);

        if (attributeMetadata != null && attributeMetadata.isReadOnly(createAttributeContext(attributeName, attributeMetadata))) {
            return true;
        }
        return false;
    }

    @Override
    public boolean validate(String name, Consumer<ValidationError>... listeners) {
        Entry<String, List<String>> attribute = createAttribute(name);
        List<AttributeMetadata> metadatas = new ArrayList<>();

        metadatas.addAll(Optional.ofNullable(this.metadataByAttribute.get(attribute.getKey()))
                .map(Collections::singletonList).orElse(Collections.emptyList()));
        metadatas.addAll(Optional.ofNullable(this.metadataByAttribute.get(READ_ONLY_ATTRIBUTE_KEY))
                .map(Collections::singletonList).orElse(Collections.emptyList()));

        List<ValidationContext> failingValidators = Collections.emptyList();

        for (AttributeMetadata metadata : metadatas) {
            for (AttributeValidatorMetadata validator : metadata.getValidators()) {
            	ValidationContext vc = validator.validate(createAttributeContext(attribute, metadata)); 
                if (!vc.isValid()) {
                    if (failingValidators.equals(Collections.emptyList())) {
                        failingValidators = new ArrayList<>();
                    }
                    failingValidators.add(vc);
                }
            }
        }

        if (listeners != null) {
            for (ValidationContext failingValidator : failingValidators) {
                for (Consumer<ValidationError> consumer : listeners) {
                    for(ValidationError err: failingValidator.getErrors()) {
                        consumer.accept(err);
                    }
                }
            }
        }

        return failingValidators.isEmpty();
    }

    @Override
    public List<String> getValues(String name) {
        return getOrDefault(name, EMPTY_VALUE);
    }

    @Override
    public boolean contains(String name) {
        return containsKey(name);
    }

    @Override
    public Set<String> nameSet() {
        return keySet();
    }

    @Override
    public Set<Entry<String, List<String>>> attributeSet() {
        return entrySet();
    }

    private AttributeContext createAttributeContext(Entry<String, List<String>> attribute, AttributeMetadata metadata) {
        return new AttributeContext(context, session, attribute, user, metadata);
    }

    private AttributeContext createAttributeContext(String attributeName, AttributeMetadata metadata) {
        return createAttributeContext(createAttribute(attributeName), metadata);
    }

    private Map<String, AttributeMetadata> configureMetadata(List<AttributeMetadata> attributes) {
        Map<String, AttributeMetadata> metadatas = new HashMap<>();

        for (AttributeMetadata metadata : attributes) {
            // checks whether the attribute is selected for the current profile
            if (metadata.isSelected(createAttributeContext(metadata.getName(), metadata))) {
                metadatas.put(metadata.getName(), metadata);
            }
        }

        return metadatas;
    }

    private SimpleImmutableEntry<String, List<String>> createAttribute(String name) {
        return new SimpleImmutableEntry<String, List<String>>(name, null) {
            @Override
            public List<String> getValue() {
                List<String> values = get(name);

                if (values == null) {
                    return EMPTY_VALUE;
                }

                return values;
            }
        };
    }

    /**
     * Normalizes the given {@code attributes} (as they were provided when creating a profile) accordingly to the
     * profile configuration and the current context.
     *
     * @param attributes the denormalized map of attributes
     *
     * @return a normalized map of attributes
     */
    private Map<String, List<String>> normalizeAttributes(Map<String, ?> attributes) {
        Map<String, List<String>> newAttributes = new HashMap<>();
        RealmModel realm = session.getContext().getRealm();

        if (attributes != null && !attributes.isEmpty()) {
            for (Map.Entry<String, ?> entry : attributes.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();

                if (!isSupportedAttribute(key)) {
                    continue;
                }

                if (key.startsWith(Constants.USER_ATTRIBUTES_PREFIX)) {
                    key = key.substring(Constants.USER_ATTRIBUTES_PREFIX.length());
                }

                List<String> values;

                if (value instanceof String) {
                    values = Collections.singletonList((String) value);
                } else {
                    values = (List<String>) value;
                }

                if (key.equals(UserModel.USERNAME)) {
                    values = Collections.singletonList(values.get(0).toLowerCase());
                }

                if (isReadOnlyFromMetadata(key)) {
                    // only revert attribute values if not an internal read-only attribute
                    // for backward compatibility changing these attributes should cause validation errors
                    // ideally, we should just ignore and remove this check
                    if (user == null) {
                        values = EMPTY_VALUE;
                    } else {
                        values = user.getAttributeStream(key).collect(Collectors.toList());
                    }
                }

                newAttributes.put(key, Collections.unmodifiableList(values));
            }
        }

        // the profile should always hold all attributes defined in the config
        for (String attributeName : metadataByAttribute.keySet()) {
            if (isSupportedAttribute(attributeName)) {
                newAttributes.computeIfAbsent(attributeName, s -> EMPTY_VALUE);
            }
        }

        if (user != null) {
            List<String> username = newAttributes.get(UserModel.USERNAME);

            if (username == null || username.isEmpty() || (!realm.isEditUsernameAllowed() && UserProfileContext.USER_API.equals(context))) {
                newAttributes.put(UserModel.USERNAME, Collections.singletonList(user.getUsername()));
            }
        }

        List<String> email = newAttributes.get(UserModel.EMAIL);

        if (email != null && realm.isRegistrationEmailAsUsername()) {
            newAttributes.put(UserModel.USERNAME, email);
        }

        return newAttributes;
    }

    /**
     * <p>Checks whether an attribute is support by the profile configuration and the current context.
     *
     * <p>This method can be used to avoid unexpected attributes from being added as an attribute because
     * the attribute source is a regular {@link Map} and not normalized.
     *
     * @param name the name of the attribute
     * @return
     */
    private boolean isSupportedAttribute(String name) {
        if (READ_ONLY_ATTRIBUTE_KEY.equals(name)) {
            return false;
        }

        if (metadataByAttribute.containsKey(name)) {
            return true;
        }

        // expect any attribute if managing the user profile using REST
        if (UserProfileContext.USER_API.equals(context) || UserProfileContext.ACCOUNT.equals(context)) {
            return true;
        }

        // attributes managed using forms with a pre-defined prefix are supported
        if (name.startsWith(Constants.USER_ATTRIBUTES_PREFIX)) {
            return true;
        }

        if (isReadOnly(name)) {
            return true;
        }

        // checks whether the attribute is a core attribute
        return UserModel.USERNAME.equals(name) || UserModel.EMAIL.equals(name) || UserModel.LAST_NAME.equals(name) || UserModel.FIRST_NAME.equals(name);
    }

    private boolean isReadOnlyInternalAttribute(String attributeName) {
        // read-only can be configured through the provider so we try to validate global validations
        AttributeMetadata readonlyMetadata = metadataByAttribute.get(READ_ONLY_ATTRIBUTE_KEY);

        if (readonlyMetadata == null) {
            return false;
        }

        SimpleImmutableEntry<String, List<String>> attribute = createAttribute(attributeName);

        for (AttributeValidatorMetadata validator : readonlyMetadata.getValidators()) {
        	ValidationContext vc = validator.validate(createAttributeContext(attribute, readonlyMetadata));
            if (!vc.isValid()) {
                return true;
            }
        }

        return false;
    }
}
