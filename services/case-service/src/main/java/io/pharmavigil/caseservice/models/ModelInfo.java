// Name and version stored next to every AI suggestion (engineering rule 5). The version is the bundle kind
// ("trained" or "fixture") plus the first 12 hex digits of the ONNX file's SHA-256, so a stored suggestion
// can always be traced to the exact model file in analysis/models/MANIFEST.md.
package io.pharmavigil.caseservice.models;

public record ModelInfo(String name, String version) {
}
