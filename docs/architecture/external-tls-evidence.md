# Repeated External TLS Strategy Evidence

## Назначение

`v0.3.0-alpha.4` превращает одиночную alpha.3-проверку в повторяемую A/B/A-классификацию текущей сети. Пользователь выбирает Telegram, YouTube, Discord или вводит собственный публичный hostname.

Это по-прежнему не глобальный режим обхода: обычный трафик приложений не входит в TUN, маршрут `0.0.0.0/0` не устанавливается, а результат относится только к выбранной цели, сети и моменту запуска.

## Поток данных

```text
manual preset or hostname
  → IDN/STD3 normalization
  → one system DNS lookup
  → reject mixed/non-public IPv4 answers
  → pin one public IPv4 + TCP/443
  → local SSLEngine ClientHello with SNI
  → exact 192.0.2.1:18445 socket
  → Android TEST-NET-only TUN
  → gVisor/tun2socks
  → authenticated local SOCKS5 relay
  → protected socket to pinned IPv4:443
  → read exactly five TLS record-header bytes
```

## Повторная A/B/A-модель

Каждая фаза выполняет три независимых TCP-соединения:

1. `BASELINE` — цельный ClientHello одним `write()`;
2. `STRATEGY` — тот же массив двумя ordered `write()` по плану `tls-clienthello-split-v1`;
3. `RECOVERY` — повтор исходного baseline.

Policy требует минимум два успешных результата и допускает максимум одну ошибку на фазу. В отчёт попадают количество успехов/ошибок, median latency и согласованный тип TLS record.

Alpha.4 всегда выполняет все три фазы. Alpha.3 отменяла strategy и recovery после первой baseline-ошибки, поэтому не могла подтвердить сценарий, ради которого существует стратегия.

## Решения evaluator

При здоровом baseline сохраняется прежняя логика latency budget и rollback. Дополнительный restricted-baseline режим классифицирует:

- `baseline fail → strategy success → recovery fail` как `STRATEGY_RESTORED_RESTRICTED_BASELINE`;
- `baseline fail → strategy success → recovery success` как нестабильную среду (`RESTRICTED_BASELINE_NOT_REPRODUCED`);
- `baseline fail → strategy fail` как `STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE`.

Однократный сбой не считается доказательством: решение строится по кворуму повторных выборок.

## Target policy

Preset — только удобное заполнение hostname. Все цели проходят одну и ту же строгую проверку:

- URL, IP literal, credentials, path, custom port и single-label name запрещены;
- порт фиксирован на `443`;
- hostname нормализуется через IDN/STD3;
- private, loopback, link-local, CGNAT, benchmark, documentation, multicast и reserved IPv4 блокируются;
- смешанный public/private DNS-ответ отклоняется целиком;
- relay использует уже выбранный numeric IPv4 и не выполняет повторный DNS lookup.

## Privacy boundary

Сервис не завершает TLS handshake и не читает payload ответа. Он не отправляет HTTP-запросы, логины, cookies или токены. Нет MITM, custom CA и расшифровки HTTPS. Runtime-логи не содержат ClientHello и SOCKS credentials.

Обезличенный отчёт содержит preset id, счётчики выборок, median latency, record kind, decision/reason и gate state. Hostname и IPv4 заменяются на `REDACTED`.

## Android gate

Debug instrumentation использует process-local TLS-like responder. Одна alpha.4-сессия создаёт девять реальных TUN/relay TCP flows; две последовательные сессии — восемнадцать. Gate проверяет результат, counters, native teardown и возможность нового explicit start после `LAB_APPROVED`.

## Ограничения alpha.4

- только IPv4 и TCP/443;
- только одна цель за запуск;
- только TEST-NET route на клиентской стороне;
- ClientHello-only, без полноценного TLS handshake;
- два `write()` не доказывают два IP-пакета на проводе;
- ordinary app traffic не проходит через стратегию;
- positive result не является универсальной гарантией обхода;
- физическое устройство и реально ограниченная сеть остаются обязательным внешним validation gate.
