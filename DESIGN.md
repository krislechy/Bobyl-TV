---
name: "Бобыль — частный кинозал"
description: "Нативный каталог Android TV: графит, светлый нефрит и целые постеры."
colors:
  background: "#101719"
  rail: "#172124"
  surface: "#202B2E"
  raised: "#29383A"
  accent: "#A5C9B4"
  focus: "#F2C879"
  on-accent: "#10251C"
  text: "#F1F3F0"
  muted: "#ADB9B9"
  outline: "#435252"
  selected: "#2B4038"
typography:
  details-title:
    fontFamily: "Android system sans-serif"
    fontSize: "34sp"
    fontWeight: 700
    lineHeight: "40sp"
  headline:
    fontFamily: "Android system sans-serif"
    fontSize: "32sp"
    fontWeight: 700
  modal-title:
    fontFamily: "Android system sans-serif"
    fontSize: "30sp"
    fontWeight: 700
  brand:
    fontFamily: "Android system sans-serif"
    fontSize: "23sp"
    fontWeight: 700
  field:
    fontFamily: "Android system sans-serif"
    fontSize: "18sp"
    fontWeight: 400
  body:
    fontFamily: "Android system sans-serif"
    fontSize: "17sp"
    fontWeight: 400
    lineHeight: "26sp"
  action:
    fontFamily: "Android system sans-serif"
    fontSize: "16sp"
    fontWeight: 600
  navigation:
    fontFamily: "Android system sans-serif"
    fontSize: "16sp"
    fontWeight: 400
  poster-title:
    fontFamily: "Android system sans-serif"
    fontSize: "15sp"
    fontWeight: 500
    lineHeight: "19sp"
  poster-status:
    fontFamily: "Android system sans-serif"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
rounded:
  control: "8dp"
  panel: "16dp"
spacing:
  rail-item-gap: "4dp"
  title-gap: "8dp"
  icon-gap: "10dp"
  compact: "12dp"
  grid-gap: "14dp"
  panel-gap: "16dp"
  section-gap: "20dp"
  page: "24dp"
  modal: "28dp"
  page-bottom: "32dp"
  details: "34dp"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8dp 20dp"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8dp 20dp"
  navigation-selected:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.control}"
    padding: "8dp 12dp"
  navigation-focused:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    padding: "8dp 12dp"
  search-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.field}"
    rounded: "{rounded.control}"
    padding: "8dp 12dp"
  poster:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.control}"
  modal:
    backgroundColor: "{colors.background}"
    textColor: "{colors.text}"
    rounded: "{rounded.panel}"
    padding: "28dp"
---

# Design System: Бобыль — частный кинозал

## Overview

**Creative North Star: "Частный кинозал"**

Спокойная графитовая оболочка отдаёт внимание постерам. Светлый нефрит обозначает
выбор и основное действие, золотой контур — фокус пульта. Оформление рассчитано
на телевизор, расстояние до экрана и частые нажатия пульта.

Это снимок реализованной системы Kotlin / Jetpack Compose TV. Числа во frontmatter
нормативны для оформления: размеры Android заданы в dp, текст — в sp. Источник
реализации — [CinemaStyle.kt](app/src/main/java/com/bobyl/tv/CinemaStyle.kt) и общие
компоненты; при изменении этих значений обновлять оба документа системы.
Выбрана композиция 1 из [направления](docs/redesign-direction.md); выбор визуального
стиля делегирован пользователем. Существующие функции и настройки сохраняются.

**Key Characteristics:**

- Целые постеры и подписи под изображением.
- Тональные графитовые поверхности без свечения.
- Различимые выбранный раздел и текущий фокус.
- Мгновенное перемещение D-pad без масштабирования.

## Colors

Основной акцент — светлый нефрит; нейтрали имеют приглушённый зелёно-серый оттенок.

### Primary

- `accent` выделяет основное действие, выбор и активную подпись; `on-accent` — текст
  и значки на светлом акцентном заполнении.
- `focus` — золотой контур толщиной 3dp текущего элемента пульта.
- `selected` — тёмная подложка фокусируемого содержимого карточек.

### Neutral

- `background` — основание экранов и модальных панелей; `rail` — постоянное меню.
- `surface` — обычные кнопки, поля, списки и заглушки постеров; `raised` — вложенные действия.
- `text` — основной текст; `muted` — метаданные, подсказки и неактивные пункты меню.
- `outline` — спокойный контур полей и вложенных действий.

**The Selection Rule.** Выбранная кнопка сохраняет нефритовое заполнение независимо
от фокуса. Фокус обозначается золотым контуром; невыбранная кнопка под ним остаётся графитовой.

## Typography

Ожидание содержимого обозначается общим Skeleton Loading: форма будущего
каталога, строки, текста или вариантов выбора, без фокусируемых элементов.
Один общий пульс меняет только отрисовку и работает лишь при видимых заглушках;
при отключённых системных анимациях они статичны. Уже загруженные данные сохраняются.
Изображения используют ту же заглушку до успеха или ошибки. Индикатор на кнопке
и счётчик прогресса остаются для действий над готовыми данными.

Системный Android sans-serif поддерживает кириллицу без нового шрифтового пакета.
Иерархия во frontmatter охватывает заголовок фильма, заголовок страницы, модальный
заголовок, бренд, поле, основной текст, действия и подписи карточки.
Где lineHeight не указан, сохраняется унаследованное значение Compose.

Заголовки жирные, действия полужирные, названия постеров средней насыщенности.
Выбранный пункт меню получает вес 600; остальные — обычный.
Название под постером занимает ровно две строки с многоточием при переполнении.
Статус под названием занимает до двух строк, без наложения на изображение.

## Layout

Постоянное меню шириной 192dp имеет отступы 12dp по горизонтали и 20dp по вертикали.
Бренд использует существующую иконку 36dp. Пункты меню имеют минимальную высоту
44dp, значок 22dp и интервал до текста из `icon-gap`.

Главная использует отступы `page` сверху и по горизонтали, `page-bottom` снизу.
Секции разделяет `section-gap`, карточки — `grid-gap`, внутренний горизонтальный
отступ сетки — 8dp. Колонки берутся из существующих настроек пользователя
(по умолчанию шесть); ширина карточки определяется доступным пространством.
Соотношение сторон постера — 2:3.

Панель сведений занимает 96% ширины и 94% высоты, с отступом `details` и интервалом
`panel-gap`; постер сведений — 160×240dp. Общая модальная панель — 90%×90%, отступ
`modal`. Панель выбора воспроизведения — 80% ширины (максимум 650dp), 85% высоты,
с отступом `modal` и интервалом `grid-gap`. Веб-брейкпоинты не вводятся.

## Elevation & Depth

Глубину создают тональные поверхности и существующее затемнение позади диалога.
Фокус плоский: цвет и контур, без декоративного свечения и подъёма карточек.
Список в панели воспроизведения использует `surface` без тени.

## Shapes

Постеры, кнопки, поля и пункты меню используют `control`; модальные панели — `panel`.
Контур сфокусированного постера рисуется штрихом 5dp по краю и обрезается формой:
видимая внутренняя часть составляет 2.5dp. Фокус не меняет геометрию сетки.

## Components

### Buttons

Минимальная высота 44dp; отступы и типографика заданы в токенах компонентов.
Основная кнопка постоянно акцентная. Обычная кнопка становится акцентной при
фокусе; дополнительный светлый контур — 2dp, в покое прозрачный 1dp.
Надпись допускает до двух строк. Масштаб во всех состояниях остаётся единичным.

### Navigation

Обычный пункт прозрачен, значок и текст приглушены. Выбранный пункт использует
`navigation-selected`; сфокусированный — `navigation-focused` и акцентный контур
2dp. Сохраняются все девять разделов и существующий порядок.

### Posters and status

Изображения берутся из реального удалённого каталога, а не из иллюстративного
концепта. Фокус добавляет нефритовый контур и меняет цвет названия на `accent`.
Статус остаётся `muted` под названием. Длинные названия и статусы не перекрывают постер.

### Inputs

Поле имеет минимальную высоту 64dp и контур `outline` 1dp; фокус меняет его на
`accent` 2dp. Действия поля — квадраты 48dp. Основное или сфокусированное доступное
действие акцентное, остальные используют `raised` / `text`, недоступные — `muted`.
Существующие поиск, очистка и голосовой ввод сохраняются.

### Assets and evidence

Сохраняется существующий `bobyl_icon`; этот редизайн не создаёт новый брендовый растр.
Постеры и метаданные поступают из существующего каталога и не включаются в документ
как новые локальные ассеты. Навигационные VectorDrawable происходят из Google
Material Icons Outlined: [источники и Apache 2.0](docs/design-icons-NOTICE.md).
Концепт — только ориентир с [метаданными происхождения](.impeccable/mocks/concept-1.png.json).

Визуальные свидетельства: [главная](captures/redesign-home.png),
[сведения](captures/redesign-details.png), [поиск](captures/redesign-search.png).
Независимое ревью не выявило блокирующих визуальных регрессий; его замечание касалось
документации и происхождения ассетов. Веб-конвейер raster/font остаётся на открытом
этапе spec в [состоянии сборки](.impeccable/build/state.json); автоматические ворота
не объявляются пройденными. Эта документация не подтверждает дополнительные
функциональные проверки. Accessibility/TalkBack исключены из текущего запроса.

## Do's and Don'ts

### Do

- **Do** сохранять системный шрифт, имя «Бобыль» и существующую иконку.
- **Do** брать постеры и сведения из действующего каталога.
- **Do** отличать выбранный раздел от текущего фокуса пульта.
- **Do** сохранять число колонок из настроек и существующие действия.

### Don't

- **Don't** накладывать название или статус на постер.
- **Don't** добавлять масштабирование при каждом перемещении D-pad.
- **Don't** заменять реальные данные изображениями из концепта.
- **Don't** вводить свечение или декоративные градиенты в общие поверхности.
