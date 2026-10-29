#!/usr/bin/env python3
"""从 get_jobs 的 SQLite 库里提取 BOSS 字典数据。

用法：
    python tools/extract-boss-dict.py
    python tools/extract-boss-dict.py --db <路径> --out <目录>

输出：
    <out>/platform-option-dict.json   全量字典（人工核对用）
    <out>/V2__seed_platform_option.sql  Flyway 种子迁移（可直接进 resources/db/migration）
    <out>/boss-blacklist-sample.json   参考项目的黑名单样本（不作为种子，仅参考）

只读打开源库，不修改源库。仅用标准库。
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path(r"C:/development/workspace/get_jobs/db/getjobs.db")
DEFAULT_OUT = Path(__file__).resolve().parent / "out"

# 期望的字典类型；缺任何一类都要报出来，不静默通过
EXPECTED_TYPES = ["city", "industry", "jobType", "salary", "experience", "degree", "scale", "stage"]


def q(value: str) -> str:
    """SQLite 单引号转义。"""
    return "'" + str(value).replace("'", "''") + "'"


def read_options(conn: sqlite3.Connection) -> list[tuple[str, str, str, int]]:
    rows = conn.execute(
        "SELECT type, name, code, COALESCE(sort_order, 0) AS sort_order, id "
        "FROM boss_option ORDER BY type, sort_order, id"
    ).fetchall()

    seen: dict[tuple[str, str], str] = {}
    out: list[tuple[str, str, str, int]] = []
    for idx, (type_, name, code, sort_order, _id) in enumerate(rows):
        type_, name, code = type_.strip(), str(name).strip(), str(code).strip()
        if not type_ or not name or not code:
            continue
        key = (type_, code)
        if key in seen:
            print(f"  ! 重复 code，已跳过：{type_}/{code}（{name} 与 {seen[key]}）", file=sys.stderr)
            continue
        seen[key] = name
        out.append((type_, name, code, sort_order or idx))
    return out


def build_sql(options: list[tuple[str, str, str, int]]) -> str:
    head = """-- 由 tools/extract-boss-dict.py 自动生成，请勿手工编辑。
-- 数据来源：get_jobs 项目的 SQLite 字典表（boss_option）。
-- 重新生成会覆盖本文件。
"""
    body = [
        "CREATE TABLE IF NOT EXISTS platform_option (",
        "    id         INTEGER PRIMARY KEY AUTOINCREMENT,",
        "    platform   TEXT    NOT NULL,",
        "    type       TEXT    NOT NULL,",
        "    name       TEXT    NOT NULL,",
        "    code       TEXT    NOT NULL,",
        "    sort_order INTEGER NOT NULL DEFAULT 0",
        ");",
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_option ON platform_option(platform, type, code);",
        "",
    ]

    by_type: dict[str, list[tuple[str, int]]] = {}
    for type_, name, code, sort_order in options:
        by_type.setdefault(type_, []).append((name, code, sort_order))

    for type_ in EXPECTED_TYPES + [t for t in by_type if t not in EXPECTED_TYPES]:
        items = by_type.get(type_)
        if not items:
            continue
        body.append(f"-- {type_}：{len(items)} 条")
        values = ",\n".join(
            f"  ('boss', {q(type_)}, {q(name)}, {q(code)}, {sort_order})" for name, code, sort_order in items
        )
        body.append(
            "INSERT INTO platform_option (platform, type, name, code, sort_order) VALUES\n"
            f"{values}\n"
            "ON CONFLICT(platform, type, code) DO UPDATE SET"
            " name = excluded.name, sort_order = excluded.sort_order;"
        )
        body.append("")

    return head + "\n".join(body)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    if not args.db.is_file():
        print(f"找不到数据库：{args.db}", file=sys.stderr)
        return 1

    conn = sqlite3.connect(f"file:{args.db.as_posix()}?mode=ro", uri=True)
    options = read_options(conn)
    if not options:
        print("boss_option 里没有数据", file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)

    # 1. JSON 全量
    payload: dict[str, list[dict]] = {}
    for type_, name, code, sort_order in options:
        payload.setdefault(type_, []).append({"name": name, "code": code, "sortOrder": sort_order})
    json_path = args.out / "platform-option-dict.json"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    # 2. Flyway 种子
    sql_path = args.out / "V2__seed_platform_option.sql"
    sql_path.write_text(build_sql(options), encoding="utf-8")

    # 3. 黑名单样本（仅参考）
    try:
        bl = conn.execute("SELECT type, value FROM boss_blacklist ORDER BY type, id").fetchall()
    except sqlite3.Error:
        bl = []
    if bl:
        bl_path = args.out / "boss-blacklist-sample.json"
        bl_path.write_text(
            json.dumps([{"type": t, "value": v} for t, v in bl], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    else:
        bl_path = None

    conn.close()

    # 汇总
    counts: dict[str, int] = {}
    for type_, *_ in options:
        counts[type_] = counts.get(type_, 0) + 1
    print(f"源库：{args.db}")
    print(f"{'type':<12}{'count':>6}")
    for type_ in EXPECTED_TYPES:
        print(f"{type_:<12}{counts.get(type_, 0):>6}")
    missing = [t for t in EXPECTED_TYPES if t not in counts]
    extra = [t for t in counts if t not in EXPECTED_TYPES]
    if missing:
        print(f"缺失类型：{missing}")
    if extra:
        print(f"额外类型：{extra}")
    print(f"合计：{len(options)} 条")
    print()
    print(f"已生成：\n  {json_path}\n  {sql_path}")
    if bl_path:
        print(f"  {bl_path}（参考样本，不要当种子导入）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
