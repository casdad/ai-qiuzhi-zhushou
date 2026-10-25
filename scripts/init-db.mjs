import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destination = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(root, 'db', 'getjobs.db');

if (fs.existsSync(destination)) {
  console.error('目标数据库已经存在，已停止；请保留已有配置和投递记录。');
  process.exit(1);
}

fs.mkdirSync(path.dirname(destination), { recursive: true });
// 排他创建，防止并发初始化覆盖文件。
fs.closeSync(fs.openSync(destination, 'wx'));
const db = new DatabaseSync(destination);
try {
  db.exec('BEGIN');
  db.exec(fs.readFileSync(path.join(root, 'database', 'schema.sql'), 'utf8'));
  db.exec(fs.readFileSync(path.join(root, 'database', 'options.sql'), 'utf8'));
  db.exec('COMMIT');
  console.log('空白数据库已初始化：' + destination);
  console.log('仅包含表结构与公共筛选字典；请在网页中自行填写个人求职配置。');
} catch (error) {
  db.exec('ROLLBACK');
  console.error('初始化失败：' + error.message);
  process.exitCode = 1;
} finally {
  db.close();
}
