/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.coheigea.cxf.oidc.userinfo;

import java.net.URL;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;

import org.apache.coheigea.cxf.oidc.provider.OIDCProviderServer;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.provider.json.JSONProvider;
import org.apache.cxf.jaxrs.provider.json.JsonMapObjectProvider;
import org.apache.cxf.rs.security.jose.jaxrs.JsonWebKeysProvider;
import org.apache.cxf.rs.security.jose.jwa.SignatureAlgorithm;
import org.apache.cxf.rs.security.jose.jwe.JweJwtCompactConsumer;
import org.apache.cxf.rs.security.jose.jws.JwsJwtCompactConsumer;
import org.apache.cxf.rs.security.jose.jwt.JwtConstants;
import org.apache.cxf.rs.security.jose.jwt.JwtToken;
import org.apache.cxf.rs.security.oauth2.common.ClientAccessToken;
import org.apache.cxf.rs.security.oauth2.common.OAuthAuthorizationData;
import org.apache.cxf.rs.security.oauth2.provider.OAuthJSONProvider;
import org.apache.cxf.rs.security.oidc.common.UserInfo;
import org.apache.cxf.testutil.common.AbstractBusClientServerTestBase;
import org.apache.wss4j.common.util.Loader;
import org.junit.Assert;
import org.junit.BeforeClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Some unit tests for the UserInfo Service in OpenId Connect. This can be used to return the User's claims given
 * an access token.
 */
public class UserInfoTest extends AbstractBusClientServerTestBase {
    
    static final String PORT = allocatePort(OIDCProviderServer.class);
    static final String USERINFO_PORT = allocatePort(UserInfoServer.class);
    
    @BeforeClass
    public static void startServers() throws Exception {
        assertTrue(
                   "Server failed to launch",
                   // run the server in the same process
                   // set this to false to fork
                   launchServer(UserInfoServer.class, true)
        );
        assertTrue(
                "Server failed to launch",
                // run the server in the same process
                // set this to false to fork
                launchServer(OIDCProviderServer.class, true)
        );
    }
    
    @org.junit.Test
    public void testPlainUserInfo() throws Exception {
        URL busFile = UserInfoTest.class.getResource("cxf-client.xml");
        
        String address = "https://localhost:" + PORT + "/services/";
        WebClient client = WebClient.create(address, setupProviders(), "alice", "security", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        // Get Authorization Code
        String code = getAuthorizationCode(client, "openid");
        assertNotNull(code);
        
        // Now get the access token
        client = WebClient.create(address, setupProviders(), "consumer-id", "this-is-a-secret", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        ClientAccessToken accessToken = getAccessTokenWithAuthorizationCode(client, code);
        assertNotNull(accessToken.getTokenKey());
        assertTrue(accessToken.getApprovedScope().contains("openid"));
        
        // Now invoke on the UserInfo service with the access token
        String userInfoAddress = "https://localhost:" + USERINFO_PORT + "/services/plain/userinfo";
        WebClient userInfoClient = WebClient.create(userInfoAddress, Collections.singletonList(new JsonMapObjectProvider()), 
                                                    busFile.toString());
        userInfoClient.accept("application/json");
        userInfoClient.header("Authorization", "Bearer " + accessToken.getTokenKey());
        
        Response serviceResponse = userInfoClient.get();
        assertEquals(serviceResponse.getStatus(), 200);
        
        UserInfo userInfo = serviceResponse.readEntity(UserInfo.class);
        assertNotNull(userInfo);
        
        assertEquals("alice", userInfo.getSubject());
        assertEquals("consumer-id", userInfo.getAudience());
    }
    
    @org.junit.Test
    public void testSignedUserInfo() throws Exception {
        URL busFile = UserInfoTest.class.getResource("cxf-client.xml");
        
        String address = "https://localhost:" + PORT + "/services/";
        WebClient client = WebClient.create(address, setupProviders(), "alice", "security", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        // Get Authorization Code
        String code = getAuthorizationCode(client, "openid");
        assertNotNull(code);
        
        // Now get the access token
        client = WebClient.create(address, setupProviders(), "consumer-id", "this-is-a-secret", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        ClientAccessToken accessToken = getAccessTokenWithAuthorizationCode(client, code);
        assertNotNull(accessToken.getTokenKey());
        assertTrue(accessToken.getApprovedScope().contains("openid"));
        
        // Now invoke on the UserInfo service with the access token
        String userInfoAddress = "https://localhost:" + USERINFO_PORT + "/services/signed/userinfo";
        WebClient userInfoClient = WebClient.create(userInfoAddress, busFile.toString());
        
        userInfoClient.accept("application/jwt");
        userInfoClient.header("Authorization", "Bearer " + accessToken.getTokenKey());
        
        Response serviceResponse = userInfoClient.get();
        assertEquals(serviceResponse.getStatus(), 200);
        
        String token = serviceResponse.readEntity(String.class);
        assertNotNull(token);
        
        JwsJwtCompactConsumer jwtConsumer = new JwsJwtCompactConsumer(token);
        JwtToken jwt = jwtConsumer.getJwtToken();

        assertEquals("alice", jwt.getClaim(JwtConstants.CLAIM_SUBJECT));
        assertEquals("consumer-id", jwt.getClaim(JwtConstants.CLAIM_AUDIENCE));
        
        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(Loader.getResource("servicestore.jks").openStream(), "sspass".toCharArray());
        Certificate cert = keystore.getCertificate("myservicekey");
        assertNotNull(cert);

        assertTrue(jwtConsumer.verifySignatureWith((X509Certificate)cert, 
                                                          SignatureAlgorithm.RS256));
    }
    
    @org.junit.Test
    public void testEncryptedUserInfo() throws Exception {
        URL busFile = UserInfoTest.class.getResource("cxf-client.xml");
        
        String address = "https://localhost:" + PORT + "/services/";
        WebClient client = WebClient.create(address, setupProviders(), "alice", "security", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        // Get Authorization Code
        String code = getAuthorizationCode(client, "openid");
        assertNotNull(code);
        
        // Now get the access token
        client = WebClient.create(address, setupProviders(), "consumer-id", "this-is-a-secret", busFile.toString());
        // Save the Cookie for the second request...
        WebClient.getConfig(client).getRequestContext().put(
            org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);
        
        ClientAccessToken accessToken = getAccessTokenWithAuthorizationCode(client, code);
        assertNotNull(accessToken.getTokenKey());
        assertTrue(accessToken.getApprovedScope().contains("openid"));
        
        // Now invoke on the UserInfo service with the access token
        String userInfoAddress = "https://localhost:" + USERINFO_PORT + "/services/encrypted/userinfo";
        WebClient userInfoClient = WebClient.create(userInfoAddress, busFile.toString());
        
        userInfoClient.accept("application/jwt");
        userInfoClient.header("Authorization", "Bearer " + accessToken.getTokenKey());
        
        Response serviceResponse = userInfoClient.get();
        assertEquals(serviceResponse.getStatus(), 200);
        
        String token = serviceResponse.readEntity(String.class);
        assertNotNull(token);
        
        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(Loader.getResource("clientstore.jks").openStream(), "cspass".toCharArray());
        
        JweJwtCompactConsumer jwtConsumer = new JweJwtCompactConsumer(token);
        PrivateKey privateKey = (PrivateKey)keystore.getKey("myclientkey", "ckpass".toCharArray());
        JwtToken jwt = jwtConsumer.decryptWith(privateKey);

        assertEquals("alice", jwt.getClaim(JwtConstants.CLAIM_SUBJECT));
        assertEquals("consumer-id", jwt.getClaim(JwtConstants.CLAIM_AUDIENCE));
    }
    
    private String getAuthorizationCode(WebClient client, String scope) {
        return getAuthorizationCode(client, scope, null, null, "consumer-id");
    }
    
    private String getAuthorizationCode(WebClient client, String scope,
                                        String nonce, String state, String consumerId) {
        // Make initial authorization request
        client.type("application/json").accept("application/json");
        client.query("client_id", consumerId);
        client.query("redirect_uri", "http://www.blah.apache.org");
        client.query("response_type", "code");
        if (scope != null) {
            client.query("scope", scope);
        }
        if (nonce != null) {
            client.query("nonce", nonce);
        }
        if (state != null) {
            client.query("state", state);
        }
        client.path("authorize/");
        Response response = client.get();
        
        OAuthAuthorizationData authzData = response.readEntity(OAuthAuthorizationData.class);
        
        // Now call "decision" to get the authorization code grant
        client.path("decision");
        client.type("application/x-www-form-urlencoded");
        
        Form form = new Form();
        form.param("session_authenticity_token", authzData.getAuthenticityToken());
        form.param("client_id", authzData.getClientId());
        form.param("redirect_uri", authzData.getRedirectUri());
        if (authzData.getNonce() != null) {
            form.param("nonce", authzData.getNonce());
        }
        if (authzData.getProposedScope() != null) {
            form.param("scope", authzData.getProposedScope());
        }
        if (authzData.getState() != null) {
            form.param("state", authzData.getState());
        }
        form.param("oauthDecision", "allow");
        
        response = client.post(form);
        String location = response.getHeaderString("Location"); 
        if (state != null) {
            assertTrue(location.contains("state=" + state));
        }
        
        return getSubstring(location, "code");
    }
    
    private ClientAccessToken getAccessTokenWithAuthorizationCode(WebClient client, String code) {
        return getAccessTokenWithAuthorizationCode(client, code, "consumer-id", null);
    }
    
    private ClientAccessToken getAccessTokenWithAuthorizationCode(WebClient client, 
                                                                  String code,
                                                                  String consumerId,
                                                                  String audience) {
        client.type("application/x-www-form-urlencoded").accept("application/json");
        client.path("token");
        
        Form form = new Form();
        form.param("grant_type", "authorization_code");
        form.param("code", code);
        form.param("client_id", consumerId);
        if (audience != null) {
            form.param("audience", audience);
        }
        Response response = client.post(form);
        
        return response.readEntity(ClientAccessToken.class);
    }
    
    private String getSubstring(String parentString, String substringName) {
        String foundString = 
            parentString.substring(parentString.indexOf(substringName + "=") + (substringName + "=").length());
        int ampersandIndex = foundString.indexOf('&');
        if (ampersandIndex < 1) {
            ampersandIndex = foundString.length();
        }
        return foundString.substring(0, ampersandIndex);
    }
    
    private static List<Object> setupProviders() {
        List<Object> providers = new ArrayList<Object>();
        JSONProvider<OAuthAuthorizationData> jsonP = new JSONProvider<OAuthAuthorizationData>();
        jsonP.setNamespaceMap(Collections.singletonMap("http://org.apache.cxf.rs.security.oauth",
                                                       "ns2"));
        providers.add(jsonP);
        providers.add(new OAuthJSONProvider());
        providers.add(new JsonWebKeysProvider());
        providers.add(new JsonMapObjectProvider());
        
        return providers;
    }
    
}