// MedDRA coder (encoder + multi-label head → ONNX) with retrieval for rare terms (ADR-007): head PTs are
// scored by the classifier, tail PTs by tail_weight · (cosine + 1) / 2 against the narrative embedding, and
// the merged list is ranked. Suggestions only: a reviewer confirms every code. PT strings come from public
// FAERS/VAERS data; no MedDRA dictionary file is used.
package io.pharmavigil.caseservice.models;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class MedDraCoder {

    public record Suggestion(String pt, double score) {
    }

    private final OrtSession session;
    private final ModelInfo info;
    private final WordPieceTokenizer tokenizer;
    private final List<String> head;
    private final List<String> tail;
    private final float[][] tailEmbeddings;
    private final double tailWeight;
    private final int maxLength;
    private final double threshold;

    MedDraCoder(OrtSession session, ModelInfo info, WordPieceTokenizer tokenizer, List<String> head, List<String> tail,
            float[][] tailEmbeddings, double tailWeight, int maxLength, double threshold) {
        this.session = session;
        this.info = info;
        this.tokenizer = tokenizer;
        this.head = head;
        this.tail = tail;
        this.tailEmbeddings = tailEmbeddings;
        this.tailWeight = tailWeight;
        this.maxLength = maxLength;
        this.threshold = threshold;
    }

    public ModelInfo info() {
        return info;
    }

    /** Minimum score for a suggestion to count as coded without manual coding. */
    public double threshold() {
        return threshold;
    }

    WordPieceTokenizer tokenizer() {
        return tokenizer;
    }

    record Raw(float[] headProbabilities, float[] embedding) {
    }

    Raw run(String text) throws OrtException {
        long[] ids = tokenizer.encode(text, maxLength);
        long[] mask = new long[ids.length];
        java.util.Arrays.fill(mask, 1L);
        try (OnnxTensor in = OnnxTensor.createTensor(Onnx.ENV, new long[][] {ids});
                OnnxTensor m = OnnxTensor.createTensor(Onnx.ENV, new long[][] {mask});
                OrtSession.Result r = session.run(Map.of("input_ids", in, "attention_mask", m))) {
            return new Raw(((float[][]) r.get(0).getValue())[0], ((float[][]) r.get(1).getValue())[0]);
        }
    }

    /** Top-k PT suggestions for a reported term or a narrative, best first. */
    public List<Suggestion> suggest(String text, int k) throws OrtException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Raw raw = run(text);
        List<Suggestion> all = new ArrayList<>(head.size() + tail.size());
        for (int i = 0; i < head.size(); i++) {
            all.add(new Suggestion(head.get(i), raw.headProbabilities()[i]));
        }
        for (int j = 0; j < tail.size(); j++) {
            double dot = 0;
            for (int d = 0; d < raw.embedding().length; d++) {
                dot += raw.embedding()[d] * tailEmbeddings[j][d];
            }
            all.add(new Suggestion(tail.get(j), tailWeight * (dot + 1) / 2));
        }
        all.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return List.copyOf(all.subList(0, Math.min(k, all.size())));
    }
}
