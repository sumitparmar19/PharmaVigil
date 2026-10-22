"""SafetyFormer training loop: AdamW with warm-up and cosine decay, soft cross-entropy on the hidden
reactions (plus the optional masked-drug loss), fixed seeds, held-out masked-reaction metrics, an
MLflow run, and a checkpoint whose SHA-256 goes into analysis/models/MANIFEST.md."""

from __future__ import annotations

import hashlib
import math
import os
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from .corpus import Corpus, Vocab
from .model import ModelConfig, SafetyFormer
from .views import ViewConfig, ViewMaker


@dataclass
class TrainConfig:
    steps: int = 2000
    batch_size: int = 256
    lr: float = 3e-4
    warmup_steps: int = 200
    weight_decay: float = 0.01
    seed: int = 13
    eval_every: int = 500
    eval_reports: int = 5000
    drug_loss_weight: float = 0.5
    d_model: int = 256
    n_layers: int = 4
    n_heads: int = 8
    d_ff: int = 1024
    dropout: float = 0.1
    min_count: int = 5
    masked_drug_objective: bool = False
    device: str = "auto"  # auto → cuda when available, else cpu
    views: ViewConfig = field(default_factory=ViewConfig)

    def as_dict(self) -> dict[str, object]:
        d = asdict(self)
        d["views"] = asdict(self.views)
        return d


def set_seed(seed: int) -> None:
    np.random.seed(seed)
    torch.manual_seed(seed)


def lr_at(step: int, cfg: TrainConfig) -> float:
    if step < cfg.warmup_steps:
        return cfg.lr * (step + 1) / cfg.warmup_steps
    progress = (step - cfg.warmup_steps) / max(1, cfg.steps - cfg.warmup_steps)
    return cfg.lr * 0.5 * (1 + math.cos(math.pi * min(1.0, progress)))


def resolve_device(name: str) -> torch.device:
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(name)


def _to(b, device: torch.device):
    for k in ("tokens", "types", "roles", "query_pos", "target", "drug_query_pos", "drug_target"):
        v = getattr(b, k)
        if v is not None:
            setattr(b, k, v.to(device, non_blocking=True))
    return b


def soft_cross_entropy(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    return -(target * F.log_softmax(logits, dim=-1)).sum(dim=-1).mean()


@torch.no_grad()
def masked_reaction_metrics(model: SafetyFormer, corpus: Corpus, n: int, seed: int = 99, k: int = 10) -> dict:
    """Hide one PT per report (all other tokens visible) and score the model's prediction of it."""
    if len(corpus) == 0:
        return {"reports": 0}
    model.eval()
    device = next(model.parameters()).device
    eval_views = ViewConfig(p_single_drug=0, p_no_drug=0, p_hide_all_pts=0, p_unknown_context=0)
    maker = ViewMaker(corpus, eval_views, seed=seed)
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(corpus), size=min(n, len(corpus)), replace=False)
    hits, nll, count = 0.0, 0.0, 0
    for start in range(0, len(idx), 512):
        b = _to(maker.batch(idx[start:start + 512]), device)
        logits = model(b.tokens, b.types, b.roles, b.query_pos)
        logp = F.log_softmax(logits, dim=-1)
        topk = logits.topk(min(k, logits.size(-1)), dim=-1).indices
        hidden = b.target > 0
        hits += hidden.gather(1, topk).any(dim=1).float().sum().item()
        nll += -(b.target * logp).sum(dim=-1).sum().item()
        count += len(b.target)
    model.train()
    return {"reports": count, f"recall_at_{k}": hits / count, "nll": nll / count}


def train(corpus: Corpus, cfg: TrainConfig, val: Corpus | None = None, out_dir: Path | None = None,
          run_name: str = "safetyformer", tracking_uri: str | None = None, log=print) -> dict[str, object]:
    set_seed(cfg.seed)
    torch.set_num_threads(int(os.environ.get("PV_TORCH_THREADS", os.cpu_count() or 1)))
    vocab: Vocab = corpus.vocab
    mcfg = ModelConfig(vocab_size=len(vocab.tokens), n_pt=vocab.n_pt, n_drug=len(vocab.drug_tokens),
                       d_model=cfg.d_model, n_layers=cfg.n_layers, n_heads=cfg.n_heads, d_ff=cfg.d_ff,
                       dropout=cfg.dropout, masked_drug_objective=cfg.masked_drug_objective)
    device = resolve_device(cfg.device)
    model = SafetyFormer(mcfg).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr, weight_decay=cfg.weight_decay)
    views = cfg.views if not cfg.masked_drug_objective or cfg.views.masked_drug_rate else \
        ViewConfig(**{**asdict(cfg.views), "masked_drug_rate": 0.15})
    maker = ViewMaker(corpus, views, seed=cfg.seed)
    rng = np.random.default_rng(cfg.seed)
    history: list[dict[str, float]] = []
    mlf = _MlflowLogger(tracking_uri, run_name, {**cfg.as_dict(), "train_reports": len(corpus),
                                                  "n_pt": vocab.n_pt, "n_drug": len(vocab.drug_tokens)})
    started = time.monotonic()
    running = 0.0
    model.train()
    for step in range(cfg.steps):
        for g in opt.param_groups:
            g["lr"] = lr_at(step, cfg)
        b = _to(maker.batch(rng.integers(0, len(corpus), size=cfg.batch_size)), device)
        loss = soft_cross_entropy(model(b.tokens, b.types, b.roles, b.query_pos), b.target)
        if model.drug_head is not None and (b.drug_target >= 0).any():
            used = b.drug_target >= 0
            dl = model.drug_logits(b.tokens[used], b.types[used], b.roles[used], b.drug_query_pos[used])
            loss = loss + cfg.drug_loss_weight * F.cross_entropy(dl, b.drug_target[used])
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        running += loss.item()
        if (step + 1) % cfg.eval_every == 0 or step + 1 == cfg.steps:
            point = {"step": step + 1, "train_loss": running / ((step % cfg.eval_every) + 1),
                     "seconds": time.monotonic() - started}
            running = 0.0
            if val is not None:
                point.update({f"val_{k}": v for k, v in masked_reaction_metrics(model, val, cfg.eval_reports).items()
                              if k != "reports"})
            history.append(point)
            mlf.metrics(point, step + 1)
            log(point)
    result: dict[str, object] = {"config": cfg.as_dict(), "model": mcfg.as_dict(), "history": history,
                                 "train_reports": len(corpus), "parameters": sum(p.numel() for p in model.parameters())}
    if out_dir is not None:
        out_dir.mkdir(parents=True, exist_ok=True)
        ckpt = out_dir / "safetyformer.pt"
        torch.save({"state_dict": {k: v.cpu() for k, v in model.state_dict().items()}, "model_config": mcfg.as_dict(),
                    "train_config": cfg.as_dict()}, ckpt)
        vocab.save(out_dir / "vocab.json")
        result["checkpoint"] = str(ckpt)
        result["checkpoint_sha256"] = hashlib.sha256(ckpt.read_bytes()).hexdigest()
        mlf.artifact(ckpt)
    mlf.close()
    result["device"] = str(device)
    result["model_obj"] = model.cpu().eval()
    return result


def load_checkpoint(path: Path) -> tuple[SafetyFormer, Vocab]:
    blob = torch.load(path, map_location="cpu", weights_only=False)
    model = SafetyFormer(ModelConfig(**blob["model_config"]))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    return model, Vocab.load(path.parent / "vocab.json")


class _MlflowLogger:
    """MLflow tracking that never breaks training: if MLflow fails, metrics still go to the history."""

    def __init__(self, uri: str | None, run_name: str, params: dict[str, object]) -> None:
        self.ok = False
        if uri is None:
            return
        try:
            os.environ.setdefault("MLFLOW_DISABLE_AGENT_HINT", "1")
            import mlflow

            mlflow.set_tracking_uri(uri)
            mlflow.set_experiment("pharmavigil-safetyformer")
            self.run = mlflow.start_run(run_name=run_name)
            flat = {k: v for k, v in params.items() if not isinstance(v, dict)}
            flat.update({f"views.{k}": v for k, v in params.get("views", {}).items()})
            mlflow.log_params(flat)
            self.mlflow = mlflow
            self.ok = True
        except Exception as exc:  # noqa: BLE001 - tracking is optional by design
            print(f"MLflow disabled: {exc}")

    def metrics(self, point: dict[str, float], step: int) -> None:
        if self.ok:
            self.mlflow.log_metrics({k: float(v) for k, v in point.items() if k != "step"}, step=step)

    def artifact(self, path: Path) -> None:
        if self.ok:
            self.mlflow.log_artifact(str(path))

    def close(self) -> None:
        if self.ok:
            self.mlflow.end_run()
