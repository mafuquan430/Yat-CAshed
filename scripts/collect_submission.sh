#!/usr/bin/env bash
# collect_submission.sh —— 把结题要交的材料按序号收进一个目录（不压缩，便于逐个上传）
# 用法：bash scripts/collect_submission.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/report/submission/dist}"

mkdir -p "$OUT"
rm -rf "$OUT"/* 2>/dev/null || true

copy() {  # copy <源> <目标名>
    local src="$1" dst="$2"
    if [ ! -f "$src" ]; then
        echo "  [缺失] $src"
        return 1
    fi
    cp "$src" "$OUT/$dst"
    printf "  %-34s %s\n" "$dst" "$(du -h "$src" | cut -f1)"
}

echo "收集结题材料 → $OUT"
copy "$ROOT/report/submission/大创结题-项目执行情况与研究总结.docx" "01-项目执行情况与研究总结.docx"
copy "$ROOT/report/submission/大创结题-项目执行情况与研究总结.md"   "01-项目执行情况与研究总结.md"
copy "$ROOT/report/paper/Yat-CAShed.pdf"                            "02-设计开发文档.pdf"
copy "$ROOT/report/slides/结题答辩.pptx"                            "03-结题答辩.pptx"
copy "$ROOT/report/slides/结题答辩-讲稿.md"                          "03-结题答辩-讲稿.md"
copy "$ROOT/soft-copyright/02-源程序/源程序鉴别材料.pdf"             "04-源程序鉴别材料.pdf"
copy "$ROOT/soft-copyright/03-说明书/说明书.pdf"                     "05-软件说明书.pdf"
copy "$ROOT/docs/结题材料清单.md"                                    "00-材料清单.md"

# 校验和：上传后可用 sha256sum -c 确认没传错文件
( cd "$OUT" && sha256sum ./* > SHA256SUMS.txt 2>/dev/null || true )

echo
echo "完成。逐个上传即可；核对用："
echo "  cd $OUT && sha256sum -c SHA256SUMS.txt"