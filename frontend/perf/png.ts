import { randomBytes } from 'node:crypto'
import { crc32, deflateSync } from 'node:zlib'

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

// 色をランダムにした PNG（RGB）を作る。圧縮しない（level 0）ので、大きさは幅 × 高さ × 3 バイトとほぼ同じで、
// 幅と高さが同じなら毎回同じ大きさになる。性能テストの投稿の写真（N-5）と、タイムラインの写真（perf/seed.sql）に使う
export function noisePng(width: number, height: number): Buffer {
  const rowBytes = width * 3
  const raw = Buffer.alloc((rowBytes + 1) * height)
  const pixels = randomBytes(rowBytes * height)
  for (let y = 0; y < height; y++) {
    // 各行の先頭は、フィルターの種類（0 = なし）
    pixels.copy(raw, y * (rowBytes + 1) + 1, y * rowBytes, (y + 1) * rowBytes)
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  // ビット深度 8、色の種類 2（RGB）、圧縮・フィルター・インターレースは 0
  header.set([8, 2, 0, 0, 0], 8)
  return Buffer.concat([
    SIGNATURE,
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 0 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}
