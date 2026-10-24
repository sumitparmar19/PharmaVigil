// Writes an IcsrCase as an ICH E2B(R3) ICSR batch (HL7 v3 MCCI_IN200100UV01 wrapping one
// PORR_IN049016UV message). The element order follows the HL7 v3 schemas; output is validated by
// E2bValidator before it may be queued for submission.
package io.pharmavigil.common.e2b;

import static io.pharmavigil.common.e2b.E2bCodes.*;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class E2bWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private Document doc;

    public String write(IcsrCase c) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        String created = TS.format(c.createdAt().withOffsetSameInstant(ZoneOffset.UTC));

        Element batch = doc.createElementNS(HL7_NS, "MCCI_IN200100UV01");
        doc.appendChild(batch);
        batch.setAttribute("ITSVersion", "XML_1.0");
        batch.setAttributeNS(XSI_NS, "xsi:schemaLocation", HL7_NS + " MCCI_IN200100UV01.xsd");
        id(batch, OID_BATCH_NUMBER, c.batchNumber());
        valued(batch, "creationTime", created);
        coded(batch, "responseModeCode", "D", null);
        id(batch, "interactionId", OID_INTERACTION, "MCCI_IN200100UV01");
        Element name = el(batch, "name");
        name.setAttribute("code", "1");
        name.setAttribute("codeSystem", OID_BATCH_TYPE);

        Element msg = el(batch, "PORR_IN049016UV");
        id(msg, OID_MESSAGE_ID, c.messageId());
        valued(msg, "creationTime", created);
        id(msg, "interactionId", OID_INTERACTION, "PORR_IN049016UV");
        coded(msg, "processingCode", "P", null);
        coded(msg, "processingModeCode", "T", null);
        coded(msg, "acceptAckCode", "AL", null);
        device(msg, "receiver", "RCV", OID_MESSAGE_RECEIVER, c.receiverId());
        device(msg, "sender", "SND", OID_MESSAGE_SENDER, c.senderId());

        Element cap = el(msg, "controlActProcess");
        cap.setAttribute("classCode", "CACT");
        cap.setAttribute("moodCode", "EVN");
        coded(cap, "code", "PORR_TE049016UV", OID_TRIGGER_EVENT);
        valued(cap, "effectiveTime", created);
        Element subject = el(cap, "subject");
        subject.setAttribute("typeCode", "SUBJ");
        investigation(subject, c);

        batchWrapperTail(batch, c);
        return serialize();
    }

    private void investigation(Element subject, IcsrCase c) {
        Element inv = el(subject, "investigationEvent");
        inv.setAttribute("classCode", "INVSTG");
        inv.setAttribute("moodCode", "EVN");
        id(inv, OID_SAFETY_REPORT_ID, c.safetyReportId());
        id(inv, OID_WORLDWIDE_CASE_ID, c.worldwideCaseId());
        coded(inv, "code", "PAT_ADV_EVNT", OID_ACT_CODE);
        if (c.narrative() != null && !c.narrative().isBlank()) {
            el(inv, "text").setTextContent(c.narrative());
        }
        coded(inv, "statusCode", "active", null);
        Element eff = el(inv, "effectiveTime");
        valued(eff, "low", DAY.format(c.firstReceived()));
        valued(inv, "availabilityTime", DAY.format(c.mostRecentReceived()));

        Element comp = el(inv, "component");
        comp.setAttribute("typeCode", "COMP");
        Element assessment = el(comp, "adverseEventAssessment");
        assessment.setAttribute("classCode", "INVSTG");
        assessment.setAttribute("moodCode", "EVN");
        List<String> drugIds = patient(assessment, c);
        for (int i = 0; i < c.drugs().size(); i++) {
            causality(assessment, c.drugs().get(i), drugIds.get(i));
        }

        primarySource(inv, c);
        Element so2 = el(inv, "subjectOf2");
        so2.setAttribute("typeCode", "SUBJ");
        Element ch = el(so2, "investigationCharacteristic");
        ch.setAttribute("classCode", "OBS");
        ch.setAttribute("moodCode", "EVN");
        coded(ch, "code", "1", OID_CHARACTERISTIC);
        Element v = el(ch, "value");
        v.setAttributeNS(XSI_NS, "xsi:type", "CE");
        v.setAttribute("code", Integer.toString(c.reportType()));
        v.setAttribute("codeSystem", OID_REPORT_TYPE);
    }

    private List<String> patient(Element assessment, IcsrCase c) {
        Element s1 = el(assessment, "subject1");
        s1.setAttribute("typeCode", "SBJ");
        Element role = el(s1, "primaryRole");
        role.setAttribute("classCode", "INVSBJ");
        Element player = el(role, "player1");
        player.setAttribute("classCode", "PSN");
        player.setAttribute("determinerCode", "INSTANCE");
        el(player, "name").setAttribute("nullFlavor", "MSK"); // D.1 patient initials masked
        Element gender = el(player, "administrativeGenderCode");
        if (c.patientSex() == null) {
            gender.setAttribute("nullFlavor", "UNK");
        } else {
            gender.setAttribute("code", c.patientSex().toString());
            gender.setAttribute("codeSystem", OID_SEX);
        }
        if (c.patientAgeYears() != null) {
            Element obs = observation(role, OBS_AGE);
            Element value = el(obs, "value");
            value.setAttributeNS(XSI_NS, "xsi:type", "PQ");
            value.setAttribute("value", c.patientAgeYears().stripTrailingZeros().toPlainString());
            value.setAttribute("unit", "a");
        }
        for (IcsrCase.Reaction r : c.reactions()) {
            reaction(role, r);
        }
        List<String> drugIds = new ArrayList<>();
        if (!c.drugs().isEmpty()) {
            Element so = el(role, "subjectOf2");
            so.setAttribute("typeCode", "SBJ");
            Element org = el(so, "organizer");
            org.setAttribute("classCode", "CATEGORY");
            org.setAttribute("moodCode", "EVN");
            coded(org, "code", ORGANIZER_DRUG_INFORMATION, OID_ORGANIZER);
            for (IcsrCase.Drug d : c.drugs()) {
                String id = UUID.randomUUID().toString();
                drugIds.add(id);
                drug(org, d, id);
            }
        }
        return drugIds;
    }

    private void reaction(Element role, IcsrCase.Reaction r) {
        Element obs = observation(role, OBS_REACTION);
        if (r.onset() != null) {
            Element eff = el(obs, "effectiveTime");
            eff.setAttributeNS(XSI_NS, "xsi:type", "IVL_TS");
            valued(eff, "low", DAY.format(r.onset()));
        }
        Element value = el(obs, "value");
        value.setAttributeNS(XSI_NS, "xsi:type", "CE");
        if (r.meddraCode() != null) {
            value.setAttribute("code", r.meddraCode());
            value.setAttribute("codeSystem", OID_MEDDRA);
            if (r.meddraVersion() != null) {
                value.setAttribute("codeSystemVersion", r.meddraVersion());
            }
        }
        if (r.pt() != null) {
            value.setAttribute("displayName", r.pt());
        }
        Element ot = el(value, "originalText");
        ot.setTextContent(r.reportedTerm());
        seriousness(obs, OBS_DEATH, r.death());
        seriousness(obs, OBS_LIFE_THREATENING, r.lifeThreatening());
        seriousness(obs, OBS_HOSPITALIZATION, r.hospitalization());
        seriousness(obs, OBS_DISABILITY, r.disability());
        seriousness(obs, OBS_CONGENITAL, r.congenitalAnomaly());
        seriousness(obs, OBS_OTHER_MEDICALLY_IMPORTANT, r.otherMedicallyImportant());
    }

    private void seriousness(Element obs, String code, boolean flag) {
        if (!flag) {
            return; // E2B allows absent (null) criteria; only true flags are sent
        }
        Element rel = el(obs, "outboundRelationship2");
        rel.setAttribute("typeCode", "PERT");
        Element o = el(rel, "observation");
        o.setAttribute("classCode", "OBS");
        o.setAttribute("moodCode", "EVN");
        coded(o, "code", code, OID_OBSERVATION);
        Element v = el(o, "value");
        v.setAttributeNS(XSI_NS, "xsi:type", "BL");
        v.setAttribute("value", "true");
    }

    private void drug(Element org, IcsrCase.Drug d, String id) {
        Element comp = el(org, "component");
        comp.setAttribute("typeCode", "COMP");
        Element sa = el(comp, "substanceAdministration");
        sa.setAttribute("classCode", "SBADM");
        sa.setAttribute("moodCode", "EVN");
        el(sa, "id").setAttribute("root", id);
        if (d.doseText() != null) {
            el(sa, "text").setTextContent(d.doseText());
        }
        Element consumable = el(sa, "consumable");
        consumable.setAttribute("typeCode", "CSM");
        Element iok = el(consumable, "instanceOfKind");
        iok.setAttribute("classCode", "INST");
        Element kind = el(iok, "kindOfProduct");
        kind.setAttribute("classCode", "MMAT");
        kind.setAttribute("determinerCode", "KIND");
        if (d.rxcui() != null) {
            Element code = el(kind, "code");
            code.setAttribute("code", d.rxcui());
            code.setAttribute("codeSystem", "2.16.840.1.113883.6.88"); // RxNorm
        }
        el(kind, "name").setTextContent(d.name());
    }

    private void causality(Element assessment, IcsrCase.Drug d, String drugId) {
        Element comp = el(assessment, "component");
        comp.setAttribute("typeCode", "COMP");
        Element ca = el(comp, "causalityAssessment");
        ca.setAttribute("classCode", "OBS");
        ca.setAttribute("moodCode", "EVN");
        coded(ca, "code", OBS_CHARACTERIZATION, OID_OBSERVATION);
        Element v = el(ca, "value");
        v.setAttributeNS(XSI_NS, "xsi:type", "CE");
        v.setAttribute("code", Integer.toString(d.characterization()));
        v.setAttribute("codeSystem", OID_CHARACTERIZATION);
        Element s2 = el(ca, "subject2");
        s2.setAttribute("typeCode", "SUBJ");
        Element ref = el(s2, "productUseReference");
        ref.setAttribute("classCode", "SBADM");
        ref.setAttribute("moodCode", "EVN");
        el(ref, "id").setAttribute("root", drugId);
    }

    private void primarySource(Element inv, IcsrCase c) {
        Element rel = el(inv, "outboundRelationship");
        rel.setAttribute("typeCode", "SPRT");
        Element ri = el(rel, "relatedInvestigation");
        ri.setAttribute("classCode", "INVSTG");
        ri.setAttribute("moodCode", "EVN");
        coded(ri, "code", "2", OID_INVESTIGATION_RELATION);
        Element so2 = el(ri, "subjectOf2");
        so2.setAttribute("typeCode", "SUBJ");
        Element cae = el(so2, "controlActEvent");
        cae.setAttribute("classCode", "CACT");
        cae.setAttribute("moodCode", "EVN");
        Element author = el(cae, "author");
        author.setAttribute("typeCode", "AUT");
        Element ae = el(author, "assignedEntity");
        ae.setAttribute("classCode", "ASSIGNED");
        Element person = el(ae, "assignedPerson");
        person.setAttribute("classCode", "PSN");
        person.setAttribute("determinerCode", "INSTANCE");
        Element q = el(person, "asQualifiedEntity");
        q.setAttribute("classCode", "QUAL");
        coded(q, "code", Integer.toString(c.reporterQualification()), OID_QUALIFICATION);
        if (c.reporterCountry() != null) {
            Element le = el(person, "asLocatedEntity");
            le.setAttribute("classCode", "LOCE");
            Element loc = el(le, "location");
            loc.setAttribute("classCode", "COUNTRY");
            loc.setAttribute("determinerCode", "INSTANCE");
            coded(loc, "code", c.reporterCountry(), OID_COUNTRY);
        }
    }

    private void batchWrapperTail(Element batch, IcsrCase c) {
        device(batch, "receiver", "RCV", OID_BATCH_RECEIVER, c.receiverId());
        device(batch, "sender", "SND", OID_BATCH_SENDER, c.senderId());
    }

    // -- small DOM helpers --------------------------------------------------------------------------

    private Element observation(Element role, String code) {
        Element so = el(role, "subjectOf2");
        so.setAttribute("typeCode", "SBJ");
        Element obs = el(so, "observation");
        obs.setAttribute("classCode", "OBS");
        obs.setAttribute("moodCode", "EVN");
        coded(obs, "code", code, OID_OBSERVATION);
        return obs;
    }

    private void device(Element parent, String name, String typeCode, String oid, String value) {
        Element e = el(parent, name);
        e.setAttribute("typeCode", typeCode);
        Element dev = el(e, "device");
        dev.setAttribute("classCode", "DEV");
        dev.setAttribute("determinerCode", "INSTANCE");
        id(dev, oid, value);
    }

    private Element el(Element parent, String name) {
        Element e = doc.createElementNS(HL7_NS, name);
        parent.appendChild(e);
        return e;
    }

    private void id(Element parent, String root, String extension) {
        id(parent, "id", root, extension);
    }

    private void id(Element parent, String name, String root, String extension) {
        Element e = el(parent, name);
        e.setAttribute("root", root);
        e.setAttribute("extension", extension);
    }

    private void valued(Element parent, String name, String value) {
        el(parent, name).setAttribute("value", value);
    }

    private void coded(Element parent, String name, String code, String codeSystem) {
        Element e = el(parent, name);
        e.setAttribute("code", code);
        if (codeSystem != null) {
            e.setAttribute("codeSystem", codeSystem);
        }
    }

    private String serialize() {
        try {
            var t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Convenience for callers building dates. */
    public static OffsetDateTime utc(LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    static BigDecimal age(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
