import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 只检查 Git 中实际待发布的字节；日志仅输出位置与类型，不回显敏感值。
const cwd = fs.realpathSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'));
const git = (...args) => execFileSync('git', args, { cwd, maxBuffer: 32 * 1024 * 1024 });
const staged = process.argv.includes('--staged');
const files = git('ls-files', '-z').toString('utf8').split('\0').filter(Boolean);
if (!files.length) throw new Error('Git 中没有待检查文件；请先执行 git add。');

const knownSecrets = [];
const envFile = path.join(cwd, '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*(API_KEY|HOOK_URL|[A-Z_]*(?:TOKEN|SECRET|PASSWORD))\s*=\s*(.*?)\s*$/);
    if (!match) continue;
    const value = match[2].replace(/^(['"])(.*)\1$/, '$2');
    if (value.length >= 8 && !/^(your[-_]|placeholder|example|sk-xxx)/i.test(value)) knownSecrets.push(value);
  }
}

const blockedPath = /(^|\/)(?:\.env(?:\..+)?|db|browser-data|chrome-profile|cookies?|resumes?|简历|logs?|tmp|node_modules|\.next|out|dist|build|target|\.idea|__pycache__)(\/|$)|\.(?:db(?:-.*)?|sqlite3?(?:-.*)?|log|pem|key|pfx|p12|jks|pyc|tsbuildinfo)$/i;
const rules = [
  ['API 或访问令牌', /\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b/],
  ['私钥', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['本机用户路径', /(?:[A-Z]:[\\/]Users[\\/][^\s"'<>]+|\/(?:Users|home)\/[^\s/"'<>]+)/i],
  ['中国大陆手机号', /(?<![\dA-Za-z])1[3-9]\d{9}(?![\dA-Za-z])/],
  ['中国居民身份证号', /(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)/],
];

const findings = [];
for (const file of files) {
  if (blockedPath.test(file) && !/(^|\/)\.env(?:\.[\w-]+)?\.example$|(^|\/)\.env\.example$/.test(file)) {
    findings.push([file, 0, '敏感文件或运行产物']);
    continue;
  }
  const content = staged ? git('show', ':' + file) : fs.readFileSync(path.join(cwd, file));
  if (content.includes(0)) {
    if (!['gradle/wrapper/gradle-wrapper.jar', 'front/app/favicon.ico'].includes(file)) findings.push([file, 0, '未经核对的二进制文件']);
    continue;
  }
  const text = content.toString('utf8');
  if (knownSecrets.some(secret => text.includes(secret))) findings.push([file, 0, '与本地私密配置相同的凭据']);
  for (const [i, line] of text.split(/\r?\n/).entries()) {
    for (const [type, pattern] of rules) if (pattern.test(line)) findings.push([file, i + 1, type]);
    const emails = line.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) || [];
    for (const email of emails) {
      const syntheticUrl = /(?:^|\/)src\/test\//.test('/' + file) && /https?:\/\//.test(line);
      const syntheticDomain = file.endsWith('/PlatformSessionResetTest.java') && /^evil[.]test@zhipin[.]com$/.test(email);
      if (!syntheticUrl && !syntheticDomain && !/@(?:example\.(?:com|org|net|invalid|test)|[^@]+\.invalid)$/i.test(email)) findings.push([file, i + 1, '邮箱地址']);
    }
  }
}

if (findings.length) {
  for (const [file, line, type] of findings) console.error(`${file}:${line}：${type}`);
  console.error(`检查未通过，共 ${findings.length} 项；请人工核对并修正后再上传。`);
  process.exitCode = 1;
} else {
  console.log(`检查通过：${files.length} 个 Git 文件，未发现规则覆盖的敏感内容。`);
  console.log('自动扫描不能替代人工审核；新截图、日志、简历和自定义凭据仍需逐项检查。');
}
