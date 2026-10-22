// Contract tests for the SOAP partner intake (C9): the WSDL comes from the contract schema, a signed
// SubmitCase with a valid ICSR returns a receipt and writes exactly one event, resends are recognised,
// follow-ups are accepted, and every kind of bad request gets a SOAP Client fault with an explained reason.
package io.pharmavigil.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.ws.soap.client.SoapFaultClientException;

import io.pharmavigil.common.e2b.E2bSamples;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.intake.receipts.IntakeReceipt;
import io.pharmavigil.intake.soap.generated.GetCaseStatusRequest;
import io.pharmavigil.intake.soap.generated.GetCaseStatusResponse;
import io.pharmavigil.intake.soap.generated.IntakeStatus;
import io.pharmavigil.intake.soap.generated.SubmitCaseRequest;
import io.pharmavigil.intake.soap.generated.SubmitCaseResponse;

class SoapIntakeContractTest extends IntakeTest {

    private static SubmitCaseRequest request(String partnerRef, String e2b) {
        SubmitCaseRequest r = new SubmitCaseRequest();
        r.setPartnerReference(partnerRef);
        r.setE2BMessage(e2b);
        return r;
    }

    private static String icsr(String reportId, String narrative) {
        return new E2bWriter().write(E2bSamples.sample(reportId, narrative));
    }

    private SubmitCaseResponse submit(SubmitCaseRequest r) throws Exception {
        return (SubmitCaseResponse) partner().marshalSendAndReceive(r);
    }

    private String faultOf(SubmitCaseRequest r) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(SoapFaultClientException.class,
                () -> partner().marshalSendAndReceive(r)).getFaultStringOrReason();
    }

    @Test
    void wsdlIsGeneratedFromTheContract() throws Exception {
        HttpResponse<String> wsdl = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(soapUrl() + "/partner-intake.wsdl")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("PartnerIntakePort").contains("SubmitCase").contains("GetCaseStatus")
                .contains("https://pharmavigil.io/schemas/partner-intake/v1");
    }

    @Test
    void validIcsrIsAcceptedOnceAndResendIsRecognised() throws Exception {
        String id = unique("US-PARTNER");
        String xml = icsr(id, "Rash after the first dose.");
        SubmitCaseResponse first = submit(request("ref-1", xml));
        assertThat(first.getStatus()).isEqualTo(IntakeStatus.ACCEPTED);
        assertThat(first.getSafetyReportId()).isEqualTo(id);
        if (validator.schemaAvailable()) {
            assertThat(first.getWarning()).isEmpty();
        } else {
            assertThat(first.getWarning()).anyMatch(w -> w.contains("not schema-validated"));
        }
        IntakeReceipt receipt = receipts.findByReceiptId(first.getReceiptId()).orElseThrow();
        assertThat(receipt.getSource()).isEqualTo(IntakeReceipt.Source.PARTNER);
        assertThat(receipt.getSubmittedBy()).isEqualTo("partner:" + partnerUser);
        assertThat(receipt.isSchemaChecked()).isEqualTo(validator.schemaAvailable());
        assertThat(outbox.findByAggregateId(first.getReceiptId())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getTopic()).isEqualTo("report.received");
                    assertThat(e.getPayload()).contains("\"source\":\"PARTNER\"").contains(id)
                            .contains("Rash after the first dose.");
                });

        SubmitCaseResponse again = submit(request("ref-1", xml));
        assertThat(again.getStatus()).isEqualTo(IntakeStatus.DUPLICATE);
        assertThat(again.getReceiptId()).isEqualTo(first.getReceiptId());
        assertThat(outbox.findByAggregateId(first.getReceiptId())).hasSize(1);
    }

    @Test
    void changedMessageForKnownReportIsAFollowUp() throws Exception {
        String id = unique("US-PARTNER");
        SubmitCaseResponse first = submit(request("ref-2", icsr(id, "Initial report.")));
        SubmitCaseResponse followUp = submit(request("ref-2b", icsr(id, "Follow-up: patient recovered.")));
        assertThat(followUp.getStatus()).isEqualTo(IntakeStatus.ACCEPTED);
        assertThat(followUp.getReceiptId()).isNotEqualTo(first.getReceiptId());
        assertThat(followUp.getWarning()).anyMatch(w -> w.contains("follow-up"));
        assertThat(receipts.findByReceiptId(followUp.getReceiptId()).orElseThrow().isFollowUp()).isTrue();
        assertThat(outbox.findByAggregateId(followUp.getReceiptId())).hasSize(1);
    }

    @Test
    void badPayloadsGetClientFaultsWithReasons() {
        assertThat(faultOf(request("r", "<MCCI_IN200100UV01 xmlns=\"urn:hl7-org:v3\">")))
                .startsWith("Submission rejected").contains("not well-formed");
        assertThat(faultOf(request("r", "<ichicsr/>"))).contains("MCCI_IN200100UV01");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<MCCI_IN200100UV01 xmlns=\"urn:hl7-org:v3\">&e;</MCCI_IN200100UV01>";
        assertThat(faultOf(request("r", xxe))).doesNotContain("root:");
        assertThat(faultOf(request("r", E2bSamples.twoMessageBatch(unique("A"), unique("B")))))
                .contains("one ICSR per SubmitCase");
        // Empty partnerReference breaks the contract schema itself.
        assertThat(faultOf(request("", icsr(unique("US-P"), "x")))).isNotBlank();
    }

    @Test
    void schemaInvalidIcsrIsRejectedWhenSchemasAreInstalled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(validator.schemaAvailable(), "ICH schema set not fetched");
        String broken = icsr(unique("US-P"), "x")
                .replaceFirst("<investigationEvent([^>]*)>", "<investigationEvent$1><notAnE2bElement/>");
        assertThat(faultOf(request("r", broken))).contains("E2B(R3) schema").contains("notAnE2bElement");
    }

    @Test
    void wrongOrMissingCredentialsAreRefused() throws Exception {
        assertThatThrownBy(() -> soapClient(partnerUser, "wrong-password-123").marshalSendAndReceive(
                request("r", icsr(unique("US-P"), "x")))).isInstanceOf(SoapFaultClientException.class);
        assertThatThrownBy(() -> soapClient("someone-else", partnerPassword).marshalSendAndReceive(
                request("r", icsr(unique("US-P"), "x")))).isInstanceOf(SoapFaultClientException.class);

        String unsigned = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:p="https://pharmavigil.io/schemas/partner-intake/v1">
                  <soapenv:Body><p:GetCaseStatusRequest><p:safetyReportId>X</p:safetyReportId></p:GetCaseStatusRequest>
                  </soapenv:Body>
                </soapenv:Envelope>""";
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(soapUrl()))
                .header("Content-Type", "text/xml; charset=utf-8").header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(unsigned)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("Fault").doesNotContain("GetCaseStatusResponse");
    }

    @Test
    void caseStatusIsReportedForKnownAndUnknownReports() throws Exception {
        String id = unique("US-PARTNER");
        submit(request("ref-3", icsr(id, "Headache.")));
        GetCaseStatusRequest known = new GetCaseStatusRequest();
        known.setSafetyReportId(id);
        assertThat(((GetCaseStatusResponse) partner().marshalSendAndReceive(known)).getStatus())
                .isEqualTo(IntakeStatus.RECEIVED);
        GetCaseStatusRequest unknown = new GetCaseStatusRequest();
        unknown.setSafetyReportId(unique("NOPE"));
        assertThat(((GetCaseStatusResponse) partner().marshalSendAndReceive(unknown)).getStatus())
                .isEqualTo(IntakeStatus.UNKNOWN);
    }
}
