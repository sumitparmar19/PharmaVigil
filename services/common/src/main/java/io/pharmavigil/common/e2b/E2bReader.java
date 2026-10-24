// Reads an ICH E2B(R3) ICSR batch into IcsrCase. The parser is hardened (no DTDs, no external entities)
// because partner files come from outside. Elements are found by their position in the HL7 v3 structure
// and identifiers by their OIDs, so element order does not matter when reading. A batch holding several
// ICSR messages yields one IcsrCase per message.
package io.pharmavigil.common.e2b;

import static io.pharmavigil.common.e2b.E2bCodes.*;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

public class E2bReader {

    /** The file is not well-formed XML or not an ICSR batch. */
    public static class E2bFormatException extends RuntimeException {
        public E2bFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public List<IcsrCase> read(String xml) {
        Document doc = parse(xml);
        Element batch = doc.getDocumentElement();
        if (!"MCCI_IN200100UV01".equals(batch.getLocalName()) || !HL7_NS.equals(batch.getNamespaceURI())) {
            throw new E2bFormatException("root element must be hl7:MCCI_IN200100UV01, found "
                    + batch.getNodeName(), null);
        }
        String batchNumber = idExtension(batch, OID_BATCH_NUMBER);
        List<IcsrCase> out = new ArrayList<>();
        for (Element msg : children(batch, "PORR_IN049016UV")) {
            out.add(message(batchNumber, msg));
        }
        if (out.isEmpty()) {
            throw new E2bFormatException("batch contains no PORR_IN049016UV message", null);
        }
        return out;
    }

    private IcsrCase message(String batchNumber, Element msg) {
        Element inv = path(msg, "controlActProcess", "subject", "investigationEvent");
        if (inv == null) {
            throw new E2bFormatException("message has no controlActProcess/subject/investigationEvent", null);
        }
        Element role = path(inv, "component", "adverseEventAssessment", "subject1", "primaryRole");
        Element player = role == null ? null : child(role, "player1");

        BigDecimal age = null;
        List<IcsrCase.Reaction> reactions = new ArrayList<>();
        List<Element> drugElements = new ArrayList<>();
        if (role != null) {
            for (Element so : children(role, "subjectOf2")) {
                Element obs = child(so, "observation");
                Element org = child(so, "organizer");
                if (obs != null && OBS_AGE.equals(code(obs))) {
                    String v = attr(child(obs, "value"), "value");
                    age = v == null ? null : new BigDecimal(v);
                } else if (obs != null && OBS_REACTION.equals(code(obs))) {
                    reactions.add(reaction(obs));
                } else if (org != null) {
                    for (Element comp : children(org, "component")) {
                        Element sa = child(comp, "substanceAdministration");
                        if (sa != null) {
                            drugElements.add(sa);
                        }
                    }
                }
            }
        }
        Map<String, Integer> characterization = new HashMap<>();
        Element assessment = path(inv, "component", "adverseEventAssessment");
        if (assessment != null) {
            for (Element comp : children(assessment, "component")) {
                Element ca = child(comp, "causalityAssessment");
                if (ca != null && OBS_CHARACTERIZATION.equals(code(ca))) {
                    String drugId = attr(path(ca, "subject2", "productUseReference", "id"), "root");
                    String v = attr(child(ca, "value"), "code");
                    if (drugId != null && v != null) {
                        characterization.put(drugId, Integer.parseInt(v));
                    }
                }
            }
        }
        List<IcsrCase.Drug> drugs = new ArrayList<>();
        for (Element sa : drugElements) {
            String id = attr(child(sa, "id"), "root");
            Element kind = path(sa, "consumable", "instanceOfKind", "kindOfProduct");
            Element code = kind == null ? null : child(kind, "code");
            drugs.add(new IcsrCase.Drug(characterization.getOrDefault(id, 1), text(child(kind, "name")),
                    code == null ? null : attr(code, "code"), text(child(sa, "text")), null));
        }

        Element person = path(inv, "outboundRelationship", "relatedInvestigation", "subjectOf2", "controlActEvent",
                "author", "assignedEntity", "assignedPerson");
        String qualification = attr(path(person, "asQualifiedEntity", "code"), "code");
        String country = attr(path(person, "asLocatedEntity", "location", "code"), "code");
        String reportType = null;
        for (Element so2 : children(inv, "subjectOf2")) {
            Element ch = child(so2, "investigationCharacteristic");
            if (ch != null && "1".equals(code(ch))) {
                reportType = attr(child(ch, "value"), "code");
            }
        }
        Element gender = player == null ? null : child(player, "administrativeGenderCode");
        String sex = attr(gender, "code");

        return new IcsrCase(batchNumber, idExtension(msg, OID_MESSAGE_ID),
                idExtension(path(msg, "sender", "device"), OID_MESSAGE_SENDER),
                idExtension(path(msg, "receiver", "device"), OID_MESSAGE_RECEIVER),
                timestamp(attr(child(msg, "creationTime"), "value")),
                idExtension(inv, OID_SAFETY_REPORT_ID), idExtension(inv, OID_WORLDWIDE_CASE_ID),
                reportType == null ? 1 : Integer.parseInt(reportType),
                day(attr(path(inv, "effectiveTime", "low"), "value")),
                day(attr(child(inv, "availabilityTime"), "value")),
                qualification == null ? 5 : Integer.parseInt(qualification), country, age,
                sex == null ? null : Integer.valueOf(sex), reactions, drugs, text(child(inv, "text")));
    }

    private IcsrCase.Reaction reaction(Element obs) {
        Element value = child(obs, "value");
        boolean[] flags = new boolean[6];
        String[] codes = {OBS_DEATH, OBS_LIFE_THREATENING, OBS_HOSPITALIZATION, OBS_DISABILITY, OBS_CONGENITAL,
                OBS_OTHER_MEDICALLY_IMPORTANT};
        for (Element rel : children(obs, "outboundRelationship2")) {
            Element o = child(rel, "observation");
            String c = code(o);
            for (int i = 0; i < codes.length; i++) {
                if (codes[i].equals(c) && "true".equals(attr(child(o, "value"), "value"))) {
                    flags[i] = true;
                }
            }
        }
        return new IcsrCase.Reaction(text(child(value, "originalText")), attr(value, "displayName"),
                attr(value, "code"), attr(value, "codeSystemVersion"),
                day(attr(path(obs, "effectiveTime", "low"), "value")),
                flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]);
    }

    // -- parsing helpers ------------------------------------------------------------------------------

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new E2bFormatException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName()) && HL7_NS.equals(e.getNamespaceURI())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static Element path(Element start, String... names) {
        Element cur = start;
        for (String n : names) {
            cur = child(cur, n);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static String attr(Element e, String name) {
        if (e == null || !e.hasAttribute(name)) {
            return null;
        }
        return e.getAttribute(name);
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().strip();
    }

    private static String code(Element e) {
        return attr(child(e, "code"), "code");
    }

    private static String idExtension(Element parent, String root) {
        for (Element id : children(parent, "id")) {
            if (root.equals(id.getAttribute("root"))) {
                return id.getAttribute("extension");
            }
        }
        return null;
    }

    private static LocalDate day(String v) {
        if (v == null || v.length() < 8) {
            return null;
        }
        return LocalDate.parse(v.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static OffsetDateTime timestamp(String v) {
        if (v == null) {
            return null;
        }
        String digits = v.replaceAll("[^0-9].*$", "");
        String padded = (digits + "000000").substring(0, 14);
        return LocalDateTime.parse(padded, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atOffset(ZoneOffset.UTC);
    }
}
