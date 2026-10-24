// Object identifiers (OIDs) and codes of the ICH E2B(R3) implementation guide used by the writer and reader.
// Each constant names the E2B data element it serves. docs/E2B_R3.md records which ones have been checked
// against the official schema set and reference instances.
package io.pharmavigil.common.e2b;

public final class E2bCodes {

    public static final String HL7_NS = "urn:hl7-org:v3";
    public static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    // N.1 batch and N.2 message header
    public static final String OID_BATCH_NUMBER = "2.16.840.1.113883.3.989.2.1.3.22";       // N.1.2
    public static final String OID_BATCH_SENDER = "2.16.840.1.113883.3.989.2.1.3.13";       // N.1.3
    public static final String OID_BATCH_RECEIVER = "2.16.840.1.113883.3.989.2.1.3.14";     // N.1.4
    public static final String OID_MESSAGE_ID = "2.16.840.1.113883.3.989.2.1.3.1";          // N.2.r.1
    public static final String OID_MESSAGE_SENDER = "2.16.840.1.113883.3.989.2.1.3.11";     // N.2.r.2
    public static final String OID_MESSAGE_RECEIVER = "2.16.840.1.113883.3.989.2.1.3.12";   // N.2.r.3
    public static final String OID_BATCH_TYPE = "2.16.840.1.113883.3.989.2.1.1.1";          // N.1.1 (1 = ichicsr)
    public static final String OID_INTERACTION = "2.16.840.1.113883.1.6";
    public static final String OID_TRIGGER_EVENT = "2.16.840.1.113883.1.18";

    // C.1 identification
    public static final String OID_SAFETY_REPORT_ID = "2.16.840.1.113883.3.989.2.1.3.1";    // C.1.1
    public static final String OID_WORLDWIDE_CASE_ID = "2.16.840.1.113883.3.989.2.1.3.2";   // C.1.8.1
    public static final String OID_ACT_CODE = "2.16.840.1.113883.5.4";                      // PAT_ADV_EVNT
    public static final String OID_CHARACTERISTIC = "2.16.840.1.113883.3.989.2.1.1.23";     // 1 = ichReportType
    public static final String OID_REPORT_TYPE = "2.16.840.1.113883.3.989.2.1.1.2";         // C.1.3 values
    public static final String OID_INVESTIGATION_RELATION = "2.16.840.1.113883.3.989.2.1.1.22"; // 2 = sourceReport

    // C.2 primary source
    public static final String OID_QUALIFICATION = "2.16.840.1.113883.3.989.2.1.1.6";       // C.2.r.4
    public static final String OID_COUNTRY = "1.0.3166.1.2.2";                              // ISO 3166-1 alpha-2

    // D patient, E reactions, G drugs
    public static final String OID_SEX = "1.0.5218";                                        // D.5 ISO 5218
    public static final String OID_OBSERVATION = "2.16.840.1.113883.3.989.2.1.1.19";        // observation codes
    public static final String OID_ORGANIZER = "2.16.840.1.113883.3.989.2.1.1.20";          // 4 = drugInformation
    public static final String OID_CHARACTERIZATION = "2.16.840.1.113883.3.989.2.1.1.13";   // G.k.1 values
    public static final String OID_MEDDRA = "2.16.840.1.113883.6.163";

    public static final String OBS_AGE = "3";
    public static final String OBS_REACTION = "29";
    public static final String OBS_CHARACTERIZATION = "20";
    public static final String OBS_DEATH = "34";
    public static final String OBS_LIFE_THREATENING = "21";
    public static final String OBS_HOSPITALIZATION = "33";
    public static final String OBS_DISABILITY = "35";
    public static final String OBS_CONGENITAL = "12";
    public static final String OBS_OTHER_MEDICALLY_IMPORTANT = "26";
    public static final String ORGANIZER_DRUG_INFORMATION = "4";

    private E2bCodes() {
    }
}
