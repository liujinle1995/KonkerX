import struct
import sys

path = sys.argv[1]
d = open(path, "rb").read()
eocd = d.rfind(b"PK\x05\x06")
cd_size, cd_off = struct.unpack("<II", d[eocd + 12:eocd + 20])
assert d[cd_off - 16:cd_off] == b"APK Sig Block 42", "no APK Signing Block"
block_size = struct.unpack("<Q", d[cd_off - 24:cd_off - 16])[0]
start = cd_off - block_size - 8
lead = struct.unpack("<Q", d[start:start + 8])[0]
assert lead == block_size, (lead, block_size)

ids = []
p = start + 8
end = cd_off - 24
while p < end:
    ln, idd = struct.unpack("<QI", d[p:p + 12])
    ids.append((hex(idd), ln))
    p += 8 + ln

print("签名块长度", block_size, "| 配对结束对齐:", p == end)
for idd, ln in ids:
    name = {0x7109871A: "v2", 0xF05368C0: "v3"}.get(int(idd, 16), "?")
    print(f"  id {idd}  len {ln}  -> {name}")
have = {int(i, 16) for i, _ in ids}
print("v2 存在:", 0x7109871A in have, "| v3 存在:", 0xF05368C0 in have)
print("v1 存在:", b"META-INF/KONKER.SF" in d)
