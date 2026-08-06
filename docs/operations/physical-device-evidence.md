# Physical Device Evidence Kit

## Назначение

Alpha.7 подготавливает воспроизводимую проверку native lifecycle на физическом Android arm64-устройстве. Комплект не заявляет работающий обход и не заменяет ручную проверку в реальной ограниченной сети.

## Что проверяет collector

- подключено ровно одно авторизованное устройство;
- устройство не сообщает `ro.kernel.qemu=1`;
- primary ABI равен `arm64-v8a`;
- Android API не ниже 29;
- native library уже собрана из зафиксированного source tree;
- JNI smoke и controlled failure lifecycle проходят;
- три последовательные TEST-NET evidence-сессии проходят через настоящий Android TUN;
- после каждой сессии native bridge остановлен;
- вторая и третья сессии не превышают bounded FD budget.

Полный Go/tun2socks runtime лениво открывает постоянные process-level descriptors при первой настоящей native session. Поэтому первая из трёх сессий является явным warm-up: после неё публикуется `fd_after`, но `fd_before` и `fd_delta` остаются `UNKNOWN`, чтобы одноразовая инициализация не выдавалась за утечку.

После успешного teardown процесс считается warmed. Перед второй и третьей сессиями снимается новый FD baseline, а после teardown проверяется delta. Бюджет остаётся равным `4` и не увеличивается для маскировки cold-start или повторяемого роста ресурсов.

Collector не записывает serial, model, manufacturer, fingerprint, SSID, hostname, IPv4 или содержимое трафика. Raw Gradle/ADB output не включается в shareable bundle.

## Подготовка

```bash
engine/go/build-android.sh "$ANDROID_SDK_ROOT/ndk/28.0.13004108"
scripts/collect-alpha7-physical-evidence.sh
```

Результат находится в `physical-evidence-alpha7/DEVICE_EVIDENCE.txt`. Перед передачей файл повторно проверяется:

```bash
python3 scripts/validate_device_evidence_bundle.py \
  physical-evidence-alpha7/DEVICE_EVIDENCE.txt
```

## Обязательная ручная restricted-network серия

После readiness gate пользователь вручную запускает в приложении полную `3× BASELINE → 3× STRATEGY → 3× RECOVERY` проверку на том же физическом устройстве и без смены сети. Из UI экспортируется schema v3 report.

Для подтверждения критерия #11 нужны оба файла:

1. `DEVICE_EVIDENCE.txt` с physical arm64 readiness;
2. schema v3 report, где restricted baseline воспроизводится после recovery, а strategy phase имеет устойчивый quorum.

Положительный результат относится только к выбранной цели, устройству и сети. Он не является универсальной гарантией обхода.
