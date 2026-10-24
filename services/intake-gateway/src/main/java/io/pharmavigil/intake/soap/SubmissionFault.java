// A partner submission that cannot be accepted (malformed XML, not an ICSR, schema errors). Spring-WS turns
// it into a SOAP Client fault whose reason lists what to fix; nothing internal is included.
package io.pharmavigil.intake.soap;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

@SoapFault(faultCode = FaultCode.CLIENT)
public class SubmissionFault extends RuntimeException {

    public SubmissionFault(String message) {
        super(message);
    }
}
