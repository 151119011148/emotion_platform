# -*- coding: utf-8 -*-
"""按 Flyway 6.5.7 的算法算本地迁移文件 checksum，与库里的记录逐条对齐。

Flyway ChecksumCalculator: 逐行读取（\n / \r\n / \r 都算换行），
去掉行尾换行符后以 UTF-8 字节喂给 CRC32，最后取 (int) crc32.getValue()。
"""
import glob
import os
import re
import zlib

D = r'D:\gaofeng\emotion_platform\emotion-server\src\main\resources\db\migration'


def flyway_checksum(path):
    data = open(path, 'rb').read()
    text = data.decode('utf-8')
    if text.startswith('\ufeff'):
        text = text[1:]
    crc = 0
    for line in re.split(r'\r\n|\n|\r', text):
        crc = zlib.crc32(line.encode('utf-8'), crc)
    return crc


def signed32(v):
    return v - 0x100000000 if v >= 0x80000000 else v


# 库里记录：version -> (description, checksum)
DB = {
    36: ('waverider scheduler job', 1463649042),
    37: ('waverider template sort seal strength', 58368587),
    38: ('position exit price', -1756680027),
    39: ('node event space break comment', 202139164),
    40: ('node event candidate pool', None),
    41: ('review objective scheduler job', 2143004524),
    42: ('review objective scheduler job', -1954108541),
}

print('=== 本地文件 checksum vs 库记录 ===')
local = {}
for p in sorted(glob.glob(os.path.join(D, 'V*.sql'))):
    n = os.path.basename(p)
    m = re.match(r'V(\d+)__', n)
    v = int(m.group(1))
    if v < 36:
        continue
    ck = signed32(flyway_checksum(p))
    local.setdefault(v, []).append((n, ck))
    desc, want = DB.get(v, (None, None))
    ok = 'match' if want is not None and ck == want else ('库无此版本' if want is None else 'MISMATCH(库=%s)' % want)
    print('  V%-3d %-52s crc=%-12s %s' % (v, n, ck, ok))

print('\n=== 库里每个版本能否在本地找到同 checksum 的文件 ===')
allf = []
for p in sorted(glob.glob(os.path.join(D, 'V*.sql'))):
    allf.append((os.path.basename(p), signed32(flyway_checksum(p))))
for v in sorted(DB):
    desc, want = DB[v]
    if want is None:
        print('  V%-3d %-40s checksum=NULL（空文件/未知）' % (v, desc))
        continue
    hit = [n for n, c in allf if c == want]
    print('  V%-3d %-40s -> %s' % (v, desc, hit or '本地没有同 checksum 的文件'))

# 额外：把 review 任务文件的两种头部变体都算一遍，看能对上哪个版本
print('\n=== review 任务文件的头部变体 ===')
for p in sorted(glob.glob(os.path.join(D, 'V4*review_objective*.sql'))):
    raw = open(p, 'rb').read().decode('utf-8')
    print('  文件:', os.path.basename(p))
    for tag in ('V41', 'V42'):
        variant = re.sub(r'-- Flyway migration V\d+: 每日复盘',
                         '-- Flyway migration %s: 每日复盘' % tag, raw, count=1)
        ck = signed32(zlib.crc32(b''.join(
            l.encode('utf-8') for l in re.split(r'\r\n|\n|\r', variant))))
        print('     头部写 %s -> crc=%s  (库 41=%s, 42=%s)' % (tag, ck, 2143004524, -1954108541))
