import os, re, time

"""
fix_conditions.py v5 — origin 模板完整条件展开器（替代工具的条件处理）
=====================================================================
ModMultiVersionTool 条件处理缺陷（实证）：
  1. IF 匹配时后续分支被错误解注释（双活）
  2. 匹配分支被无脑去掉行首 //（注释文字也变活代码）

本脚本（init.sh 后运行）对每个版本目录的 .java 文件，直接用 origin 模板
按版本号展开条件块并覆盖生成物（完全不依赖工具的条件处理结果）。

分支行目标状态：
  - origin 活代码行         → 真分支活 / 假分支注释
  - origin '//代码' 注释行  → 真分支活（去 //）/ 假分支注释
  - origin 注释文字行        → 永远注释
"//代码" vs "注释文字" 区分（启发式）：剥掉行首 // 与缩进后，
  是合法 Java 语句片段（方法调用/关键字/注解/赋值/分号结尾）→ 代码行；
  否则（中文/英文句子、javadoc * 行）→ 注释文字。
用法: python fix_conditions.py
"""

# 路径（相对化：脚本应放在项目 scripts/ 目录下，上一级是项目根）
# 这样同一份脚本在本地 Windows 与 CI Linux 都能运行，无需改路径
HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)  # scripts/ 的上一级 = 项目根
ROOT = os.path.join(PROJECT, 'fabric')
ORIGIN = os.path.join(ROOT, 'origin', 'src', 'main')


def discover_versions():
    """自动扫描 fabric/ 下所有版本目录（排除 origin），按版本号排序。
    新增版本目录后无需改脚本。
    【26.x 例外】26.x 是独立 gradle 项目（未混淆 mojmap，非 origin 模板生成），
    不能从 origin 展开（origin 是 yarn 版）→ 排除 26.x，防模板污染。"""
    vs = []
    if os.path.isdir(ROOT):
        for d in os.listdir(ROOT):
            if d.startswith('fabric-') and d != 'fabric-origin':
                v = d[len('fabric-'):]
                if v.startswith('26.'):
                    continue  # 26.x 独立项目，跳过
                vs.append(v)
    return sorted(vs, key=lambda s: [int(x) for x in s.split('.')])

def ver(v):
    return tuple(int(x) for x in v.split('.'))

def cond_true(cond, vname):
    m = re.match(r'\s*(>=|<=|>|<)?\s*(?:fabric-)?([\d.]+)\s*$', cond)
    if not m: return None
    op = m.group(1) or '='
    target = ver(m.group(2))
    cur = ver(vname)
    if op == '>=': return cur >= target
    if op == '<=': return cur <= target
    if op == '>': return cur > target
    if op == '<': return cur < target
    return cur == target

def find_end(lines, start):
    depth = 0
    for j in range(start, len(lines)):
        # 支持任意层 // 前缀（嵌套块在未解注释的外层内是双重注释 // // IF）
        if re.match(r'^\s*//\s*(//\s*)*IF\b', lines[j]): depth += 1
        elif re.match(r'^\s*//\s*(//\s*)*END\s*IF', lines[j]):
            depth -= 1
            if depth == 0: return j
    return None

def is_marker(line):
    return bool(re.match(r'^\s*//\s*(IF|ELSE|END)', line))

def is_code_fragment(text):
    s = text.lstrip()
    if not s: return False
    if s.startswith('*'): return False
    if s.startswith('//'): return False
    # Java 语法收尾符号（如 "),", ")", "};", '",' 等）
    if re.match(r'^[)\]},;\"\']+$', s): return True
    if re.match(r'^[@\w][\w.$]*\s*\(', s): return True
    if re.match(r'^(import|return|private|public|protected|static|final|boolean|int|float|double|long|void|if|for|while|switch|case|default|new|throw|try|catch|break|continue|else|this|super)\b', s): return True
    if re.match(r'^[@\w][\w.]*\s*=', s): return True
    if s in ('}', '{', '};', '});'): return True
    if s.endswith(';') and re.match(r'^[@\w]', s): return True
    # 兜底：以代码结尾符结尾、且首字符非自然语言 → 视为代码
    if re.match(r'^[^A-Za-z\u4e00-\u9fff]', s) and (s.endswith(')') or s.endswith(';') or s.endswith(',') or s.endswith('}') or s.endswith('"')):
        return True
    return False

def strip_one(l):
    """去掉行首的 //（保留 // 前的缩进）。用于嵌套块剥壳。"""
    s = l.lstrip()
    if s.startswith('//'):
        idx = l.index('//')
        return l[:idx] + l[idx + 2:]
    return l

def expand_block(o_lines, j, vname, depth=0):
    """展开单个条件块（从 IF 标记行 j 开始），返回 (输出行列表, 块结束行号)。支持嵌套（递归）。"""
    m = re.match(r'^(\s*)//\s*IF\s+(.+)', o_lines[j])
    end = find_end(o_lines, j)
    if m is None or end is None:
        return [o_lines[j]], j
    # 解析分支: (kind, cond, marker_idx, s, e)
    conds = []
    k = j + 1
    seg_end = end
    while k < end:
        if re.match(r'^\s*//\s*ELSE\s+IF\s+', o_lines[k]) or re.match(r'^\s*//\s*ELSE\s*$', o_lines[k]):
            seg_end = k; break
        k += 1
    conds.append(('IF', m.group(2).strip(), j, j + 1, seg_end))
    k = seg_end
    while k < end:
        em = re.match(r'^\s*//\s*ELSE\s+IF\s+(.+)', o_lines[k])
        em2 = re.match(r'^\s*//\s*ELSE\s*$', o_lines[k])
        if em:
            e2 = end
            k2 = k + 1
            while k2 < end:
                if re.match(r'^\s*//\s*ELSE\s+IF', o_lines[k2]) or re.match(r'^\s*//\s*ELSE\s*$', o_lines[k2]):
                    e2 = k2; break
                k2 += 1
            conds.append(('ELSE_IF', em.group(1).strip(), k, k + 1, e2))
            k = e2
        elif em2:
            conds.append(('ELSE', None, k, k + 1, end))
            k = end
        else:
            k += 1
    # 分支真假
    prev_true = False
    branches = []
    for (kind, cond, midx, s, e) in conds:
        if kind == 'IF':
            live = bool(cond_true(cond, vname))
        elif kind == 'ELSE_IF':
            live = (not prev_true) and bool(cond_true(cond, vname))
        else:
            live = not prev_true
        branches.append((kind, cond, midx, s, e, live))
        prev_true = prev_true or live
    # 输出
    out = [o_lines[j]]  # IF 标记
    for bi, (kind, cond, midx, s, e, live) in enumerate(branches):
        if bi > 0:
            out.append(o_lines[midx])  # ELSE IF / ELSE 标记
        i = s
        while i < e:
            ol = o_lines[i]
            stripped = ol.lstrip()
            if stripped == '':
                out.append(ol); i += 1; continue
            # 内层条件块：双重注释形式（// // IF）——剥一重 // 后递归展开
            # 【v6 修复】仅当当前分支为 live 时才递归展开；外层为死分支（live=False）时
            # 内层整块应保持注释（走下方单行逻辑：活代码行会加 //、注释行保持），
            # 否则内层递归会无视外层假分支状态，把内层活代码行泄漏成真代码。
            if live and re.match(r'^\s*//\s*//\s*IF\s+', stripped):
                inner_end = find_end(o_lines, i)
                if inner_end is None:
                    out.append(ol); i += 1; continue
                # 构造剥壳行列表（剥掉最外层 //，模拟外层解注释后的状态）
                shelled = [strip_one(l) for l in o_lines[i:inner_end + 1]]
                inner_out, _ = expand_block(shelled, 0, vname, depth + 1)
                out.extend(inner_out)
                i = inner_end + 1
                continue
            # 判断目标状态（可靠规则，无启发式）：
            #   行首 //（ol 无前导缩进）= 模板注释：
            #     //# 显式标记 / 双重 // / javadoc * → 永注释
            #     其他 → 被注释的代码行（真分支活 / 假分支注释）
            #   带缩进的 //（ol 有前导缩进）= 活代码块内的注释文字 → 永注释
            #   活代码 → 真分支活 / 假分支注释
            if stripped.startswith('//'):
                if ol.startswith('//'):
                    rest = stripped[2:]
                    if rest.startswith('#') or rest.lstrip().startswith('//') or rest.lstrip().startswith('*'):
                        target_comment = True
                    else:
                        target_comment = not live
                else:
                    target_comment = True
            else:
                target_comment = not live
            if target_comment:
                if stripped.startswith('//'):
                    out.append(ol)          # 已是注释（保持原样）
                else:
                    out.append('//' + ol)   # 活代码 → 注释
            else:
                if stripped.startswith('//'):
                    # 去 //（保留 // 后内容，含原有缩进）
                    idx = ol.index('//')
                    out.append(ol[:idx] + ol[idx + 2:])
                else:
                    out.append(ol)          # 已活（保持）
            i += 1
    out.append(o_lines[end])  # END IF 标记
    return out, end

def write_retry(path, text, attempts=8, delay=0.4):
    """写文件，遇 PermissionError 重试。
    某些环境（Windows 文件系统保护层）会对已存在文件间歇性拒绝写入，
    重试几次即可成功，避免整轮展开因单个文件失败而中断。"""
    for i in range(attempts):
        try:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text)
            return
        except PermissionError:
            if i == attempts - 1:
                raise
            time.sleep(delay)

def expand_file(gen_path, origin_path, vname):
    o_lines = open(origin_path, encoding='utf-8').read().split('\n')
    out = []
    j = 0
    while j < len(o_lines):
        line = o_lines[j]
        m = re.match(r'^(\s*)//\s*IF\s+(.+)', line)
        if not m:
            out.append(line); j += 1; continue
        block_out, end = expand_block(o_lines, j, vname)
        out.extend(block_out)
        j = end + 1
    result = '\n'.join(out)
    write_retry(gen_path, result)
    return 1 if result != '\n'.join(o_lines) else 0

def main():
    total = 0
    for v in discover_versions():
        base = os.path.join(ROOT, f'fabric-{v}', 'src', 'main')
        if not os.path.exists(base): continue
        for r2, dirs, files in os.walk(base):
            for fn in files:
                if not fn.endswith('.java'): continue
                p = os.path.join(r2, fn)
                rel = os.path.relpath(p, base)
                op = os.path.join(ORIGIN, rel)
                if not os.path.exists(op): continue
                c = expand_file(p, op, v)
                total += c
    print(f'fix v5: 展开完成（{total} 个文件有改动）')

if __name__ == '__main__':
    main()
