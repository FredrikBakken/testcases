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
package org.apache.coheigea.cxf.ocsp.tls;

import java.net.URL;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.xml.namespace.QName;
import javax.xml.ws.Service;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.spring.SpringBusFactory;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.testutil.common.AbstractBusClientServerTestBase;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.xml.security.utils.ClassLoaderUtils;
import org.example.contract.doubleit.DoubleItPortType;
import org.junit.BeforeClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Some test-cases where a SOAP client request over TLS, where the client uses OCSP to validate
 * that the server's certificate is valid. It contains two test-cases, one where TLS is configured
 * in code, and one where it is configured in a spring configuration file.
 *
 * Prerequisite: Launch OpenSSL via (pass phrase: security):
 *
 * openssl ocsp -index ca.db.index -port 12345 -text -rkey wss40CAKey.pem -CA wss40CA.pem -rsigner wss40CA.pem
 */
public class TLSOCSPTest extends AbstractBusClientServerTestBase {

    private static final String NAMESPACE = "http://www.example.org/contract/DoubleIt";
    private static final QName SERVICE_QNAME = new QName(NAMESPACE, "DoubleItService");

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
    public void testTLSOCSPPass() throws Exception {
        try {
            Security.setProperty("ocsp.responderURL", "http://localhost:12345");
            Security.setProperty("ocsp.enable", "true");

            SpringBusFactory bf = new SpringBusFactory();
            URL busFile = TLSOCSPTest.class.getResource("cxf-client.xml");

            Bus bus = bf.createBus(busFile.toString());
            SpringBusFactory.setDefaultBus(bus);
            SpringBusFactory.setThreadDefaultBus(bus);

            URL wsdl = TLSOCSPTest.class.getResource("DoubleIt.wsdl");
            Service service = Service.create(wsdl, SERVICE_QNAME);
            QName portQName = new QName(NAMESPACE, "DoubleItTLSOCSPPort");
            DoubleItPortType transportPort =
                service.getPort(portQName, DoubleItPortType.class);
            updateAddressPort(transportPort, PORT);

            // Configure TLS
            TrustManagerFactory tmf  =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(ClassLoaderUtils.getResourceAsStream("clientstore.jks", this.getClass()), "cspass".toCharArray());

            PKIXBuilderParameters param = new PKIXBuilderParameters(keyStore, new X509CertSelector());
            param.setRevocationEnabled(true);

            tmf.init(new CertPathTrustManagerParameters(param));

            TLSClientParameters tlsParams = new TLSClientParameters();
            tlsParams.setTrustManagers(tmf.getTrustManagers());
            tlsParams.setDisableCNCheck(true);

            Client client = ClientProxy.getClient(transportPort);
            HTTPConduit http = (HTTPConduit) client.getConduit();
            http.setTlsClientParameters(tlsParams);

            doubleIt(transportPort, 25);
        } finally {
            Security.setProperty("ocsp.responderURL", "");
            Security.setProperty("ocsp.enable", "false");
        }
    }

    // https is configured in spring here
    @org.junit.Test
    public void testTLSOCSPPassSpring() throws Exception {
        try {
            Security.setProperty("ocsp.responderURL", "http://localhost:12345");
            Security.setProperty("ocsp.enable", "true");

            SpringBusFactory bf = new SpringBusFactory();
            URL busFile = TLSOCSPTest.class.getResource("cxf-client-https.xml");

            Bus bus = bf.createBus(busFile.toString());
            SpringBusFactory.setDefaultBus(bus);
            SpringBusFactory.setThreadDefaultBus(bus);

            URL wsdl = TLSOCSPTest.class.getResource("DoubleIt.wsdl");
            Service service = Service.create(wsdl, SERVICE_QNAME);
            QName portQName = new QName(NAMESPACE, "DoubleItTLSOCSPPort");
            DoubleItPortType transportPort =
                service.getPort(portQName, DoubleItPortType.class);
            updateAddressPort(transportPort, PORT);

            doubleIt(transportPort, 25);
        } finally {
            Security.setProperty("ocsp.responderURL", "");
            Security.setProperty("ocsp.enable", "false");
        }
    }

    private static void doubleIt(DoubleItPortType port, int numToDouble) {
        int resp = port.doubleIt(numToDouble);
        assertEquals(numToDouble * 2 , resp);
    }

}