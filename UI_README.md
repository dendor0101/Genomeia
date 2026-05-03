# Адаптивный UI для Genomeia

## Обзор

Этот проект теперь включает современный, полностью адаптивный пользовательский интерфейс, разработанный для работы на различных размерах экранов - от мобильных устройств до десктопов.

## Особенности

### 🎨 Современный дизайн
- **Тёмная тема** по умолчанию с приятной цветовой палитрой
- **Градиентные эффекты** для заголовков и кнопок
- **Плавные анимации** при наведении и нажатии
- **Визуальная иерархия** с различными стилями для разных типов элементов

### 📱 Полная адаптивность
- **Автоматическое определение размера экрана**
- **Динамическое изменение размеров** кнопок и шрифтов
- **Поддержка различных DPI** (Retina, HDPI, XHDPI)
- **Оптимизация для мобильных устройств** и десктопов

### 🛠️ Технические характеристики

#### Цветовая схема
```
Primary:       #3399FF (синий)
Primary Dark:  #2673B3
Primary Light: #66B2F2
Accent:        #F26680 (розовый)
Background:    #1F1F2E (тёмно-синий)
Panel:         #262633
Text:          #F2F2F2 (светло-серый)
```

#### Компоненты VisUI
Все стандартные компоненты VisUI настроены:
- Кнопки (TextButton, ImageButton, ImageTextButton)
- Поля ввода (TextField, ValidatableTextField)
- Чекбоксы и радио-кнопки
- Слайдеры (горизонтальные и вертикальные)
- Выпадающие списки (SelectBox)
- Окна и диалоги
- Меню и всплывающие подсказки
- Прогресс-бары
- Деревья и таблицы

## Установка

### 1. Файл скина
Основной файл конфигурации UI находится в:
```
assets/ui/uiskin.json
```

### 2. Загрузка в коде
В файле `MainGame.kt` скин загружается автоматически:

```kotlin
override fun create() {
    val skinFile = Gdx.files.internal("ui/uiskin.json")
    if (skinFile.exists()) {
        VisUI.load(skinFile)
        skin = VisUI.getSkin()
    } else {
        VisUI.load()  // Fallback на дефолтный скин
        skin = VisUI.getSkin()
    }
}
```

### 3. Использование в экранах
Пример использования в `MenuScreen.kt`:

```kotlin
// Адаптивные размеры
val screenWidth = Gdx.graphics.width
val screenHeight = Gdx.graphics.height
val isSmallScreen = screenWidth < 600 || screenHeight < 400

val buttonWidth = if (isSmallScreen) 280f * density else 350f * density
val buttonHeight = if (isSmallScreen) 45f * density else 55f * density

// Создание кнопки со стилем из скина
val button = VisTextButton("Текст", "default")
table.add(button).width(buttonWidth).height(buttonHeight)
```

## Настройка

### Изменение цветов
Откройте `assets/ui/uiskin.json` и измените значения в секции `com.badlogic.gdx.graphics.Color`:

```json
com.badlogic.gdx.graphics.Color: {
  primary: { r: 0.2; g: 0.6; b: 0.9; a: 1 }
  accent: { r: 0.95; g: 0.4; b: 0.5; a: 1 }
  // ... другие цвета
}
```

### Добавление новых стилей
Добавьте новый стиль в соответствующую секцию файла uiskin.json:

```json
com.badlogic.gdx.scenes.scene2d.ui.TextButton$TextButtonStyle: {
  myCustomStyle: { 
    up: bg-primary; 
    down: bg-primary-dark; 
    over: bg-primary-light; 
    font: button; 
    fontColor: white 
  }
}
```

Используйте в коде:
```kotlin
val button = VisTextButton("Текст", "myCustomStyle")
```

### Адаптация под разные экраны

#### Пороговые значения
Измените пороги для определения "маленького" экрана в `MenuScreen.kt`:

```kotlin
val isSmallScreen = screenWidth < 600 || screenHeight < 400
```

#### Масштабирование UI
Глобальный масштаб UI можно настроить в настройках игры или программно:

```kotlin
GlobalSettings.UI_SCALE = 1.5f  // Увеличить UI на 50%
```

## Структура файлов

```
assets/
└── ui/
    ├── uiskin.json          # Конфигурация скина
    └── background.png       # Фоновое изображение

core/src/.../ui/screens/
├── MainGame.kt              # Инициализация UI
├── MenuScreen.kt            # Главное меню (адаптивное)
├── SettingsScreen.kt        # Настройки
├── SimulationScreen.kt      # Экран симуляции
└── ...
```

## Рекомендации

### Для разработчиков

1. **Всегда используйте стили из скина:**
   ```kotlin
   // ✅ Хорошо
   val button = VisTextButton("Текст", "default")
   
   // ❌ Избегайте
   val button = VisTextButton("Текст")
   button.style.fontColor = Color.WHITE
   ```

2. **Адаптируйте размеры под плотность пикселей:**
   ```kotlin
   val density = Gdx.graphics.density
   val size = 50f * density  // Автоматический размер
   ```

3. **Используйте VisTable для компоновки:**
   ```kotlin
   val table = VisTable()
   TableUtils.setSpacingDefaults(table)
   table.setFillParent(true)
   ```

4. **Проверяйте существование файлов скина:**
   ```kotlin
   if (Gdx.files.internal("ui/uiskin.json").exists()) {
       // Загружаем кастомный скин
   }
   ```

### Для дизайнеров

1. **Соблюдайте контрастность** текста и фона
2. **Используйте единый стиль** для всех кнопок одного типа
3. **Тестируйте на разных разрешениях** (от 320x480 до 4K)
4. **Учитывайте safe area** на мобильных устройствах

## Отладка

### Логирование размеров шрифтов
```kotlin
Gdx.app.log("FontDebug", "Menu font cap height: ${game.mediumFont.capHeight}")
```

### Проверка текущего скина
```kotlin
Gdx.app.log("UIDebug", "Skin loaded: ${VisUI.isLoaded()}")
```

## Совместимость

- **libGDX**: 1.11.0+
- **VisUI**: 1.5.0+
- **Android**: API 21+
- **iOS**: iOS 10+
- **Desktop**: Windows, macOS, Linux

## Будущие улучшения

- [ ] Добавить светлую тему
- [ ] Анимации переходов между экранами
- [ ] Поддержка жестов для мобильных устройств
- [ ] Динамическая смена темы в реальном времени
- [ ] Больше предустановленных стилей для специфичных компонентов

## Лицензия

Часть проекта Genomeia. Смотрите основной файл LICENSE для деталей.
