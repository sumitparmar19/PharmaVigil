// Base for intake-gateway integration tests: the real application on a random port (so SOAP calls go over
// HTTP exactly as a partner's would), the shared test database, MockMvc for the REST API, and a SOAP client
// that signs every request with a WS-Security UsernameToken (password digest) and Timestamp.
package io.pharmavigil.intake;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;

import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.intake.outbox.OutboxEventRepository;
import io.pharmavigil.intake.receipts.IntakeReceiptRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class IntakeTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @LocalServerPort protected int port;
    @Autowired protected MockMvc mvc;
    @Autowired protected TokenService tokens;
    @Autowired protected IntakeReceiptRepository receipts;
    @Autowired protected OutboxEventRepository outbox;
    @Autowired protected E2bValidator validator;
    @Value("${pv.soap.partner-user}") protected String partnerUser;
    @Value("${pv.soap.partner-password}") protected String partnerPassword;

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected String bearer(String role) {
        return "Bearer " + tokens.issue(unique("user"), List.of(role));
    }

    protected String soapUrl() {
        return "http://localhost:" + port + "/ws";
    }

    protected WebServiceTemplate soapClient(String user, String password) throws Exception {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("io.pharmavigil.intake.soap.generated");
        marshaller.afterPropertiesSet();
        WebServiceTemplate client = new WebServiceTemplate(marshaller);
        client.setDefaultUri(soapUrl());
        Wss4jSecurityInterceptor security = new Wss4jSecurityInterceptor();
        security.setSecurementActions("Timestamp UsernameToken");
        security.setSecurementUsername(user);
        security.setSecurementPassword(password);
        security.setSecurementPasswordType("PasswordDigest");
        security.setSecurementUsernameTokenNonce(true);
        security.setSecurementUsernameTokenCreated(true);
        security.setValidateResponse(false);
        security.afterPropertiesSet();
        client.setInterceptors(new ClientInterceptor[] {security});
        return client;
    }

    protected WebServiceTemplate partner() throws Exception {
        return soapClient(partnerUser, partnerPassword);
    }
}
