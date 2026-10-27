"""Convert the handwritten ledger to the app's UTF-8 CSV without guessing duplicates."""

import csv
import re
from collections import Counter, defaultdict
from datetime import date
from decimal import Decimal
from pathlib import Path


def clean(source: Path, destination: Path) -> None:
    rows = []
    channel = "未注明"
    for line_number, raw in enumerate(source.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        heading = re.fullmatch(r"以下开始(.+)账单", line)
        if heading:
            channel = heading[1]
            continue
        match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})，(.+)，(\d+(?:\.\d+)?)\s*[rR]", line)
        if not match:
            raise ValueError(f"第 {line_number} 行格式无法识别：{line}")
        day, description, amount = match.groups()
        date.fromisoformat(day)
        value = Decimal(amount)
        if value <= 0:
            raise ValueError(f"第 {line_number} 行金额必须大于零")
        rows.append([day, "", format(value, ".2f"), "人民币", "", description.strip(), channel])

    # Python sorting is stable, preserving source order within each date.
    rows.sort(key=lambda row: row[0], reverse=True)
    with destination.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["日期", "时间", "金额", "币种", "类别", "说明", "来源"])
        writer.writerows(rows)

    exact = Counter(tuple(row) for row in rows)
    same_amount = defaultdict(list)
    for row in rows:
        same_amount[(row[0], Decimal(row[2]))].append(row)
    report = [
        "# 账单清洗说明", "",
        f"从原始文本提取 **{len(rows)} 笔**支出，合计 **{sum(Decimal(row[2]) for row in rows):.2f} 人民币**。",
        f"日期范围：{min(row[0] for row in rows)} 至 {max(row[0] for row in rows)}。", "",
        "- `r` / `R` 转为人民币，金额保留两位小数，日期统一为 YYYY-MM-DD。",
        "- 文件使用 UTF-8 BOM 和标准 CSV 引号转义，方便表格软件和应用读取。",
        "- 原始商品或商户名称保存在说明中；段落标题转为来源，首段来源设为“未注明”。",
        "- 原文未提供时间和类别，对应列留空。按日期倒序排列，同日保持原顺序。",
        "- 所有记录均保留；同日同额不足以确定重复，请结合支付流水核实。", "",
        "## 来源统计", "", "| 来源 | 笔数 | 人民币 |", "| --- | ---: | ---: |",
    ]
    for name in dict.fromkeys(row[6] for row in rows):
        group = [row for row in rows if row[6] == name]
        report.append(f"| {name} | {len(group)} | {sum(Decimal(row[2]) for row in group):.2f} |")
    report += ["", "## 完全相同的记录（保留）", ""]
    for row, count in exact.items():
        if count > 1:
            report.append(f"- {row[0]}，{row[5]}，{row[2]} 人民币，来源：{row[6]}，共 {count} 笔。")
    report += ["", "## 同日同额、描述或来源不同的记录（待核对）", ""]
    for (day, amount), group in same_amount.items():
        if len({tuple(row) for row in group}) > 1:
            descriptions = "；".join(f"{row[5]}（{row[6]}）" for row in group)
            report.append(f"- {day}，{amount:.2f} 人民币：{descriptions}。")
    destination.with_name("账单清洗说明.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(report[2])


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    clean(root / "账单.txt", root / "账单.csv")
