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
package org.keycloak.protocol.oidc.grants.ciba.endpoints;

import static org.keycloak.protocol.oidc.OIDCLoginProtocol.ID_TOKEN_HINT;
import static org.keycloak.protocol.oidc.OIDCLoginProtocol.LOGIN_HINT_PARAM;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.CibaConfig;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OAuth2DeviceCodeModel;
import org.keycloak.models.OAuth2DeviceTokenStoreProvider;
import org.keycloak.models.OAuth2DeviceUserCodeModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.grants.ciba.CibaGrantType;
import org.keycloak.protocol.oidc.grants.ciba.channel.AuthenticationChannelProvider;
import org.keycloak.protocol.oidc.grants.ciba.channel.CIBAAuthenticationRequest;
import org.keycloak.protocol.oidc.grants.ciba.resolvers.CIBALoginUserResolver;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.util.JsonSerialization;
import org.keycloak.utils.ProfileHelper;

public class BackchannelAuthenticationEndpoint extends AbstractCibaEndpoint {

    private final RealmModel realm;

    public BackchannelAuthenticationEndpoint(KeycloakSession session, EventBuilder event) {
        super(session, event);
        this.realm = session.getContext().getRealm();
        event.event(EventType.LOGIN);
    }

    @POST
    @NoCache
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response processGrantRequest(@Context HttpRequest httpRequest) {
        CIBAAuthenticationRequest request = authorizeClient(httpRequest.getDecodedFormParameters());

        try {
            String authReqId = request.serialize(session);
            AuthenticationChannelProvider provider = session.getProvider(AuthenticationChannelProvider.class);

            if (provider == null) {
                throw new RuntimeException("Authentication Channel Provider not found.");
            }

            CIBALoginUserResolver resolver = session.getProvider(CIBALoginUserResolver.class);

            if (resolver == null) {
                throw new RuntimeException("CIBA Login User Resolver not setup properly.");
            }

            UserModel user = request.getUser();

            String infoUsedByAuthentication = resolver.getInfoUsedByAuthentication(user);

            if (provider.requestAuthentication(request, infoUsedByAuthentication)) {
                CibaConfig cibaPolicy = realm.getCibaPolicy();
                int poolingInterval = cibaPolicy.getPoolingInterval();

                storeAuthenticationRequest(request, cibaPolicy);

                ObjectNode response = JsonSerialization.createObjectNode();

                response.put(CibaGrantType.AUTH_REQ_ID, authReqId)
                        .put(OAuth2Constants.EXPIRES_IN, cibaPolicy.getExpiresIn());

                if (poolingInterval > 0) {
                    response.put(OAuth2Constants.INTERVAL, poolingInterval);
                }

                return Response.ok(JsonSerialization.writeValueAsBytes(response))
                        .build();
            }
        } catch (Exception e) {
            throw new ErrorResponseException(OAuthErrorException.SERVER_ERROR, "Failed to send authentication request", Response.Status.SERVICE_UNAVAILABLE);
        }

        throw new ErrorResponseException(OAuthErrorException.SERVER_ERROR, "Unexpected response from authentication device", Response.Status.SERVICE_UNAVAILABLE);
    }

    /**
     * TODO: Leverage the device code storage for tracking authentication requests. Not sure if we need a specific storage,
     * but probably make the {@link OAuth2DeviceTokenStoreProvider} more generic for ciba, device, or any other use case
     * that relies on cross-references for unsolicited user authentication requests from devices.
     */
    private void storeAuthenticationRequest(CIBAAuthenticationRequest request, CibaConfig cibaConfig) {
        ClientModel client = request.getClient();
        int expiresIn = cibaConfig.getExpiresIn();
        int poolingInterval = cibaConfig.getPoolingInterval();

        OAuth2DeviceCodeModel deviceCode = OAuth2DeviceCodeModel.create(realm, client,
                request.getId(), request.getScope(), null, expiresIn, poolingInterval,
                Collections.emptyMap());
        String authResultId = request.getAuthResultId();
        OAuth2DeviceUserCodeModel userCode = new OAuth2DeviceUserCodeModel(realm, deviceCode.getDeviceCode(),
                authResultId);

        // To inform "expired_token" to the client, the lifespan of the cache provider is longer than device code
        int lifespanSeconds = expiresIn + poolingInterval + 10;

        OAuth2DeviceTokenStoreProvider store = session.getProvider(OAuth2DeviceTokenStoreProvider.class);

        store.put(deviceCode, userCode, lifespanSeconds);
    }

    private CIBAAuthenticationRequest authorizeClient(MultivaluedMap<String, String> params) {
        ClientModel client = authenticateClient();
        UserModel user = resolveUser(params, realm.getCibaPolicy().getAuthRequestedUserHint());

        CIBAAuthenticationRequest request = new CIBAAuthenticationRequest(session, user, client);

        request.setClient(client);

        String scope = params.getFirst(OAuth2Constants.SCOPE);

        if (scope == null)
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "missing parameter : scope",
                    Response.Status.BAD_REQUEST);

        request.setScope(scope);

        // optional parameters
        if (params.getFirst(CibaGrantType.BINDING_MESSAGE) != null) request.setBindingMessage(params.getFirst(CibaGrantType.BINDING_MESSAGE));
        if (params.getFirst(OAuth2Constants.ACR_VALUES) != null) request.setAcrValues(params.getFirst(OAuth2Constants.ACR_VALUES));

        CibaConfig policy = realm.getCibaPolicy();

        // create JWE encoded auth_req_id from Auth Req ID.
        Integer expiresIn = policy.getExpiresIn();
        String requestedExpiry = params.getFirst(CibaGrantType.REQUESTED_EXPIRY);

        if (requestedExpiry != null) {
            expiresIn = Integer.valueOf(requestedExpiry);
        }

        request.exp(Time.currentTime() + expiresIn.longValue());

        StringBuilder scopes = new StringBuilder(Optional.ofNullable(request.getScope()).orElse(""));
        client.getClientScopes(true)
                .forEach((key, value) -> {
                    if (value.isDisplayOnConsentScreen())
                        scopes.append(" ").append(value.getName());
                });
        request.setScope(scopes.toString());

        String clientNotificationToken = params.getFirst(CibaGrantType.CLIENT_NOTIFICATION_TOKEN);

        if (clientNotificationToken != null) {
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST,
                    "Ping and push modes not supported. Use poll mode instead.", Response.Status.BAD_REQUEST);
        }

        String userCode = params.getFirst(OAuth2Constants.USER_CODE);

        if (userCode != null) {
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "User code not supported",
                    Response.Status.BAD_REQUEST);
        }

        return request;
    }

    private UserModel resolveUser(MultivaluedMap<String, String> params, String authRequestedUserHint) {
        CIBALoginUserResolver resolver = session.getProvider(CIBALoginUserResolver.class);

        if (resolver == null) {
            throw new RuntimeException("CIBA Login User Resolver not setup properly.");
        }

        String userHint;
        UserModel user;

        if (authRequestedUserHint.equals(LOGIN_HINT_PARAM)) {
            userHint = params.getFirst(LOGIN_HINT_PARAM);
            if (userHint == null)
                throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "missing parameter : login_hint",
                        Response.Status.BAD_REQUEST);
            user = resolver.getUserFromLoginHint(userHint);
        } else if (authRequestedUserHint.equals(ID_TOKEN_HINT)) {
            userHint = params.getFirst(ID_TOKEN_HINT);
            if (userHint == null)
                throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "missing parameter : id_token_hint",
                        Response.Status.BAD_REQUEST);
            user = resolver.getUserFromIdTokenHint(userHint);
        } else if (authRequestedUserHint.equals(CibaGrantType.LOGIN_HINT_TOKEN)) {
            userHint = params.getFirst(CibaGrantType.LOGIN_HINT_TOKEN);
            if (userHint == null)
                throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "missing parameter : login_hint_token",
                        Response.Status.BAD_REQUEST);
            user = resolver.getUserFromLoginHintToken(userHint);
        } else {
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST,
                    "invalid user hint", Response.Status.BAD_REQUEST);
        }

        if (user == null || !user.isEnabled())
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "invalid user", Response.Status.BAD_REQUEST);

        return user;
    }
}
