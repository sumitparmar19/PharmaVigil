// Contract-first SOAP setup (Spring-WS): the WSDL is generated from xsd/partner-intake.xsd and served at
// /ws/partner-intake.wsdl. Every request must carry a WS-Security UsernameToken and Timestamp (ADR-009);
// then its body is checked against the contract schema before the endpoint sees it. Credentials come only
// from SOAP_PARTNER_USER / SOAP_PARTNER_PASSWORD: with either unset, every request is refused.
package io.pharmavigil.intake.soap;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.soap.security.wss4j2.callback.SimplePasswordValidationCallbackHandler;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

// No @EnableWs: Spring Boot's web-services auto-configuration enables Spring-WS, registers the
// MessageDispatcherServlet under spring.webservices.path and applies this WsConfigurer.
@Configuration
public class WebServiceConfig implements WsConfigurer {

    public static final String NAMESPACE = "https://pharmavigil.io/schemas/partner-intake/v1";

    /** Maximum age of a request's wsu:Timestamp, in seconds (limits replay of a captured message). */
    static final int TIMESTAMP_TTL_SECONDS = 300;

    private final String partnerUser;
    private final String partnerPassword;

    public WebServiceConfig(@Value("${pv.soap.partner-user:}") String partnerUser,
            @Value("${pv.soap.partner-password:}") String partnerPassword) {
        this.partnerUser = partnerUser;
        this.partnerPassword = partnerPassword;
    }

    @Bean
    XsdSchema partnerIntakeSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/partner-intake.xsd"));
    }

    @Bean(name = "partner-intake")
    DefaultWsdl11Definition partnerIntakeWsdl(XsdSchema partnerIntakeSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("PartnerIntakePort");
        wsdl.setServiceName("PartnerIntakeService");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchema(partnerIntakeSchema);
        return wsdl;
    }

    @Bean
    Wss4jSecurityInterceptor securityInterceptor() throws Exception {
        SimplePasswordValidationCallbackHandler users = new SimplePasswordValidationCallbackHandler();
        boolean configured = !partnerUser.isBlank() && partnerPassword.length() >= 12;
        users.setUsersMap(configured ? Map.of(partnerUser, partnerPassword) : Map.of());
        users.afterPropertiesSet();

        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        interceptor.setValidationActions("Timestamp UsernameToken");
        interceptor.setValidationCallbackHandler(users);
        interceptor.setTimestampStrict(true);
        interceptor.setValidationTimeToLive(TIMESTAMP_TTL_SECONDS);
        interceptor.setSecureResponse(false);
        interceptor.afterPropertiesSet();
        return interceptor;
    }

    @Bean
    PayloadValidatingInterceptor contractValidation(XsdSchema partnerIntakeSchema) throws Exception {
        PayloadValidatingInterceptor v = new PayloadValidatingInterceptor();
        v.setXsdSchema(partnerIntakeSchema);
        v.setValidateRequest(true);
        v.setValidateResponse(true);
        v.afterPropertiesSet();
        return v;
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        // Authenticate first, then check the body against the contract.
        interceptors.add(securityInterceptorUnchecked());
        interceptors.add(contractValidationUnchecked());
    }

    private EndpointInterceptor securityInterceptorUnchecked() {
        try {
            return securityInterceptor();
        } catch (Exception e) {
            throw new IllegalStateException("WS-Security setup failed", e);
        }
    }

    private EndpointInterceptor contractValidationUnchecked() {
        try {
            return contractValidation(partnerIntakeSchema());
        } catch (Exception e) {
            throw new IllegalStateException("contract validation setup failed", e);
        }
    }
}
