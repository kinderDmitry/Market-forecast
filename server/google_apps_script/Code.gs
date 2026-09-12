/**
 * Market Forecast PRO X — Google Sheets account backend.
 *
 * The spreadsheet stays PRIVATE. Only this Apps Script deployment can read/write it.
 * Deploy as a Web app: Execute as Me, Who has access: Anyone.
 * Required Script Properties:
 *   SPREADSHEET_ID = ID of the Google Sheet
 *   APP_SECRET     = long random secret (at least 32 characters)
 */

const SHEET_NAME = 'Users';
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;

function doGet() {
  return output({ ok: true, service: 'Market Forecast PRO X', status: 'online' });
}

function doPost(e) {
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const action = String(body.action || '').trim().toLowerCase();
    if (action === 'register') return register(body);
    if (action === 'login') return login(body);
    if (action === 'me') return me(body);
    if (action === 'logout') return output({ ok: true });
    return output({ ok: false, error: 'Неизвестное действие' });
  } catch (err) {
    return output({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function register(b) {
  const email = normalizeEmail(b.email);
  const name = cleanName(b.name, email);
  const password = String(b.password || '');
  if (!isEmail(email)) return output({ ok: false, error: 'Введите корректный email' });
  if (password.length < 8) return output({ ok: false, error: 'Пароль должен содержать минимум 8 символов' });

  const sheet = usersSheet();
  const rows = readRows(sheet);
  if (rows.some(r => normalizeEmail(r.email) === email)) {
    return output({ ok: false, error: 'Аккаунт с таким email уже существует' });
  }

  const id = 'MFP-' + Utilities.getUuid().replace(/-/g, '').slice(0, 12).toUpperCase();
  const now = new Date();
  sheet.appendRow([id, email, name, hashPassword(password), 'user', 'FALSE', '', now.toISOString()]);

  return output({
    ok: true,
    pending: true,
    message: 'Регистрация выполнена. Дождитесь, пока администратор разрешит доступ.'
  });
}

function login(b) {
  const email = normalizeEmail(b.email);
  const password = String(b.password || '');
  const rows = readRows(usersSheet());
  const user = rows.find(r => normalizeEmail(r.email) === email);
  if (!user || user.password_hash !== hashPassword(password)) {
    return output({ ok: false, error: 'Неверный email или пароль' });
  }
  if (!isEnabled(user)) {
    return output({ ok: false, error: 'Доступ к приложению пока не разрешён администратором' });
  }
  if (isExpired(user.expires_at)) {
    return output({ ok: false, error: 'Срок доступа истёк' });
  }

  const expires = Date.now() + TOKEN_TTL_MS;
  const token = makeToken(user.id, expires);
  return output({ ok: true, token: token, user: publicUser(user) });
}

function me(b) {
  const data = verifyToken(String(b.token || ''));
  if (!data) return output({ ok: false, error: 'Сессия недействительна' });
  const rows = readRows(usersSheet());
  const user = rows.find(r => r.id === data.id);
  if (!user) return output({ ok: false, error: 'Пользователь не найден' });
  if (!isEnabled(user)) return output({ ok: false, error: 'Доступ закрыт администратором' });
  if (isExpired(user.expires_at)) return output({ ok: false, error: 'Срок доступа истёк' });
  return output({ ok: true, user: publicUser(user) });
}

function usersSheet() {
  const props = PropertiesService.getScriptProperties();
  const spreadsheetId = String(props.getProperty('SPREADSHEET_ID') || '').trim();
  if (!spreadsheetId) throw new Error('Не задан Script Property SPREADSHEET_ID');
  const ss = SpreadsheetApp.openById(spreadsheetId);
  let sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_NAME);
    sheet.getRange(1, 1, 1, 8).setValues([[
      'id', 'email', 'name', 'password_hash', 'role', 'active', 'expires_at', 'created_at'
    ]]);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function readRows(sheet) {
  const values = sheet.getDataRange().getValues();
  if (values.length <= 1) return [];
  return values.slice(1).map(r => ({
    id: String(r[0] || '').trim(),
    email: String(r[1] || '').trim(),
    name: String(r[2] || '').trim(),
    password_hash: String(r[3] || '').trim(),
    role: String(r[4] || 'user').trim().toLowerCase(),
    active: String(r[5] || '').trim().toUpperCase(),
    expires_at: String(r[6] || '').trim(),
    created_at: String(r[7] || '').trim()
  }));
}

function publicUser(u) {
  return {
    id: u.id,
    email: u.email,
    name: u.name,
    role: u.role === 'admin' ? 'admin' : 'user',
    enabled: isEnabled(u),
    expires_at: u.expires_at || ''
  };
}

function isEnabled(u) {
  return u.active === 'TRUE' || u.active === '1' || u.active === 'YES';
}

function isExpired(value) {
  const s = String(value || '').trim();
  if (!s) return false;
  const d = new Date(s.length === 10 ? s + 'T23:59:59' : s);
  return isNaN(d.getTime()) || d.getTime() < Date.now();
}

function normalizeEmail(v) { return String(v || '').trim().toLowerCase(); }
function cleanName(v, fallback) { return String(v || '').trim().slice(0, 80) || fallback; }
function isEmail(v) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v); }

function hashPassword(password) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(password), Utilities.Charset.UTF_8);
  return bytes.map(b => {
    const n = b < 0 ? b + 256 : b;
    return ('0' + n.toString(16)).slice(-2);
  }).join('');
}

function base64url(bytes) {
  return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/, '');
}

function textBytes(s) { return Utilities.newBlob(String(s)).getBytes(); }

function makeToken(id, expires) {
  const payload = base64url(textBytes(JSON.stringify({ id: id, exp: expires })));
  const secret = PropertiesService.getScriptProperties().getProperty('APP_SECRET');
  if (!secret) throw new Error('Не задан Script Property APP_SECRET');
  const sig = base64url(Utilities.computeHmacSha256Signature(payload, secret));
  return payload + '.' + sig;
}

function verifyToken(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 2) return null;
  const secret = PropertiesService.getScriptProperties().getProperty('APP_SECRET');
  if (!secret) return null;
  const expected = base64url(Utilities.computeHmacSha256Signature(parts[0], secret));
  if (expected !== parts[1]) return null;
  try {
    const json = Utilities.newBlob(Utilities.base64DecodeWebSafe(parts[0])).getDataAsString();
    const data = JSON.parse(json);
    if (!data.id || Number(data.exp) < Date.now()) return null;
    return data;
  } catch (_) { return null; }
}

function output(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
