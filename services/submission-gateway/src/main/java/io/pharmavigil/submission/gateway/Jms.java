// JMS destinations and message properties shared by the gateway and the mock regulator.
//   regulatory.outbound  gateway → regulator: TextMessage with the E2B(R3) XML
//   regulatory.ack       regulator → gateway: TextMessage with a RegulatorAck JSON
package io.pharmavigil.submission.gateway;

public final class Jms {

    public static final String OUTBOUND = "regulatory.outbound";
    public static final String ACK = "regulatory.ack";
    public static final String MESSAGE_ID = "pvMessageId";
    public static final String SAFETY_REPORT_ID = "pvSafetyReportId";

    private Jms() {
    }
}
