export function truncateUtf8(input: string, maxBytes: number): string {
  if (maxBytes <= 0) return "";
  const totalBytes = Buffer.byteLength(input, "utf8");
  if (totalBytes <= maxBytes) {
    return input;
  }

  let currentBytes = 0;
  let result = "";

  for (const char of input) {
    const charBytes = Buffer.byteLength(char, "utf8");
    if (currentBytes + charBytes > maxBytes) {
      break;
    }
    result += char;
    currentBytes += charBytes;
  }

  return result;
}

export interface RingBufferEntry<T> {
  item: T;
  timestampMillis: number;
}

export class RingBuffer<T> {
  private readonly capacity: number;
  private readonly maxAgeMillis: number;
  private readonly entries: RingBufferEntry<T>[] = [];

  constructor(capacity: number = 1000, maxAgeMillis: number = 15 * 60 * 1000) {
    this.capacity = capacity;
    this.maxAgeMillis = maxAgeMillis;
  }

  add(item: T, timestampMillis: number = Date.now()): void {
    if (this.capacity <= 0) return;
    this.entries.push({ item, timestampMillis });
    if (this.entries.length > this.capacity) {
      this.entries.shift();
    }
  }

  snapshot(nowMillis: number = Date.now()): T[] {
    const minTimestamp = nowMillis - this.maxAgeMillis;
    const result: T[] = [];
    // Newest-first: iterate backwards from most recent entry
    for (let i = this.entries.length - 1; i >= 0; i--) {
      const entry = this.entries[i];
      if (entry && entry.timestampMillis >= minTimestamp) {
        result.push(entry.item);
      }
    }
    return result;
  }

  clear(): void {
    this.entries.length = 0;
  }

  size(): number {
    return this.entries.length;
  }
}

export const backendLogRingBuffer = new RingBuffer<string>(1000, 15 * 60 * 1000);
export const backendDiagnosticRingBuffer = new RingBuffer<any>(100, 15 * 60 * 1000);
