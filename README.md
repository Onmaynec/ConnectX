# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.3.0-alpha.2

Alpha.2 добавляет измеримый A/B/A health gate вокруг первой lab-only TLS write-split стратегии. Обычный пользовательский трафик по-прежнему не обрабатывается стратегией, а локальный результат не считается доказательством обхода DPI в реальной сети.

### Strategy API

Pure Kotlin модуль `:strategy:api` содержит:

- типизированный `BypassStrategy` API;
- capability model для TCP, UDP, IPv4, IPv6, TLS, QUIC и root;
- immutable registry стратегий с уникальными идентификаторами;
- глобальный feature gate, выключенный по умолчанию;
- отдельные `LAB_ONLY` и `USER_TRAFFIC` scopes;
- строгие причины отказа вместо скрытых fallback-заглушек;
- bounded `StrategyHealthEvaluator` и immutable session gate.

Первая стратегия `tls-clienthello-split-v1` принимает только ограниченный синтетический TLS ClientHello. Bounded inspector проверяет TLS record header, тип ClientHello, версии и длины record/handshake, максимальный размер и отсутствие trailing data. План обязан восстанавливать исходный payload байт-в-байт.

### A/B/A Strategy Evaluation Lab

После отдельного действия пользователя приложение выполняет три последовательные проверки одного синтетического ClientHello:

```text
A — BASELINE: one write()
B — STRATEGY: two ordered writes from tls-clienthello-split-v1
A — RECOVERY: one write()

Каждая фаза
  → отдельное TCP-соединение
  → exact endpoint 192.0.2.1:18444
  → TEST-NET TUN
  → Go/gVisor + tun2socks
  → authenticated local SOCKS5 relay
  → protected loopback echo endpoint
```

Для каждой завершённой фазы проверяются:

- полное байтовое совпадение echo с исходным ClientHello;
- отдельное relay-соединение;
- ожидаемые upload/download byte deltas;
- latency или типизированная причина ошибки.

Evaluator принимает решение только после baseline, strategy и recovery:

- `KEEP_FOR_LAB_SESSION` — локальная проверка прошла в пределах latency budget;
- `ROLLBACK_CONFIRMED` — baseline и recovery здоровы, а strategy сломалась или слишком замедлилась;
- `REJECT_BASELINE_UNHEALTHY` — путь был нездоров ещё до strategy;
- `REJECT_ENVIRONMENT_UNSTABLE` — recovery не восстановил здоровый путь;
- `INCONCLUSIVE` — данных недостаточно.

Фиксированная alpha.2 policy: одна обязательная успешная проба на фазу, ноль допустимых ошибок, максимум 50% относительной или 100 мс абсолютной регрессии и 60 секунд cooldown после rollback/reject/interruption.

**Два вызова `write()` не гарантируют два TCP-сегмента или два IP-пакета на проводе.** Ядро ОС и userspace stack могут объединить данные. Alpha.2 проверяет planner, TUN integration, byte integrity, health comparison и rollback lifecycle, но не заявляет доказанную сетевую сегментацию или рабочий обход DPI.

### Lifecycle hardening

- stop/revoke/error закрывают client socket, native stack, TUN, relay и endpoint в generation-safe порядке;
- поздний или повторный `ACTION_STOP` после успешного teardown идемпотентен;
- завершённый `LAB_APPROVED` результат не превращается в искусственный cooldown из-за stale stop command;
- cooldown применяется только при активной evaluation или незавершённом teardown;
- Android regression gate проверяет late stop, немедленный explicit restart и active stop.

### Важное ограничение

TUN перехватывает только зарезервированную сеть `192.0.2.0/24`. A/B/A override разрешает только точную пару `192.0.2.1:18444` и перенаправляет её на process-local endpoint `127.0.0.1`.

`0.0.0.0/0` и `::/0` не добавляются. Системные DNS-запросы и обычный интернет-трафик телефона не направляются в ConnectX. Внешний DNS resolver, IPv6, QUIC и DPI-модификация реальных соединений не включены.

## Модули

```text
:app                 Compose UI, permission flow и Android instrumentation gates
:core:model          состояния, diagnostics и reducer
:core:designsystem   Material 3 theme
:strategy:api        strategy contracts, planner, evaluator и rollback gate
:vpn:api             межмодульный контракт tunnel lifecycle
:vpn:relay           authenticated SOCKS5 relay, bounded TCP/UDP/DNS endpoints
:vpn:nativebridge    JNI API и Android native payload
:vpn:service         VpnService, TUN, native/probe/strategy lifecycle
engine/go            source-built tun2socks/gVisor bridge
```

## Сборка

Требования:

- JDK 17;
- Go 1.26.3;
- Android SDK 36;
- Android NDK `28.0.13004108`;
- Gradle 8.13.

```bash
gradle --no-daemon test
gradle --no-daemon lintDebug
gradle --no-daemon :app:assembleDebug
```

Native bridge собирается скриптом:

```bash
engine/go/build-android.sh "$ANDROID_SDK_ROOT/ndk/28.0.13004108"
```

APK будет находиться в:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions запускает Android 35 x86_64 emulator и выполняет изолированные gates:

1. strategy foundation внутри установленного APK;
2. TLS write-split Lab через foreground `VpnService` и TEST-NET TUN;
3. A/B/A strategy evaluation, late stop, restart и active stop;
4. JNI lifecycle;
5. TCP path;
6. UDP path;
7. DNS path.

Между сетевыми gates процесс приложения и VPN app-op сбрасываются, а каждый набор instrumentation-результатов сохраняется отдельно.

## Roadmap

Полный план версий находится в [`ROADMAP.md`](ROADMAP.md). Технические исследования, ADR и release notes находятся в каталоге [`docs/`](docs/).

## Безопасность и приватность

- нет регистрации и аккаунтов;
- нет рекламы и собственной аналитики;
- нет удалённого сервера ConnectX;
- нет внешнего DNS resolver в diagnostic probe;
- нет перехвата системных DNS-запросов;
- нет расшифровки HTTPS;
- нет установки сертификатов;
- нет записи содержимого пакетов, TLS payload или DNS query names;
- SOCKS credentials создаются временно и не выводятся в diagnostics;
- strategy feature gate выключен по умолчанию;
- lab strategy не разрешена для user-traffic scope;
- evaluator получает только bounded latency/failure samples;
- root не является обязательным.

## Статус релиза

PR `v0.3.0-alpha.2` остаётся stacked draft поверх `v0.3.0-alpha.1`. Слияние и публикация запрещены до успешной проверки Go lock, JVM tests, lint, APK payload и всех изолированных Android gates на точном commit.

Implementation merge только регистрирует guarded release workflow в `main`. Отдельный минимальный post-merge PR добавит `.publish` marker и активирует публикацию после успешного Android CI push run на том же release SHA.
