# Сервер отчётов Genomeia

Отправка работает по HTTPS, отдельный FTP-сервер не нужен. Игра отправляет существующий
multipart (`meta` + `bug`), сервер сохраняет исходный `.bug` без распаковки и перекодирования.
Исходники сервера не входят в модули Gradle и сборку игры.

## Адреса

- `GET https://199.189.252.141/api/status` — название, версия, время UTC.
- `POST https://199.189.252.141/upload` — один файл `bug` и строковое JSON-поле `meta`.
  Успех: `201 {"uploaded":true,"id":"…","size":123,"sha256":"…"}`.
- `GET https://199.189.252.141/reports` — HTML-список, по 50 отчётов на страницу.
- `GET /reports/<id>/download` — исходный файл с `Content-Disposition: attachment`.

Список и скачивание защищены HTTP Basic Auth в nginx. Пароль администратора **не входит
в игру и репозиторий**. Приём открытый: 100 МиБ на файл, 64 КиБ метаданных, 5 ГиБ суммарно,
одна обрабатываемая загрузка, 6 запросов в минуту на IP с кратковременным запасом 3 запроса.
При заполнении новые отчёты получают `507`, старые автоматически не удаляются.

## Размещение и обслуживание

- `/opt/genomeia-bugs/` — `app.py`, `templates/`, тесты, `requirements.txt`.
- `/opt/genomeia-bugs-venv/` — Flask + Gunicorn; версии в `requirements.txt`.
- `/var/lib/genomeia-bugs/` — файлы `<id>.bug` и индекс `reports.sqlite3`. Каталог создаёт
  systemd (`DynamicUser`, `StateDirectory`); root видит также `/var/lib/private/genomeia-bugs/`.
- `/etc/nginx/sites-available/genomeia` — конфигурация из `nginx.conf`.
- `/etc/nginx/genomeia.htpasswd` — хэш пароля, владелец `root:www-data`, права `640`.
- `/root/genomeia-reports-access.json` — выданные данные входа, права `600`.
- `/opt/genomeia-certbot/` — Certbot 5.8.0, отдельное окружение Python.
- `/etc/letsencrypt/` — IP-сертификат и настройки продления.

Gunicorn слушает **только `127.0.0.1:8000`**. Нельзя открывать 8000 наружу: авторизацию
списка обеспечивает nginx. Один процесс и четыре потока — условие работы блокировки
загрузок/квоты; несколько процессов потребуют межпроцессного резервирования места.

Для обновления скопировать исходники в `/opt/genomeia-bugs/`, затем:

```sh
cd /opt/genomeia-bugs
/opt/genomeia-bugs-venv/bin/pip install -r requirements.txt
/opt/genomeia-bugs-venv/bin/python -m unittest -v
systemctl restart genomeia-bugs
systemctl status genomeia-bugs nginx
journalctl -u genomeia-bugs -n 50 --no-pager
```

После изменения unit-файлов — `systemctl daemon-reload`. Для nginx —
`nginx -t && systemctl reload nginx`. Смена пароля: `htpasswd /etc/nginx/genomeia.htpasswd genomeia`
(интерактивно); затем обновить либо удалить вспомогательный `/root/genomeia-reports-access.json`.
Для ручного доступа к файлам уже есть SFTP по SSH:22; FTP и пароль root в игре не нужны.

IP-сертификат Let's Encrypt действует около шести дней. `genomeia-certbot.timer` запускает
проверку дважды в день и при продлении перезагружает конфигурацию nginx. Порт 80 и путь
`/.well-known/acme-challenge/` нужны для продления; HTTP-запросы к остальным ручкам
перенаправляются на HTTPS. [Документация](https://letsencrypt.org/2026/03/11/shorter-certs-certbot).

```sh
systemctl list-timers genomeia-certbot.timer
/opt/genomeia-certbot/bin/certbot renew --dry-run --no-random-sleep-on-renew --run-deploy-hooks --deploy-hook 'systemctl reload nginx'
```

На новом сервере установить `nginx`, `python3-venv`, `apache2-utils`; создать оба окружения
Python, скопировать исходники и unit-файлы. Сначала нужен временный HTTP server nginx с
`server_name <IP>` и `location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }`.
Создать webroot и получить сертификат Certbot 5.4+:

```sh
/opt/genomeia-certbot/bin/certbot certonly --preferred-profile shortlived --webroot --webroot-path /var/www/letsencrypt --ip-address 199.189.252.141
```

Создать пароль: `htpasswd -c /etc/nginx/genomeia.htpasswd genomeia` (только для нового файла),
назначить `root:www-data`, `640`. Установить итоговый `nginx.conf` и unit-файлы; выполнить
`nginx -t`, `systemctl daemon-reload`,
`systemctl enable --now genomeia-bugs nginx genomeia-certbot.timer`.
При смене IP обновляются URL в `BugUploader`, nginx и команда выдачи сертификата.

## Проверка из проекта и стоимость

```powershell
./gradlew.bat -q :lwjgl3:bugUploadCheck '-PbugFile=bugs/имя-отчёта.bug'
```

Эта ручная проверка без окна **отправляет файл на настоящий сервер** тем же `BugUploader`,
что кнопка игры. Печатает время и SHA-256. Автоматические тесты внешнюю сеть не используют.
Исходный файл остаётся у игрока и при успехе, и при ошибке.

Тело запроса занимает один размер отчёта; прежняя сборка дополнительно копировала его
через `ByteArrayOutputStream.toByteArray()`. Сетевой слой libGDX на ПК сам буферизует тело
ещё раз. Это не постоянное потребление памяти: большие отчёты на телефоне нужно замерить.
В симуляции ничего не изменено. На сервере большие multipart-файлы временно кладутся
на диск; копирование и хэширование идут с буфером 64 КиБ, `MemoryMax=256M` ограничивает сервис.

Это тестовый приёмник: резервных копий, уведомлений о заполнении, защиты от распределённого
спама пока нет. Авария между переименованием файла и записью SQLite может оставить
неиндексированный `.bug` или `.part`; автоматическая очистка и учёт таких остатков в квоте
пока не реализованы. Для согласованной резервной копии остановить `genomeia-bugs` и сохранить
каталог данных целиком.

## Проверено 20.10.2026

- Быстрая проверка из `AGENTS.md` на JBR 21.0.11: компиляция core/render/renderLab/lwjgl3,
  175 тестов core и 44 теста render; в `BugUploaderTest` — 4 теста. Дополнительно прошёл
  `:android:processDebugMainManifest` с разрешением доступа в интернет.
- На сервере `python -m unittest -v`: 9 тестов (файл >2 МиБ, ограничения, квота, имена,
  HTML-экранирование, одинаковые имена, скачивание без изменения байтов).
- `bugUploadCheck`: существующий отчёт 1 046 460 байт принят за 1376 мс; скачан за 2562 мс.
  Байты совпали полностью, SHA-256 `17c8fe4e1d99c8db7ef3af82ff771c5291233122df959a4aaee7758cade5840e`.
- Снаружи: status `200`, список/скачивание без пароля `401`, запрос >101 МиБ `413`,
  HTTP перенаправляет на HTTPS с доверенным IP-сертификатом.
- Пробное продление сертификата с перезагрузкой конфигурации nginx: успешно (`certbot renew --dry-run`).
- Basic Auth проверен HTTP-запросами. Встроенный браузер Codex не показал окно входа
  (`ERR_INVALID_AUTH_CREDENTIALS`); открывать страницу в обычном Chrome/Edge.
- Не проверены: отправка на Android/iOS/TeaVM, кнопка в окне игры, визуальный вид HTML,
  нагрузка, перезагрузка всего VDS, аварийное отключение питания. `bugReplayCheck` не запускался:
  симуляция, команды, журнал и формат отчёта не менялись.
