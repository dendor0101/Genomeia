# Поддержка macOS (Apple Silicon) — что было сломано и как починено

Этот документ описывает, почему игра не запускалась на macOS и какие изменения добавляют
полноценную поддержку macOS (запуск, меню, редактор генома, симуляция) **без** изменений
для Windows / Linux / Android.

> TL;DR: рендерер был написан под OpenGL 4.3 (SSBO) + GLSL ES 3.20. macOS аппаратно режет
> OpenGL до **4.1** (нет SSBO, нет `#version … es`). Добавлен отдельный «маковский» путь
> рендера на VBO+инстансинге (порт Android-рендерера) + core-совместимые шейдеры для UI.
> Win/Linux остаются на SSBO, Android — на своём GLES-рендерере.

---

## 1. Корень проблемы

Десктоп-бэкенд — LWJGL3. Игра падала на macOS по двум независимым причинам:

### 1.1 Краш на старте (ещё в меню)
В `Lwjgl3Launcher` стояло `setOpenGLEmulation(GL32, 3, 2)` для **всех** платформ. GL32-эмуляция
запрашивает **core-profile** контекст. macOS в core-profile строго режет встроенный дефолтный
шейдер libGDX (`SpriteBatch`/`Scene2D` — GLES2-стиль: `attribute`/`varying`, без `#version`):

```
Error compiling shader: Vertex shader
ERROR: '' : #version required and missing.
ERROR: 'attribute' : syntax error
  at SpriteBatch.createDefaultShader → Stage.<init> → MenuScreen.<init>
```

На Windows/Linux драйверы (NVIDIA/AMD/Intel) это прощают, поэтому там работало. macOS — нет.

### 1.2 Краш в редакторе/симуляции
Собственные GPU-рендереры игры (`ShaderManagerLibgdxApi`, `PheromoneShaderManagerLibgdx`) и
их шейдеры (`*_pc.vert/frag`) написаны под **GLSL ES 3.20 (`#version 320 es`) + OpenGL 4.3 SSBO**
(`GL_SHADER_STORAGE_BUFFER`). macOS держит OpenGL максимум на **4.1** — она не компилирует
`#version 320 es` и не поддерживает SSBO вообще. Поэтому вход в редактор/симуляцию падал.

У Android-сборки уже был не-SSBO рендерер (`ShaderManagerAndroidApi`,
`PheromoneShaderManagerAndroid`) на VBO + инстансинге, но он завязан на `android.opengl.GLES32`
и ES-шейдеры — на десктоп-маке напрямую не встанет.

### Как ведёт себя libGDX на macOS (проверено эмпирически)
- `GLEmulation.GL20` → macOS даёт **legacy 2.1** контекст. Дефолтные шейдеры UI работают,
  но **нет** текстурных массивов / инстансинга / FBO — рендерер не запустить.
- `GLEmulation.GL30` → macOS даёт **4.1 core** контекст, `Gdx.gl30 != null` (текстурные массивы,
  инстансинг, FBO — всё есть), **но** дефолтный шейдер SpriteBatch ломается на core.

То есть на macOS нужен **4.1-core контекст на всё приложение + core-совместимые шейдеры для UI**.

---

## 2. Решение

macOS desktop = GL30-эмуляция (4.1-core) + core-шейдеры для всех Scene2D/SpriteBatch/ShapeRenderer
+ новый десктопный VBO-рендерер (порт Android-варианта), выбираемый через DI только на macOS.

### 2.1 Launcher — платформо-зависимая GL-эмуляция
`lwjgl3/.../Lwjgl3Launcher.java`:
```java
boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
if (isMac) {
    configuration.setOpenGLEmulation(GLEmulation.GL30, 3, 2); // 4.1-core, Gdx.gl30 доступен
} else {
    configuration.setOpenGLEmulation(GLEmulation.GL32, 3, 2); // Win/Linux — SSBO через Gdx.gl31
}
```
`-XstartOnFirstThread` на macOS уже разруливается существующим `StartupHelper`.

### 2.2 Core-шейдеры для Scene2D — `CoreDesktopShaders`
Новый `core/.../systems/render/CoreDesktopShaders.kt`: на macOS отдаёт `#version 150` core-версии
дефолтных шейдеров `SpriteBatch`/`ShapeRenderer` (`newBatch()`, `newShapeRenderer()`, `newStage(viewport)`).
На остальных платформах возвращает обычные конструкторы libGDX — поведение не меняется.
Через хелпер прошиты **все** места создания `Stage`/`SpriteBatch`/`ShapeRenderer` (10 экранов).

### 2.3 Десктопный VBO-рендерер — `ShaderManagerDesktopVbo` / `PheromoneShaderManagerDesktopVbo`
Порт Android-рендереров в `core`: тот же пайплайн VBO + инстансинг (без SSBO), но GL-вызовы идут
через libGDX `Gdx.gl30` (а не `android.opengl.GLES32`), а шейдеры — через `DesktopShaderSource`.
`GL30 extends GL20`, поэтому `Gdx.gl30` покрывает все нужные вызовы.

**Грабли, пойманные при порте (важно для ревью):**
- **Direct-буферы.** LWJGL требует **direct** `IntBuffer` (`BufferUtils.newIntBuffer`) для
  gen/delete/iv-запросов. Heap-буферы (`IntBuffer.allocate`/`wrap`) роняют JVM нативно (SIGSEGV в
  `glGetShaderiv_Exec`). GL-объекты хранятся как обычные `int`.
- **Размеры.** У libGDX `glBufferData`/`glBufferSubData` — размеры типа **int** (а не long).
- **iv-запросы.** `glGetShaderiv`/`glGetProgramiv` — только через `IntBuffer` (без `int[]+offset`).
- **HiDPI/Retina.** Рендерер использует **back-buffer** (`Gdx.graphics.backBufferWidth/Height`) для
  FBO и `glViewport`, а не логический размер окна. На Retina back-buffer в 2× больше логического,
  поэтому размер по `Gdx.graphics.width/height` рисовал контент только в нижней четверти окна.
  Камера через clip-space (−1..1) работает при любом размере viewport, так что это даёт полноэкранный
  чёткий рендер.
- **VAO.** `glGenVertexArrays`/`glDeleteVertexArrays` (`GL30`) берут форму `int[]+offset` (LWJGL
  копирует массив — heap ок); буферы/текстуры/FBO (`GL20`) — только `IntBuffer`.

### 2.4 Конверсия ES-шейдеров — `DesktopShaderSource`
Новый `core/.../systems/render/DesktopShaderSource.kt`: читает существующие `_android`/shared
шейдеры и на лету превращает `#version 320 es` → `#version 410`, выкидывает `precision …;` и
квалификаторы `highp`/`mediump`/`lowp` (desktop-семантика не меняется — там и так highp).
Единый источник шейдеров остаётся, Android не затрагивается.

### 2.5 Шейдеры color-picker'а vis-ui
vis-ui (`com.kotcrab.vis.ui.widget.color.internal.Palette` и др.) грузит свои шейдеры
(`default.vert`, `palette.frag`, `hsv.frag`, `rgb.frag`, `verticalBar.frag`, `checkerboard.frag`)
через `Gdx.files.classpath(...)` — GL2-стиль, падают на macOS core. Core-версии (`#version 150`)
положены в `lwjgl3/src/main/resources/com/kotcrab/vis/ui/widget/color/internal/` — на десктопе
classpath разрешает ресурсы lwjgl3-модуля раньше jar'а vis-ui, поэтому macOS/Win/Linux берут
core-шейдеры; Android (без модуля lwjgl3) использует оригинальные GLES-шейдеры vis-ui.

### 2.6 `CircleWidget`
`core/.../editor/ui/dialog/CircleWidget.kt`: desktop-ветка шейдеров переведена на core
(`#version 150`, `in`/`out`/`fragColor`); ветка `GL_ES` (Android) не тронута.

### 2.7 DI — выбор рендерера
`core/.../core/DIGameGlobalContainer.kt` и `DISimulationContainer.kt`:
```kotlin
ApplicationType.Desktop -> if (isMac()) ShaderManagerDesktopVbo() else ShaderManagerLibgdxApi()
// (для феромонов — PheromoneShaderManagerDesktopVbo / PheromoneShaderManagerLibgdx)
```
Редактор переиспользует `DIGameGlobalContainer.shaderManager`, так что одно изменение покрывает и его.

---

## 3. Что НЕ изменилось
- **Windows/Linux:** по-прежнему `GL32` + `ShaderManagerLibgdxApi`/`PheromoneShaderManagerLibgdx`
  (SSBO) и оригинальные `_pc`-шейдеры. Поведение идентично.
- **Android:** свой GLES-рендерер через `androidRendererFactory`. Файлы `_android`-шейдеров
  переиспользуются (read-only) mac'овой конверсией — их содержимое не меняется.

---

## 4. Сборка и запуск

```bash
# JDK 17 (Gradle 8.12 не запускается на JDK 26+)
export JAVA_HOME=/path/to/jdk17
./gradlew :lwjgl3:run     # macOS: -XstartOnFirstThread добавляет StartupHelper / блок run{}
```

Проверено на macOS (Apple Silicon, OpenGL 4.1 «Metal»): меню, редактор генома (рендер тканей
заполняет окно, color picker работает), симуляция. Windows/Linux/Android — без регрессий по логике
(конфиг/рендерер для не-mac не тронут).

---

## 5. Изменённые/новые файлы

Новые:
- `core/.../systems/render/CoreDesktopShaders.kt`
- `core/.../systems/render/DesktopShaderSource.kt`
- `core/.../systems/render/ShaderManagerDesktopVbo.kt`
- `core/.../systems/pheromone/PheromoneShaderManagerDesktopVbo.kt`
- `lwjgl3/src/main/resources/com/kotcrab/vis/ui/widget/color/internal/{default.vert,palette.frag,hsv.frag,rgb.frag,verticalBar.frag,checkerboard.frag}`

Правки:
- `lwjgl3/.../Lwjgl3Launcher.java` (GL-эмуляция по платформе)
- `core/.../core/DIGameGlobalContainer.kt`, `DISimulationContainer.kt` (DI)
- `core/.../editor/ui/dialog/CircleWidget.kt` (core-шейдер)
- экраны Scene2D: `MenuScreen`, `SimulationScreen`, `GenomeEditorScreen`, `WorldEditorScreen`,
  `EcoSystemScreen`, `EcoSystemScreenCellsSettings`, `EcoSystemScreenGlobalSettings`,
  `SettingsScreen`, `SupportScreen`, `JsonSettingsEditorScreen` (`CoreDesktopShaders.*`)

---

## 6. Ограничения
- OpenGL на macOS депрекейтнут Apple. Фикс заставляет существующую GL-игру работать на macOS 4.1;
  это **не** порт на Metal и не защита от будущего.
- Визуально macOS-путь соответствует Android/VBO-рендереру (оба рендерят одни и те же CPU-считаемые
  данные о частицах). Возможная GPU-оптимизация SSBO-пути (пространственный grid) на macOS не реплицируется.
