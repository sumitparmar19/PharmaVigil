"""MedDRA coder on invented narratives with a tiny, randomly initialized BERT (the real checkpoint is
downloaded on Kaggle): label-space choice, fine-tuning, ranking with retrieval, metrics, ONNX parity."""

import numpy as np
import pytest
import torch
from tokenizers import BertWordPieceTokenizer
from transformers import BertConfig, BertModel, PreTrainedTokenizerFast

from pharmavigil_ml.coder.model import CoderModel, choose_labels
from pharmavigil_ml.coder.train import CoderConfig, encode, fine_tune, rank, top_k_recall, tune_and_score
from pharmavigil_ml.export.onnx_export import coder_parity, export_coder

PHRASES = {"a red rash": "rash", "itchy skin": "pruritus", "a high fever": "pyrexia", "a bad headache": "headache",
           "vomiting": "vomiting", "dizziness": "dizziness", "hair loss": "alopecia"}


def _data(n: int, seed: int):
    rng = np.random.default_rng(seed)
    keys = list(PHRASES)
    weights = np.array([8, 6, 6, 5, 4, 3, 0.4])
    texts, labels = [], []
    for _ in range(n):
        k = rng.choice(keys, size=rng.integers(1, 3), replace=False, p=weights / weights.sum())
        texts.append("The patient developed " + " and ".join(k) + " two days after the first dose.")
        labels.append(sorted({PHRASES[x] for x in k}))
    return texts, labels


@pytest.fixture(scope="module")
def setup(tmp_path_factory):
    texts, labels = _data(500, 1)
    wp = BertWordPieceTokenizer(lowercase=True)
    wp.train_from_iterator(texts + list(PHRASES.values()), vocab_size=200, min_frequency=1)
    tok = PreTrainedTokenizerFast(tokenizer_object=wp._tokenizer, unk_token="[UNK]", pad_token="[PAD]",
                                  cls_token="[CLS]", sep_token="[SEP]", mask_token="[MASK]")
    enc = BertModel(BertConfig(vocab_size=tok.vocab_size, hidden_size=32, num_hidden_layers=1, num_attention_heads=2,
                               intermediate_size=64, max_position_embeddings=64))
    space = choose_labels(labels, coverage=0.97)
    torch.manual_seed(0)
    model = CoderModel(enc, 32, len(space.head))
    hist = fine_tune(model, tok, texts, labels, space, CoderConfig(max_length=32, batch_size=32, epochs=6,
                                                                    lr_encoder=1e-3), log=lambda _: None)
    return tok, model, space, hist


def test_label_space_head_and_tail(setup) -> None:
    _, _, space, _ = setup
    assert "rash" in space.head and "alopecia" in space.tail


def test_training_and_metrics(setup) -> None:
    tok, model, space, hist = setup
    assert hist[-1]["train_loss"] < hist[0]["train_loss"]
    m = tune_and_score(model, tok, space, _data(100, 2), _data(150, 3), max_length=32)
    assert m["micro_f1"] > 0.5 and m["top5_recall"] > 0.6
    assert set(m) >= {"macro_f1", "top5_recall_head_labels", "top5_recall_tail_labels", "tail_weight"}


def test_rank_merges_tail() -> None:
    from pharmavigil_ml.coder.model import LabelSpace

    space = LabelSpace(head=["a", "b"], tail=["z"])
    out = rank(np.array([[0.1, 0.2]]), np.array([[1.0, 0.0]]), np.array([[1.0, 0.0]]), space, 1.0, k=2)
    assert out == [["z", "b"]]
    assert top_k_recall([["z", "b"]], [["z", "a"]]) == 0.5


def test_onnx_parity(setup, tmp_path) -> None:
    tok, model, _, _ = setup
    batch = encode(tok, _data(8, 9)[0], 32)
    path = export_coder(model, tmp_path / "coder.onnx", batch["input_ids"], batch["attention_mask"])
    parity, _, _ = coder_parity(model, path, batch["input_ids"], batch["attention_mask"])
    assert parity["max_abs_diff"] < 1e-4


def test_onnx_handles_other_shapes(setup, tmp_path) -> None:
    """The graph was traced at one batch/sequence size; it must stay exact at others."""
    tok, model, _, _ = setup
    trace = encode(tok, _data(4, 9)[0], 32)
    path = export_coder(model, tmp_path / "coder.onnx", trace["input_ids"], trace["attention_mask"])
    texts = ["Rash.", "The patient developed itchy skin, a bad headache and dizziness after the second dose today."]
    other = encode(tok, texts, 32)  # different batch size, longer padding inside the batch
    parity, _, _ = coder_parity(model, path, other["input_ids"], other["attention_mask"])
    assert parity["max_abs_diff"] < 1e-4
