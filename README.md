# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.3.0-alpha.5

Alpha.5 сохраняет повторяемую TLS A/B/A-проверку alpha.4 и переводит выпуск prerelease на единый exact-commit pipeline. Версия приложения, native bridge, README, changelog и release manifest теперь проверяются одной автоматической guard-процедурой.

### Проверка TLS-стратегии

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
- ограничение baseline не воспроизвелось;
- данных недостаточно.

В интерфейсе показываются success/failure counters, median latency, TLS record kind, decision и reason. Обезличенный отчёт не содержит hostname, IPv4, payload, credentials или raw error text.

### Единый guarded prerelease

Файл [`release/prerelease.json`](release/prerelease.json) является единственным release manifest. После успешного `Android CI` push-run в `main` общий workflow:

1. проверяет точное совпадение commit SHA, workflow, event и branch;
2. подтверждает согласованность manifest, `versionCode`, `versionName`, native version, README и changelog;
3. скачивает APK и native artifacts только из этого exact CI run;
4. проверяет обе ABI, locked toolchain metadata и legal assets внутри APK;
5. формирует deterministic native ZIP, `PROVENANCE.json` и `CHECKSUMS.txt`;
6. создаёт prerelease либо подтверждает byte-identical повторный запуск.

PR-run, failed CI, другая ветка или другой SHA не могут публиковать release. Ручной запуск по умолчанию работает как preflight без создания тега. Подробности: [`docs/operations/guarded-prerelease.md`](docs/operations/guarded-prerelease.md).

### Важное ограничение

TUN перехватывает только `192.0.2.0/24`. Обычный трафик приложений не проходит через ConnectX. Два вызова `write()` не гарантируют два TCP-сегмента или IP-пакета. Положительный результат относится только к выбранной цели и текущей сети и не является универсальной гарантией обхода.

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

## Сборка и проверка

Требования:

- JDK 17;
- Go 1.26.3;
- Android SDK 36;
- Android NDK `28.0.13004108`;
- Gradle 8.13;
- Python 3 для release guard tests.

```bash
python3 -m unittest discover -s scripts/tests -p 'test_release_guard.py'
python3 scripts/release_guard.py validate-repo
gradle --no-daemon test
gradle --no-daemon lintDebug
gradle --no-daemon :app:assembleDebug
```

Полная локальная проверка alpha.5:

```bash
scripts/verify-alpha5-local.sh --clean
```

Для Android runtime gates нужен уже запущенный Android 35 x86_64 emulator:

```bash
scripts/verify-alpha5-local.sh --clean --device
```

Native bridge собирается скриптом:

```bash
engine/go/build-android.sh "$ANDROID_SDK_ROOT/ndk/28.0.13004108"
```

GitHub Actions выполняет изолированные gates:

1. strategy foundation внутри установленного APK;
2. TLS write-split Lab через foreground `VpnService` и TEST-NET TUN;
3. A/B/A strategy evaluation;
4. repeated external TLS evidence;
5. JNI lifecycle;
6. TCP path;
7. UDP path;
8. DNS path.

Между сетевыми gates процесс приложения и VPN app-op сбрасываются, а instrumentation-результаты сохраняются отдельно.

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

`v0.3.0-alpha.5` публикуется только после полного Android CI на exact `main` commit. Отдельные version-specific publisher workflows больше не используются: release activation, provenance verification и идемпотентная публикация выполняются общим guarded workflow.
