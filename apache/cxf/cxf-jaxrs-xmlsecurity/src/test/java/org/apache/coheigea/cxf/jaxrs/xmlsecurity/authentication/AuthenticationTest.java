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
package org.apache.coheigea.cxf.jaxrs.xmlsecurity.authentication;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.coheigea.cxf.jaxrs.xmlsecurity.common.Number;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.rs.security.xml.XmlSigOutInterceptor;
import org.apache.cxf.testutil.common.AbstractBusClientServerTestBase;
import org.junit.BeforeClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test Authentication via JAX-RS XML Signature.
 */
public class AuthenticationTest extends AbstractBusClientServerTestBase {

    private static final String PORT = allocatePort(Server.class);

    @BeforeClass
    public static void startServers() throws Exception {
        assertTrue(
                   "Server failed to launch",
                   // run the server in the same process
                   // set this to false to fork
                   launchServer(Server.class, true)
        );
    }

    @org.junit.Test
    public void testAuthenticatedRequest() throws Exception {

        URL busFile = AuthenticationTest.class.getResource("cxf-client.xml");

        String address = "http://localhost:" + PORT + "/doubleit/services";
        WebClient client = WebClient.create(address, busFile.toString());
        client = client.type("application/xml");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("ws-security.callback-handler",
                       "org.apache.coheigea.cxf.jaxrs.xmlsecurity.common.CommonCallbackHandler");
        properties.put("ws-security.signature.username", "myclientkey");

        properties.put("ws-security.signature.properties", "clientKeystore.properties");
        WebClient.getConfig(client).getRequestContext().putAll(properties);

        XmlSigOutInterceptor sigInterceptor = new XmlSigOutInterceptor();
        WebClient.getConfig(client).getOutInterceptors().add(sigInterceptor);

        Number numberToDouble = new Number();
        numberToDouble.setDescription("This is the number to double");
        numberToDouble.setNumber(25);

        Response response = client.post(numberToDouble);
        assertEquals(response.getStatus(), 200);
        assertEquals(response.readEntity(Number.class).getNumber(), 50);
    }

    @org.junit.Test
    public void testUnauthenticatedRequest() throws Exception {

        URL busFile = AuthenticationTest.class.getResource("cxf-client.xml");

        String address = "http://localhost:" + PORT + "/doubleit/services";
        WebClient client = WebClient.create(address, busFile.toString());
        client = client.type("application/xml");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("ws-security.callback-handler",
                       "org.apache.coheigea.cxf.jaxrs.xmlsecurity.common.CommonCallbackHandler");
        properties.put("ws-security.signature.username", "imposter");

        properties.put("ws-security.signature.properties", "imposterKeystore.properties");
        WebClient.getConfig(client).getRequestContext().putAll(properties);

        XmlSigOutInterceptor sigInterceptor = new XmlSigOutInterceptor();
        WebClient.getConfig(client).getOutInterceptors().add(sigInterceptor);

        Number numberToDouble = new Number();
        numberToDouble.setDescription("This is the number to double");
        numberToDouble.setNumber(25);

        Response response = client.post(numberToDouble);
        assertEquals(response.getStatus(), 400);
    }

}