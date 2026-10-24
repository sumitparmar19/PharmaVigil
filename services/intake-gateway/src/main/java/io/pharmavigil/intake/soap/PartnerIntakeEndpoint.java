// SOAP operations for partners: SubmitCase takes one E2B(R3) ICSR (the full MCCI_IN200100UV01 message as
// text inside the SOAP body) and returns a receipt; GetCaseStatus reports what happened to it.
package io.pharmavigil.intake.soap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import io.pharmavigil.intake.IntakeService;
import io.pharmavigil.intake.receipts.IntakeReceipt;
import io.pharmavigil.intake.soap.generated.GetCaseStatusRequest;
import io.pharmavigil.intake.soap.generated.GetCaseStatusResponse;
import io.pharmavigil.intake.soap.generated.IntakeStatus;
import io.pharmavigil.intake.soap.generated.SubmitCaseRequest;
import io.pharmavigil.intake.soap.generated.SubmitCaseResponse;

@Endpoint
public class PartnerIntakeEndpoint {

    private final IntakeService intake;
    private final String partnerUser;

    public PartnerIntakeEndpoint(IntakeService intake, @Value("${pv.soap.partner-user:}") String partnerUser) {
        this.intake = intake;
        this.partnerUser = partnerUser;
    }

    @PayloadRoot(namespace = WebServiceConfig.NAMESPACE, localPart = "SubmitCaseRequest")
    @ResponsePayload
    public SubmitCaseResponse submit(@RequestPayload SubmitCaseRequest request) {
        IntakeService.Accepted a;
        try {
            a = intake.submitE2b(request.getE2BMessage(), IntakeReceipt.Source.PARTNER,
                    request.getPartnerReference(), "partner:" + partnerUser, true).get(0);
        } catch (IntakeService.RejectedException e) {
            throw new SubmissionFault("Submission rejected: " + e.getMessage());
        }
        SubmitCaseResponse response = new SubmitCaseResponse();
        response.setReceiptId(a.receipt().getReceiptId());
        response.setStatus(a.duplicate() ? IntakeStatus.DUPLICATE : IntakeStatus.ACCEPTED);
        response.setSafetyReportId(a.receipt().getSourceReportId());
        response.getWarning().addAll(a.warnings());
        return response;
    }

    @PayloadRoot(namespace = WebServiceConfig.NAMESPACE, localPart = "GetCaseStatusRequest")
    @ResponsePayload
    public GetCaseStatusResponse status(@RequestPayload GetCaseStatusRequest request) {
        IntakeReceipt r = intake.latest(IntakeReceipt.Source.PARTNER, request.getSafetyReportId());
        GetCaseStatusResponse response = new GetCaseStatusResponse();
        response.setSafetyReportId(request.getSafetyReportId());
        response.setStatus(r == null ? IntakeStatus.UNKNOWN : IntakeStatus.valueOf(r.getStatus().name()));
        return response;
    }
}
