import os, re, sys
from fix_conditions import ROOT, ORIGIN, discover_versions, expand_block

def ideal_content(origin_path, vname):
    o_lines = open(origin_path, encoding='utf-8').read().split('\n')
    out = []
    j = 0
    while j < len(o_lines):
        if re.match(r'^\s*//\s*IF\s+', o_lines[j]):
            block_out, end = expand_block(o_lines, j, vname)
            out.extend(block_out)
            j = end + 1
        else:
            out.append(o_lines[j]); j += 1
    return '\n'.join(out)

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
            ideal = ideal_content(op, v)
            actual = open(p, encoding='utf-8').read()
            if ideal != actual:
                i_lines = ideal.split('\n')
                a_lines = actual.split('\n')
                for i in range(max(len(i_lines), len(a_lines))):
                    il = i_lines[i] if i < len(i_lines) else '<EOF>'
                    al = a_lines[i] if i < len(a_lines) else '<EOF>'
                    if il != al:
                        print(f'== {v} {fn} L{i+1}')
                        print(f'  期望: {il.strip()[:70]}')
                        print(f'  实际: {al.strip()[:70]}')
                        break
                total += 1
print('不一致文件数:', total)
if total > 0:
    sys.exit(1)  # CI 用：不一致则构建失败，防止坏生成物进 Release
