# Guarded prerelease workflow

## Единственный источник метаданных

`release/prerelease.json` содержит версию, versionCode, tag, title, release notes и ожидаемую CI provenance. Manifest имеет строгую схему: неизвестные поля, небезопасные пути и несовпадающие tag/version отклоняются.

## Штатный выпуск

1. Изменения проходят PR Android CI.
2. PR объединяется в `main`.
3. `Android CI` повторно собирает exact merge commit как событие `push`.
4. После успешного завершения `workflow_run` запускает `Publish guarded prerelease`.
5. Publisher продолжает работу только если exact commit изменил `release/prerelease.json`.
6. APK и native artifacts скачиваются из triggering run по exact SHA.
7. Проверяются manifest, repository metadata, обе ABI, toolchain metadata и legal APK payload.
8. Формируются deterministic assets, `PROVENANCE.json` и `CHECKSUMS.txt`.
9. Tag создаётся на exact commit. Повторный запуск сравнивает существующие assets байт-в-байт.

## Ручной preflight

`workflow_dispatch` без флага `publish` выбирает указанный SHA либо текущий `main`, находит successful Android CI push-run и выполняет все проверки без создания release.

Публикация вручную разрешена только с `publish=true`. Указанный SHA обязан иметь successful Android CI push-run в `main`.

## Отказ и recovery

- PR run, failed/cancelled CI, другая ветка или другой SHA отклоняются до скачивания artifacts.
- Если manifest не изменялся в triggering commit, workflow завершается без публикации.
- Если tag уже указывает на другой commit, автоматический run ничего не изменяет; manual publish завершается ошибкой.
- Если существующий asset отличается, workflow завершается ошибкой и не использует `--clobber`.
- Недостающий asset для release на том же exact commit может быть догружен; существующие assets предварительно сравниваются.
- После transient failure используйте manual preflight для того же SHA, затем manual publish.

## Локальная проверка

```bash
python3 -m unittest discover -s scripts/tests -p 'test_release_guard.py'
python3 scripts/release_guard.py validate-repo
scripts/verify-alpha5-local.sh --clean
```
