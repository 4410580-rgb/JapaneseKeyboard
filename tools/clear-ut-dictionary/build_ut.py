#!/usr/bin/env python3
"""
透過キーボード（自分用）: Mozc UT 辞書をまとめて、アプリに入れる追加辞書の元データ（TSV）を作る。

使い方: python3 build_ut.py <作業ディレクトリ> <アプリの id.def>
出力:   <作業ディレクトリ>/ut.tsv（読み \\t 表記 \\t コスト \\t 品詞ID）

元の辞書: https://github.com/utuhiro78 の mozcdic-ut-*（各リポジトリのライセンスに従う。自分用のビルドにだけ使う）
処理は merge-ut-dictionaries（Apache-2.0）の merge_dictionaries.py に近い:
  - 表記を NFKC で正規化し、1文字・26文字以上の表記は外す
  - 英数字だけの表記は外す（かなの読みから英字が出る雑音が多いため）
  - 同じ（読み, 表記）は一番低いコストを残す
  - 品詞はすべて「名詞,一般」
"""
import bz2
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

SOURCES = [
    "jawiki", "sudachidict", "neologd", "skk-jisyo",
    "alt-cannadic", "edict2", "place-names", "personal-names",
]
YOMI_RE = re.compile(r"^[ぁ-ゖー]+$")


def noun_id(id_def: Path) -> int:
    for line in id_def.read_text(encoding="utf-8").splitlines():
        num, _, pos = line.partition(" ")
        if pos.startswith("名詞,一般,"):
            return int(num)
    raise SystemExit("名詞,一般 not found in id.def")


def main():
    work = Path(sys.argv[1])
    id_def = Path(sys.argv[2])
    work.mkdir(parents=True, exist_ok=True)
    pos_id = noun_id(id_def)
    best = {}
    for name in SOURCES:
        repo = work / f"mozcdic-ut-{name}"
        if not repo.exists():
            subprocess.run(
                ["git", "clone", "--depth", "1",
                 f"https://github.com/utuhiro78/mozcdic-ut-{name}.git", str(repo)],
                check=True,
            )
        src = repo / f"mozcdic-ut-{name}.txt.bz2"
        added = 0
        with bz2.open(src, "rt", encoding="utf-8") as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if len(cols) < 5:
                    continue
                yomi, _, _, cost, hyouki = cols[:5]
                if not YOMI_RE.match(yomi):
                    continue
                hyouki = unicodedata.normalize("NFKC", hyouki).replace("~", "〜")
                if len(hyouki) < 2 or len(hyouki) > 25 or hyouki.isascii():
                    continue
                if "\t" in hyouki or "\n" in hyouki:
                    continue
                try:
                    c = max(0, min(int(cost), 32000))
                except ValueError:
                    continue
                key = (yomi, hyouki)
                old = best.get(key)
                if old is None:
                    added += 1
                    best[key] = c
                elif c < old:
                    best[key] = c
        print(f"{name}: +{added}", flush=True)
    out = work / "ut.tsv"
    with out.open("w", encoding="utf-8") as f:
        for (yomi, hyouki), c in sorted(best.items()):
            f.write(f"{yomi}\t{hyouki}\t{c}\t{pos_id}\n")
    print(f"total {len(best)} entries -> {out}")


if __name__ == "__main__":
    main()
