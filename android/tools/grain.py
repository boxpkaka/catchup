"""生成雾面颗粒的纹理 res/drawable-nodpi/grain.png（见 Flow.grainShader）：python3 tools/grain.py

512×512、可以无缝平铺的胶片颗粒：高斯白噪声做两次环形高斯模糊（细颗粒 σ=0.7 格，加三成半 σ=1.6 格的粗一点的团），
再归一化。颗粒边缘是柔的，不是一格一格的方块。存成 8 位灰度，128 是不变，往上是亮粒、往下是暗粒（约 ±2.6σ 到头），
App 里拆成亮粒、暗粒两张分别叠上去。只用标准库。
"""
import math
import random
import struct
import zlib
from pathlib import Path

N = 512


def blur(img, sigma):
    """环形（首尾相接）高斯模糊，横竖各一遍，结果仍然可以无缝平铺。"""
    r = max(1, math.ceil(sigma * 3))
    k = [math.exp(-i * i / (2 * sigma * sigma)) for i in range(-r, r + 1)]
    s = sum(k)
    k = [v / s for v in k]
    rows = [[sum(k[j] * row[(x + j - r) % N] for j in range(len(k))) for x in range(N)] for row in img]
    cols = [[sum(k[j] * rows[(y + j - r) % N][x] for j in range(len(k))) for x in range(N)] for y in range(N)]
    return cols


def normalized(img):
    flat = [v for row in img for v in row]
    mean = sum(flat) / len(flat)
    std = math.sqrt(sum((v - mean) ** 2 for v in flat) / len(flat))
    return [[(v - mean) / std for v in row] for row in img]


def main():
    rnd = random.Random(7)
    white = [[rnd.gauss(0, 1) for _ in range(N)] for _ in range(N)]
    fine = normalized(blur(white, 0.7))
    clump = normalized(blur(white, 1.6))
    grain = normalized([[f + 0.35 * c for f, c in zip(fr, cr)] for fr, cr in zip(fine, clump)])
    raw = bytearray()
    for row in grain:
        raw.append(0)
        for v in row:
            n = max(-1.0, min(1.0, v / 2.6))
            raw.append(round(128 + 127 * n))

    def chunk(t, c):
        return struct.pack('>I', len(c)) + t + c + struct.pack('>I', zlib.crc32(t + c) & 0xFFFFFFFF)

    out = Path(__file__).resolve().parent.parent / 'res' / 'drawable-nodpi' / 'grain.png'
    out.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', N, N, 8, 0, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))
    print(out, out.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
