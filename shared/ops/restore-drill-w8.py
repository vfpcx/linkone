# -*- coding: utf-8 -*-
"""
W8-L1 还原演练脚本（PII-S2 明文收缩 · V34 唯一恢复手段验证）
============================================================
对应：shared/test-plan/13-pii-w8-delivery-report.md §8.5（W8-L1）/ §10.3
目的：验证「全库备份 backup_w8_gap_delete_20260901.sql（37 表 27700 行 INSERT）可完整还原」，
      作为 V34 不可逆段（DROP *__bak 明文列）的唯一恢复手段做本地模拟演练：
        1) 解析备份文件 → 每表文件行数 N_file（事实源）
        2) 建临时库 restore_drill_w8_<ts>（源库 cangchu_dev 同构：CREATE TABLE ... LIKE）
        3) 关闭外键检查后逐表参数化还原（SQL 字面量 tokenize → pymysql executemany）
        4) 校验：
           a. 逐表 COUNT(*) == N_file（缺行检测）
           b. PII 8 张敏感表（users/tenants/tenant_applications/inquiry_requests/
              customer_prices/sms_codes/wholesaler_applications/blacklist）全列逐行值比对
              （按主键序 zip，NULL/日期/Decimal 归一）—— 即「关键列校验和」的强校验落地
        5) 默认 DROP 临时库（--keep 保留供人工复核）

只读源库（SHOW/CREATE TABLE LIKE 快照结构），对源库数据零写入；演练库名前缀 restore_drill_w8_。
Windows 本机 MySQL（dev,local profile）：127.0.0.1:3306 root，密码取自 application-local.yml。

用法：
  python shared/ops/restore-drill-w8.py --dry-run                 # 仅解析文件自检
  python shared/ops/restore-drill-w8.py --password <pwd>          # 完整演练（默认演练后删库）
  python shared/ops/restore-drill-w8.py --password <pwd> --keep   # 保留临时库
"""
import argparse
import datetime
import io
import re
import sys
from decimal import Decimal

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

DEFAULT_BACKUP = r"d:\chenxu\my-linkone\backup_w8_gap_delete_20260901.sql"
# PII 敏感表（§8.5 抽样校验对：V34 涉及的 8 表全量逐行值比对）
PII_TABLES = ['users', 'tenants', 'tenant_applications', 'inquiry_requests',
              'customer_prices', 'sms_codes', 'wholesaler_applications', 'blacklist']
ESC = {'n': '\n', 'r': '\r', 't': '\t', '0': '\0', 'b': '\b',
       'Z': '\x1a', '\\': '\\', "'": "'", '"': '"'}


def parse_tuple_body(body):
    """解析单行 tuple 文本（无首尾括号）→ Python 值列表。
    处理：单引号字符串（'' 转义 + \\ 反斜杠转义）、NULL、数字/裸 token。"""
    vals, i, n = [], 0, len(body)
    while i < n:
        c = body[i]
        if c in ' \t':
            i += 1
            continue
        if c == ',':
            i += 1
            continue
        if c == "'":
            i += 1
            buf = []
            while i < n:
                ch = body[i]
                if ch == '\\':
                    if i + 1 < n:
                        nx = body[i + 1]
                        buf.append(ESC.get(nx, nx))
                        i += 2
                    else:
                        i += 1
                elif ch == "'":
                    if i + 1 < n and body[i + 1] == "'":
                        buf.append("'")
                        i += 2
                    else:
                        i += 1
                        break
                else:
                    buf.append(ch)
                    i += 1
            vals.append(''.join(buf))
        elif body.startswith('NULL', i):
            vals.append(None)
            i += 4
        else:
            j = i
            while j < n and body[j] != ',':
                j += 1
            tok = body[i:j].strip()
            vals.append(tok)
            i = j
    return vals


def parse_backup(path):
    """返回 {'table': {'cols': [...], 'rows': [[...]...]}}，按文件出现顺序。"""
    text = open(path, encoding='utf-8', errors='replace').read()
    tables, order = {}, []
    cur = None
    for raw in text.split('\n'):
        ln = raw.strip()
        if not ln:
            continue
        m = re.match(r"^INSERT INTO `([a-z_]+)` \((.*)\) VALUES$", ln)
        if m:
            name = m.group(1)
            if name not in tables:
                # 列名去掉反引号与空白，后续拼装时统一加反引号
                cols = [c.strip().strip('`') for c in m.group(2).split(',')]
                tables[name] = {'cols': cols, 'rows': []}
                order.append(name)
            cur = name
            continue
        if cur is not None:
            if ln.startswith('('):
                # tuple 行：去掉首尾括号（末尾可能是 `),` 或 `);`）
                if ln.endswith(');') or ln.endswith('),'):
                    body = ln[1:-2]
                elif ln.endswith(')'):
                    body = ln[1:-1]
                else:
                    body = ln[1:]
                tables[cur]['rows'].append(parse_tuple_body(body))
                if ln.endswith(');'):
                    cur = None
            elif ln == ';':
                cur = None
    return tables, order


def norm(v):
    """DB 值 vs 文件文本值的可比较归一。"""
    if v is None:
        return ('NULL',)
    if isinstance(v, datetime.datetime):
        return ('STR', v.strftime('%Y-%m-%d %H:%M:%S'))
    if isinstance(v, datetime.date):
        return ('STR', v.strftime('%Y-%m-%d'))
    if isinstance(v, datetime.time):
        return ('STR', str(v))
    if isinstance(v, int):
        return ('DEC', v)
    if isinstance(v, float):
        return ('DEC', Decimal(str(v)))
    if isinstance(v, str):
        try:
            return ('DEC', int(v))
        except ValueError:
            try:
                return ('DEC', Decimal(v))
            except Exception:
                return ('STR', v)
    if isinstance(v, Decimal):
        return ('DEC', v)
    return ('STR', str(v))


def norm_file(v):
    if v is None:
        return ('NULL',)
    try:
        return ('DEC', int(v))
    except ValueError:
        try:
            return ('DEC', Decimal(v))
        except Exception:
            return ('STR', v)


def run_drill(args):
    tables, order = parse_backup(args.backup)
    if args.only:
        picks = [t.strip() for t in args.only.split(',') if t.strip()]
        missing = [t for t in picks if t not in tables]
        if missing:
            print(f"!! --only 指定表不在备份中: {missing}")
            return 1
        order = [t for t in order if t in picks]
    total_rows = sum(len(tables[t]['rows']) for t in order)
    print(f"[解析] 备份 {args.backup}")
    print(f"[解析] {len(order)} 表 / {total_rows} 行")
    if args.dry_run:
        for t in order:
            cols = tables[t]['cols']
            print(f"  - {t}: {len(tables[t]['rows'])} 行, {len(cols)} 列: "
                  f"{','.join(c.strip() for c in cols[:6])}{'...' if len(cols) > 6 else ''}")
        # 抽样值验证
        sample = tables['users']['rows'][0] if 'users' in tables else None
        if sample:
            print(f"[抽样] users 首行前 5 值: {sample[:5]}")
            print(f"[抽样] 尾行末值 NULL? {sample[-1] is None or sample[-1] == 'NULL'}")
        print("[dry-run] 解析自检通过")
        return 0

    import pymysql
    from decimal import Decimal

    dbname = 'restore_drill_w8_' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S')
    conn = pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, charset='utf8mb4')
    cur = conn.cursor()
    try:
        cur.execute(f"CREATE DATABASE `{dbname}`")
        cur.execute("SET FOREIGN_KEY_CHECKS=0")
        # 1) 从源库复制结构（仅备份涉及表）
        for t in order:
            cur.execute(f"CREATE TABLE `{dbname}`.`{t}` LIKE `{args.source_db}`.`{t}`")
        # 2) 参数化还原
        t0 = datetime.datetime.now()
        for t in order:
            clean = tables[t]['cols']
            rows = tables[t]['rows']
            ph = ','.join(['%s'] * len(clean))
            sql = f"INSERT INTO `{dbname}`.`{t}` ({','.join('`'+c+'`' for c in clean)}) VALUES ({ph})"
            for i in range(0, len(rows), 500):
                cur.executemany(sql, rows[i:i + 500])
            conn.commit()
        dt = (datetime.datetime.now() - t0).total_seconds()
        print(f"[还原] 建库 {dbname}（{len(order)} 表结构 LIKE 源库）→ 参数化导入完成 {dt:.1f}s")

        # 3a) 行数对比
        print("\n=== 行数对比（file vs restored）===")
        bad = 0
        for t in order:
            file_n = len(tables[t]['rows'])
            cur.execute(f"SELECT COUNT(*) FROM `{dbname}`.`{t}`")
            db_n = cur.fetchone()[0]
            ok = 'OK' if file_n == db_n else 'MISMATCH'
            if file_n != db_n:
                bad += 1
            print(f"  {t:<32} file={file_n:<7} restored={db_n:<7} {ok}")
        if bad:
            print(f"!! {bad} 表行数不一致")
            return 1

        # 3b) PII 敏感表全列逐行值比对（尊重 --only 子集）
        pii_check = [t for t in PII_TABLES if t in tables and t in order]
        print(f"\n=== PII 敏感表逐行值比对（{len(pii_check)} 表）===")
        pbad = 0
        for t in pii_check:
            file_rows = tables[t]['rows']
            cur.execute(f"SELECT * FROM `{dbname}`.`{t}` ORDER BY `{tables[t]['cols'][0].strip()}`")
            db_rows = cur.fetchall()
            n_file, n_db = len(file_rows), len(db_rows)
            mism = 0
            first = None
            for i in range(min(n_file, n_db)):
                fr = [norm_file(v) for v in file_rows[i]]
                dr = [norm(v) for v in db_rows[i]]
                if len(fr) != len(dr):
                    mism += 1
                    if first is None:
                        first = f"列数不一致 file={len(fr)} db={len(dr)}"
                    continue
                for a, b in zip(fr, dr):
                    if a != b:
                        mism += 1
                        if first is None:
                            first = f"第{i}行 {a} != {b}"
                        break
            if n_file != n_db:
                mism += 1
                if first is None:
                    first = f"行数不一致 file={n_file} db={n_db}"
            ok = 'PASS' if mism == 0 else f'FAIL ({mism})'
            if mism:
                pbad += 1
            print(f"  {t:<32} rows={n_db:<6} {ok}" + (f"  e.g. {first}" if first else ""))
        print(f"\n=== 演练结论：行数对比 {'通过' if bad == 0 else '失败'}；"
              f"PII 表值比对 {'通过' if pbad == 0 else '失败'} ===")
        if bad or pbad:
            return 1
        return 0
    finally:
        if not args.keep:
            cur.execute(f"DROP DATABASE IF EXISTS `{dbname}`")
            conn.commit()
            print(f"[清理] 已删除临时库 {dbname}")
        cur.close()
        conn.close()


def main():
    ap = argparse.ArgumentParser(description='W8-L1 还原演练（V34 备份恢复验证）')
    ap.add_argument('--backup', default=DEFAULT_BACKUP)
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=3306)
    ap.add_argument('--user', default='root')
    ap.add_argument('--password', default='')
    ap.add_argument('--source-db', default='cangchu_dev')
    ap.add_argument('--keep', action='store_true', help='演练后保留临时库')
    ap.add_argument('--only', default='', help='仅演练指定表（逗号分隔），默认全 37 表')
    ap.add_argument('--dry-run', action='store_true', help='仅解析自检，不连库')
    args = ap.parse_args()
    sys.exit(run_drill(args))


if __name__ == '__main__':
    main()
