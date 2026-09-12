# Быстрый запуск личного кабинета через Google Sheets

1. Создай Google Таблицу и назови её `Market Forecast PRO X — Users`.
2. Открой **Расширения → Apps Script**.
3. Из архива возьми `server/google_apps_script/Code.gs` и полностью вставь его в редактор Apps Script.
4. В **Project Settings → Script Properties** добавь:
   - `SPREADSHEET_ID` = ID своей таблицы;
   - `APP_SECRET` = случайная секретная строка от 32 символов.
5. **Deploy → New deployment → Web app**.
6. `Execute as: Me`, `Who has access: Anyone`.
7. Скопируй URL, который заканчивается на `/exec`.
8. В APK: **Настройки → Личный кабинет → URL Google Apps Script** вставь этот URL.
9. Зарегистрируй свой аккаунт.
10. В таблице найди свою строку и выставь `role=admin`, `active=TRUE`, `expires_at` оставь пустым.
11. Для обычных пользователей после регистрации меняй `active=TRUE` и при необходимости `expires_at=YYYY-MM-DD`.

Таблица должна оставаться **закрытой**. Пользователям доступ к Google Sheets не выдавай.
