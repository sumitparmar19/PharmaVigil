// Small helpers around ONNX Runtime: one shared environment and single-row float/long tensor calls.
package io.pharmavigil.caseservice.models;

import java.nio.file.Path;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

final class Onnx {

    static final OrtEnvironment ENV = OrtEnvironment.getEnvironment();

    private Onnx() {
    }

    static OrtSession open(Path file) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            return ENV.createSession(file.toString(), options);
        }
    }

    /** LightGBM classifier exported with zipmap off: output 1 is float[n][2] class probabilities. */
    static double positiveProbability(OrtSession session, float[] features) throws OrtException {
        try (OnnxTensor x = OnnxTensor.createTensor(ENV, new float[][] {features});
                OrtSession.Result r = session.run(Map.of("features", x))) {
            return ((float[][]) r.get(1).getValue())[0][1];
        }
    }
}
