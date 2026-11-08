// Dev helper: encrypt a Halo login password with the public key rendered on /login.
import { readFileSync, writeFileSync } from "node:fs";
import { constants, publicEncrypt } from "node:crypto";

const html = readFileSync(process.argv[2], "utf8");
const match = html.match(/const publicKey = "([^"]+)"/);
if (!match) {
  console.error("public key not found");
  process.exit(1);
}
const key = match[1].replace(/\\\//g, "/");
const pem = `-----BEGIN PUBLIC KEY-----\n${key}\n-----END PUBLIC KEY-----`;
const encrypted = publicEncrypt(
  { key: pem, padding: constants.RSA_PKCS1_PADDING },
  Buffer.from(process.argv[3] ?? "admin123")
).toString("base64");
writeFileSync(process.argv[4] ?? ".tools/halo/enc-password.txt", encrypted, "utf8");
console.log(`encrypted ${encrypted.length} chars`);
