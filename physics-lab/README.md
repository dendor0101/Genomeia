# Лаборатория физики

Окно, файлы, повтор записей, диагностика, проверки и замеры новой физики.
Зависит от `:physics`, `:render` и настольного libGDX. От `:core` и `:lwjgl3`
не зависит. Непосредственное рисование примитивов остаётся в `:render`.

Пакеты внутри `io.github.some_example_name.physics.lab`:

| Пакет | Что искать |
|---|---|
| `session` | Состояние сессии, настройки запуска, применение команд журнала |
| `window` | `RealBodyDemo`, окно, ввод, интерполяция и подготовка рисунка |
| `io` | Чтение body-export и формат `PlayerLog` |
| `scenes` | Сценарии столкновений и генератор мягкой решётки |
| `inspection` | `Probe`, именованный доступ для диагностики, отчёты |
| `replay` | Повтор журнала игрока и старого drag-log |
| `checks` | Физические регрессии, проверка записи, скрытого состояния и окна |
| `bench` | Время тика, масштаб 10k/100k, раскладка памяти и численная точность |
| `tuning` | Подбор мышц и сохранённое независимое зеркало `SwimSolver` |

## Запуск

Окно запускается платформенными обёртками в `lwjgl3/src/lab/kotlin`:

```text
gradlew --no-daemon :lwjgl3:realBodyDemo -Pbody=body-export.txt
gradlew --no-daemon :lwjgl3:realBodyDemo -Pplay=<запись> -Pspeed=4
gradlew --no-daemon :lwjgl3:softGridLab -Pseed=12345
```

У `lwjgl3` это отдельный source set `lab`: лаборатория не входит в classpath
или JAR основной игры. Проверки основной игры находятся в `lwjgl3.verification`.

Безоконные инструменты запускаются прямо из этого модуля:

```text
gradlew :physics-lab:test
gradlew :physics-lab:physicsCheck -Pbody=body-export.txt
gradlew :physics-lab:playerLogSelfTest -Pbody=body-export.txt
gradlew :physics-lab:playerReplay -Plog=<запись>
gradlew :physics-lab:scaleBench -Pbody=<тело> -PscaleScene=all
gradlew :physics-lab:swimTune --args="--pop 64 --gen 60 --seed 7"
```

Старые задачи `:lwjgl3:physicsCheck`, `:lwjgl3:playerReplay` и остальные безоконные
команды оставлены как перенаправления; свойства `-P...` работают как прежде.
Аргументы `--args` задаются у настоящей JavaExec-задачи в `:physics-lab`.

## Сравнение версий

`refactorCheck` проверяет исходные хэши журнала и дополнительный неизменяемый снимок
скрытого состояния. `check` никогда не записывает эталон заново.

```text
gradlew :physics-lab:refactorCheck -Plog=<запись> -Pcheckpoint=<эталон> -PperfTicks=1200
gradlew :physics-lab:scaleParityCheck -Pbaseline=<classpath.txt> -Pbody=<тело> -Pcopies=108
```

Для `scaleParityCheck` предпочтителен файл с полным сохранённым classpath прежней
версии, включая отдельные копии JAR ядра и рендера. Пути разделены системным
разделителем classpath (`;` на Windows). Два независимых загрузчика в одном JVM
чередуют старый и новый тик, сверяют биты позиций/скоростей после каждого тика и
измеряют wall/CPU time. Каталог старых классов поддерживается для прежних монолитных
эталонов; при переносах между модулями используйте полный снимок classpath.
Повторите замер в отдельном JVM с `-PscaleOrder=new-first` (по умолчанию `old-first`):
первым загружается и прогревается другой вариант. Одного чередования тиков мало,
чтобы исключить влияние порядка на JIT и расположение массивов. Сохраняйте оба результата.

Одинаковые имена сцен и число частиц ещё не гарантируют одинаковую нагрузку:
сравнивайте тело, команды, настройки и число проходов. Простой перенос файлов не
меняет алгоритм и не должен считаться оптимизацией решателя.
