"""Zero-shot coder bundle (P4): with a tiny random BERT saved as a local "pretrained" encoder (no download in tests),
the builder writes an int8 ONNX graph with an empty head, the vocabulary and coder.json with one embedding per
label; the label embeddings come from the same quantized graph the queries use, so an exact label scores ≈ 1."""

import json

import numpy as np
import onnxruntime as ort
import pytest


@pytest.fixture()
def tiny_encoder(tmp_path):
    from tokenizers import BertWordPieceTokenizer
    from transformers import BertConfig, BertModel, BertTokenizerFast

    words = ["pruritus", "pyrexia", "headache", "nausea", "rash", "itchy", "skin", "fever", "high", "a", "my", "head"]
    wp = BertWordPieceTokenizer(lowercase=True)
    wp.train_from_iterator(words * 5, vocab_size=120, min_frequency=1)
    d = tmp_path / "enc"
    d.mkdir()
    wp.save_model(str(d))
    tok = BertTokenizerFast(vocab_file=str(d / "vocab.txt"), do_lower_case=True)
    tok.save_pretrained(d)
    BertModel(BertConfig(vocab_size=tok.vocab_size, hidden_size=32, num_hidden_layers=1, num_attention_heads=2,
                         intermediate_size=64, max_position_embeddings=64)).save_pretrained(d)
    return d


def test_zero_shot_bundle(tiny_encoder, tmp_path) -> None:
    from pharmavigil_ml.coder.zero_shot import build

    pts = ["Pruritus", "pyrexia", "headache", "nausea", "rash", "pruritus"]
    out = tmp_path / "bundle"
    result = build(tiny_encoder, pts, out, threshold=0.97, max_length=16, probes=["headache", "itchy skin"])

    cfg = json.loads((out / "coder.json").read_text())
    assert cfg["head"] == [] and cfg["tail"] == ["pruritus", "pyrexia", "headache", "nausea", "rash"]
    assert np.asarray(cfg["tail_embeddings"]).shape == (5, 32)
    assert cfg["threshold"] == 0.97 and cfg["add_special_tokens"] is True
    vocab = (out / "coder_vocab.txt").read_text().splitlines()
    assert vocab[:1] and "[CLS]" in vocab

    session = ort.InferenceSession(str(out / "coder.onnx"), providers=["CPUExecutionProvider"])
    assert [i.name for i in session.get_inputs()] == ["input_ids", "attention_mask"]
    assert any(n.op_type in ("DynamicQuantizeLinear", "MatMulInteger") for n in
               __import__("onnx").load(str(out / "coder.onnx")).graph.node)
    # The same text through the same graph gives the same embedding, so the exact label scores the maximum (≈ 1);
    # a random tiny encoder may tie other labels with it, so only the score is checked, not the order.
    first = dict(result["rows"][0]["suggestions"])
    assert result["rows"][0]["text"] == "headache" and "headache" in first
    assert first["headache"] == pytest.approx(max(first.values()), abs=1e-6)
    assert first["headache"] == pytest.approx(1.0, abs=1e-4)
    assert result["labels"] == 5
