# External TLS Evidence Lab

## Назначение

`v0.3.0-alpha.3` добавляет ручную проверку одной публичной TLS-цели в текущей сети. Проверка нужна для получения воспроизводимых технических данных перед тем, как стратегию можно будет тестировать на реально ограниченной сети.

Она не является общим режимом обхода, не включает маршрут `0.0.0.0/0`, не обрабатывает трафик других приложений и не доказывает универсальную эффективность стратегии.

## Поток данных

```text
manual hostname input
  → IDN/STD3 normalization
  → one system DNS lookup
  → public IPv4 policy
  → pinned IPv4 + fixed TCP/443
  → local SSLEngine ClientHello with SNI
  → existing bounded ClientHello inspector
  → baseline / split / recovery
  → exact 192.0.2.1:18445 socket
  → Android TEST-NET-only TUN
  → gVisor/tun2socks
  → authenticated local SOCKS5 relay
  → protected socket to pinned IPv4:443
  → read exactly five TLS record-header bytes
```

Исходящий relay socket обязательно проходит `VpnService.protect()`. Клиентский socket намеренно не защищается: только адрес `192.0.2.1` должен войти в TUN.

## Target policy

Пользователь передаёт только hostname. URL, IP literal, credentials, path, произвольный port и single-label name запрещены. Порт всегда равен `443`.

Hostname нормализуется через `IDN.toASCII(..., USE_STD3_ASCII_RULES)` и переводится в lowercase. Запрещены зарезервированные suffixes, включая `localhost`, `.local`, `.internal`, `.invalid`, `.test`, `.example` и `.home.arpa`.

DNS выполняется один раз до запуска TUN. Все полученные IPv4 проверяются до выбора адреса. Если хотя бы один IPv4 относится к local/private/link-local/CGNAT/benchmark/documentation/multicast/reserved ranges, вся цель отклоняется. Это не позволяет незаметно выбрать публичный адрес из смешанного public/private ответа.

После проверки выбирается один детерминированный IPv4. Relay получает уже готовый numeric address и не выполняет повторный DNS lookup, поэтому DNS rebinding между validation и connect не используется.

## TLS boundary

ClientHello создаётся локальным `SSLEngine` с SNI и протоколами TLS 1.2/1.3. `SSLEngine` не подключается к сети. Полученный массив обязан пройти существующий bounded `TlsClientHelloInspector`; иначе probe не начинается.

В каждой фазе отправляется только ClientHello:

1. `baseline` — один `write()`;
2. `strategy` — два ordered `write()` по плану `tls-clienthello-split-v1`;
3. `recovery` — один `write()`.

Сервис не завершает TLS handshake. Он читает ровно пять байт следующего TLS record header и принимает только bounded `handshake` или `alert` record. TLS payload, certificate, HTTP response и application data не читаются.

## Evaluation

Три результата передаются существующему `StrategyHealthEvaluator`. В отчёте сохраняются только:

- success/failure category;
- latency каждой фазы;
- тип первого TLS record (`HANDSHAKE` или `ALERT`);
- byte/connection counters;
- `KEEP_FOR_LAB_SESSION`, rollback или reject reason;
- состояние session gate.

Hostname и pinned IPv4 показываются пользователю в текущем UI-сеансе, но packet payload, SOCKS credentials, cookies, tokens и response body не попадают в runtime logs.

## Deterministic Android gate

Instrumentation не зависит от внешнего DNS или интернета. В debuggable APK тест передаёт:

- canonical hostname `example.org`;
- policy-valid display IPv4 `93.184.216.34`;
- loopback port локального `LoopbackTlsEvidenceServer`.

Production service принимает test override только при `ApplicationInfo.FLAG_DEBUGGABLE`. Release APK отклоняет эти extras до запуска relay/TUN.

Responder слушает только `127.0.0.1`, принимает один полный bounded ClientHello и проверяет согласованность TLS record length с трёхбайтовой handshake length. Затем он возвращает фиксированный TLS alert record. Он не парсит SNI, не открывает исходящие соединения и не сохраняет payload.

## Ограничения alpha.3

- только IPv4;
- только TCP/443;
- только одна цель за ручной запуск;
- только TEST-NET route на клиентской стороне;
- один ClientHello, без полноценного TLS handshake;
- два `write()` не гарантируют два IP-пакета на проводе;
- результат относится только к конкретной сети и моменту проверки;
- до теста на реально ограниченной сети рабочий DPI bypass не заявляется;
- PR и release запрещены без полного Gradle, lint, APK и Android runtime gate на финальном commit.
