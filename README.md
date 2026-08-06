# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.3.0-alpha.4

Alpha.4 делает внешнюю TLS-проверку повторяемой и исправляет ключевое ограничение alpha.3. Теперь сервис всегда выполняет три baseline, три strategy и три recovery-соединения и способен распознать сценарий, где доступ появляется только при TLS ClientHello split.

### Проверка стратегии

Пользователь выбирает Telegram, YouTube, Discord или вводит собственный публичный hostname. Preset не обходит target policy: URL, IP, local/private адреса и смешанные DNS-ответы блокируются, порт фиксирован на 443.

```text
3 × BASELINE: one write()
3 × STRATEGY: two ordered writes
3 × RECOVERY: one write()

каждое соединение
  → 192.0.2.1:18445
  → TEST-NET-only Android TUN
  → Go/gVisor + tun2socks
  → authenticated local SOCKS5 relay
  → protected pinned public IPv4:443
```

Evaluator требует минимум два успеха и допускает максимум одну ошибку на фазу. Он различает:

- стратегия восстановила недоступный baseline;
- baseline был доступен и стратегия не ухудшила его;
- стратегия не помогла;
- блокировка baseline не воспроизвелась и сеть нестабильна;
- данных недостаточно.

В интерфейсе показываются success/failure counters, median latency, TLS record kind, decision и reason. Обезличенный отчёт не содержит hostname, IPv4, payload, credentials или raw error text.

### Важное ограничение

TUN по-прежнему перехватывает только `192.0.2.0/24`. Обычный трафик приложений не проходит через ConnectX. Два вызова `write()` не гарантируют два TCP-сегмента или IP-пакета. Положительный результат относится только к выбранной цели и текущей сети и не является универсальной гарантией обхода.

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

`v0.3.0-alpha.4` разрабатывается в отдельной feature-ветке и не считается выпущенной до успешного exact-head Android CI. Перед merge обязательны Go lock/bridge tests, JVM tests, lint, APK payload verification и все изолированные Android 35 x86_64 gates.

Release workflow и одноразовый `.publish` marker входят в один проверяемый change set. После merge workflow повторно собирает и проверяет точный `main` commit, а затем создаёт prerelease только при полном успехе.
