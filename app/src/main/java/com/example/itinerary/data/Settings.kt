package com.example.itinerary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import java.time.LocalDate
import java.time.YearMonth

enum class AppTheme(val label: String) {
    MATRIX("Matrix Green"),
    HIGH_CONTRAST("High Contrast"),
    COLOUR_BLIND("Colour-blind friendly");

    companion object {
        fun fromStored(value: String?): AppTheme = entries.firstOrNull { it.name == value } ?: MATRIX
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("Use system setting"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class TimeFormat(val label: String) {
    SYSTEM("Use system setting"),
    HOUR_12("12-hour"),
    HOUR_24("24-hour"),
}

// The typeface used for all text in the app. The files are bundled in res/font (see ui/theme/Theme.kt for how each is
// drawn); the first is the default.
// A saved name that is no longer in this list (an earlier build had pixel fonts) falls back to the default.
enum class AppFont(val label: String) {
    TERMINAL("Terminal (JetBrains Mono)"),
    SPACE_MONO("Space Mono"),
    SYSTEM_MONO("System monospace"),
    SYSTEM_SANS("System sans-serif"),
    ATKINSON("Atkinson Hyperlegible"),
    LEXEND("Lexend"),
    OPEN_DYSLEXIC("Dyslexia-friendly (OpenDyslexic)"),
    ;

    companion object {
        val DEFAULT = TERMINAL
    }
}

// How a whole day is written where the app names a day (the plan screen's day heading, the event form's date and the
// day headings in Search); the patterns are in ui/Format.kt. A saved name that no longer exists falls back to the default.
enum class DateFormatChoice(val label: String) {
    DAY_MONTH_YEAR("Day Month Year"),
    MONTH_DAY_YEAR("Month Day, Year"),
    SHORT_NAMED("Short, with month name"),
    NUMERIC_DMY("Numeric, day first"),
    NUMERIC_MDY("Numeric, month first"),
    ISO("Year-month-day"),
    NO_YEAR("Without the year"),
    SYSTEM("Use system setting"),
    ;

    companion object {
        val DEFAULT = DAY_MONTH_YEAR
    }
}

/** How Quick entry reads a numeric date such as 3/4: day first, month first, or null to ask. */
fun DateFormatChoice.numericDayFirst(locale: java.util.Locale = java.util.Locale.getDefault()): Boolean? = when (this) {
    DateFormatChoice.DAY_MONTH_YEAR, DateFormatChoice.NUMERIC_DMY -> true
    DateFormatChoice.MONTH_DAY_YEAR, DateFormatChoice.NUMERIC_MDY -> false
    DateFormatChoice.SYSTEM -> java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        java.time.format.FormatStyle.SHORT, null, java.time.chrono.IsoChronology.INSTANCE, locale,
    ).let { pattern -> pattern.indexOf('d').takeIf { it >= 0 }?.let { day -> pattern.indexOf('M').takeIf { it >= 0 }?.let { day < it } } }
    else -> null
}

// All text in the app can be made smaller or larger; 100 is normal.
object TextSize {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 5
    const val DEFAULT_PERCENT = 100
}

// Every user setting in one piece, so a backup can save and restore them together.
data class SettingsSnapshot(
    val themeMode: ThemeMode,
    val timeFormat: TimeFormat,
    val calendarCollapsed: Boolean,
    // Built-in categories the user removed (see Categories.BUILT_IN).
    val hiddenCategories: Set<String>,
    // Show plans that start soon (or are under way) at the top of the list, and how many days ahead counts as soon.
    val upcomingOnTop: Boolean = true,
    val upcomingDays: Int = UpcomingPlans.DEFAULT_DAYS,
    // How see-through the big + button on the agenda is, in percent (0 = solid).
    val addButtonSeeThrough: Int = AddButton.DEFAULT_SEE_THROUGH,
    // The colour of every heading in the app, as an opaque ARGB value.
    val headingColor: Int = HeadingColor.DEFAULT_ARGB,
    val appFont: AppFont = AppFont.DEFAULT,
    // How big all text is, in percent (see TextSize).
    val textSizePercent: Int = TextSize.DEFAULT_PERCENT,
    val dateFormat: DateFormatChoice = DateFormatChoice.DEFAULT,
    val agendaRange: AgendaRange = AgendaRange.UPCOMING,
    val appTheme: AppTheme = AppTheme.MATRIX,
    val savedSearches: List<SavedSearch> = emptyList(),
)

// Headings (screen titles, section headings, day headers, dialog titles) share one colour the user can change.
object HeadingColor {
    // Red orange (#FF5614).
    const val DEFAULT_ARGB: Int = 0xFFFF5614.toInt()
}

// The big + button on the agenda can be made see-through so what is behind it shows. It never goes fully invisible.
object AddButton {
    const val MIN_SEE_THROUGH = 0
    const val MAX_SEE_THROUGH = 80
    const val DEFAULT_SEE_THROUGH = 30
}

class SettingsRepository(context: Context, private val onChanged: () -> Unit = {}) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Device-local master AI permission; provider keys are stored separately.
    // Off until the person turns it on; their choice is saved and kept from then on.
    private val _aiFeaturesEnabled = MutableStateFlow(prefs.getBoolean("ai_features_enabled", false))
    val aiFeaturesEnabled = _aiFeaturesEnabled.asStateFlow()
    fun setAiFeaturesEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("ai_features_enabled", value).commit()) { "Couldn't save AI preference. Try again." }
        _aiFeaturesEnabled.value = value
    }

    private val _savedSearches = kotlinx.coroutines.flow.MutableStateFlow(runCatching {
        SavedSearchCodec.decode(org.json.JSONArray(prefs.getString("saved_searches", "[]")))
    }.getOrDefault(emptyList()))
    val savedSearches = _savedSearches.asStateFlow()
    fun setSavedSearches(values: List<SavedSearch>) {
        val encoded = SavedSearchCodec.encode(values)
        SavedSearchCodec.decode(encoded)
        check(prefs.edit().putString("saved_searches", encoded.toString()).commit()) { "Couldn't save searches. Try again." }
        _savedSearches.value = values
    }

    // Device-local navigation state; independent of exported planner data.
    var lastViewCalendar: Boolean
        get() = prefs.getBoolean("last_view_calendar", false)
        set(value) { prefs.edit { putBoolean("last_view_calendar", value) } }

    var lastCalendarDate: LocalDate?
        get() = runCatching { LocalDate.parse(prefs.getString("last_calendar_date", null)) }.getOrNull()
        set(value) { prefs.edit { putString("last_calendar_date", value?.toString()) } }

    var lastCalendarMonth: YearMonth?
        get() = runCatching { YearMonth.parse(prefs.getString("last_calendar_month", null)) }.getOrNull()
        set(value) { prefs.edit { putString("last_calendar_month", value?.toString()) } }

    private val _agendaTypes = MutableStateFlow(AgendaTypes.decode(
        prefs.getStringSet("agenda_types", null), prefs.getString("agenda_type", null)))
    val agendaTypes: StateFlow<Set<AgendaType>> = _agendaTypes.asStateFlow()
    fun setAgendaTypes(types: Set<AgendaType>) {
        val selected = types.toSet()
        prefs.edit { putStringSet("agenda_types", selected.map { it.name }.toSet()) }
        _agendaTypes.value = selected
    }
    fun toggleAgendaType(type: AgendaType) = setAgendaTypes(AgendaTypes.toggle(_agendaTypes.value, type))

    private val _agendaRange = MutableStateFlow(
        runCatching { AgendaRange.valueOf(prefs.getString("agenda_range", "UPCOMING").orEmpty()) }.getOrDefault(AgendaRange.UPCOMING),
    )
    val agendaRange: StateFlow<AgendaRange> = _agendaRange.asStateFlow()

    fun setAgendaRange(range: AgendaRange) {
        prefs.edit { putString("agenda_range", range.name) }
        _agendaRange.value = range
    }

    // Device-local presentation preference; survives navigation and app restarts.
    private val _showBillsSummary = MutableStateFlow(prefs.getBoolean("show_bills_summary", true))
    val showBillsSummary: StateFlow<Boolean> = _showBillsSummary.asStateFlow()

    fun setShowBillsSummary(show: Boolean) {
        prefs.edit { putBoolean("show_bills_summary", show) }
        _showBillsSummary.value = show
    }

    private val _billsExpanded = MutableStateFlow(prefs.getBoolean("bills_expanded", true))
    val billsExpanded: StateFlow<Boolean> = _billsExpanded.asStateFlow()

    fun setBillsExpanded(expanded: Boolean) {
        prefs.edit { putBoolean("bills_expanded", expanded) }
        _billsExpanded.value = expanded
    }

    private val _appTheme = MutableStateFlow(AppTheme.fromStored(prefs.getString("app_theme", null)))
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    fun setAppTheme(theme: AppTheme) {
        prefs.edit { putString("app_theme", theme.name) }
        _appTheme.value = theme
    }

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _timeFormat = MutableStateFlow(loadTimeFormat())
    val timeFormat: StateFlow<TimeFormat> = _timeFormat.asStateFlow()

    // Whether the plan screen's calendar is collapsed to a week strip. One choice for every plan.
    private val _calendarCollapsed = MutableStateFlow(prefs.getBoolean(KEY_CALENDAR_COLLAPSED, false))
    val calendarCollapsed: StateFlow<Boolean> = _calendarCollapsed.asStateFlow()

    // Built-in categories the user has removed. They stay out of the category chips until they are added again.
    private val _hiddenCategories = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_CATEGORIES, emptySet()).orEmpty().toSet(),
    )
    val hiddenCategories: StateFlow<Set<String>> = _hiddenCategories.asStateFlow()

    fun setHiddenCategories(names: Set<String>) {
        prefs.edit { putStringSet(KEY_HIDDEN_CATEGORIES, names) }
        _hiddenCategories.value = names
    }

    fun hideCategories(names: Collection<String>) = setHiddenCategories(_hiddenCategories.value + names)

    fun showCategory(name: String) = setHiddenCategories(_hiddenCategories.value - name)

    // Plans that start soon (or are already under way) float to the top of the list. On by default, 7 days ahead.
    private val _upcomingOnTop = MutableStateFlow(prefs.getBoolean(KEY_UPCOMING_ON_TOP, true))
    val upcomingOnTop: StateFlow<Boolean> = _upcomingOnTop.asStateFlow()

    private val _upcomingDays = MutableStateFlow(
        prefs.getInt(KEY_UPCOMING_DAYS, UpcomingPlans.DEFAULT_DAYS).coerceIn(UpcomingPlans.MIN_DAYS, UpcomingPlans.MAX_DAYS),
    )
    val upcomingDays: StateFlow<Int> = _upcomingDays.asStateFlow()

    fun setUpcomingOnTop(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_UPCOMING_ON_TOP, enabled) }
        _upcomingOnTop.value = enabled
    }

    fun setUpcomingDays(days: Int) {
        val clean = days.coerceIn(UpcomingPlans.MIN_DAYS, UpcomingPlans.MAX_DAYS)
        prefs.edit { putInt(KEY_UPCOMING_DAYS, clean) }
        _upcomingDays.value = clean
    }

    private val _addButtonSeeThrough = MutableStateFlow(
        prefs.getInt(KEY_ADD_BUTTON_SEE_THROUGH, AddButton.DEFAULT_SEE_THROUGH)
            .coerceIn(AddButton.MIN_SEE_THROUGH, AddButton.MAX_SEE_THROUGH),
    )
    val addButtonSeeThrough: StateFlow<Int> = _addButtonSeeThrough.asStateFlow()

    fun setAddButtonSeeThrough(percent: Int) {
        val clean = percent.coerceIn(AddButton.MIN_SEE_THROUGH, AddButton.MAX_SEE_THROUGH)
        prefs.edit { putInt(KEY_ADD_BUTTON_SEE_THROUGH, clean) }
        _addButtonSeeThrough.value = clean
    }

    private val _headingColor = MutableStateFlow(prefs.getInt(KEY_HEADING_COLOR, HeadingColor.DEFAULT_ARGB) or OPAQUE)
    val headingColor: StateFlow<Int> = _headingColor.asStateFlow()

    fun setHeadingColor(argb: Int) {
        val clean = argb or OPAQUE
        prefs.edit { putInt(KEY_HEADING_COLOR, clean) }
        _headingColor.value = clean
    }

    private val _appFont = MutableStateFlow(
        runCatching { AppFont.valueOf(prefs.getString(KEY_APP_FONT, null).orEmpty()) }.getOrDefault(AppFont.DEFAULT),
    )
    val appFont: StateFlow<AppFont> = _appFont.asStateFlow()

    fun setAppFont(font: AppFont) {
        prefs.edit { putString(KEY_APP_FONT, font.name) }
        _appFont.value = font
    }

    private val _textSizePercent = MutableStateFlow(
        prefs.getInt(KEY_TEXT_SIZE, TextSize.DEFAULT_PERCENT).coerceIn(TextSize.MIN_PERCENT, TextSize.MAX_PERCENT),
    )
    val textSizePercent: StateFlow<Int> = _textSizePercent.asStateFlow()

    fun setTextSizePercent(percent: Int) {
        val clean = percent.coerceIn(TextSize.MIN_PERCENT, TextSize.MAX_PERCENT)
        prefs.edit { putInt(KEY_TEXT_SIZE, clean) }
        _textSizePercent.value = clean
    }

    private val _dateFormat = MutableStateFlow(
        runCatching { DateFormatChoice.valueOf(prefs.getString(KEY_DATE_FORMAT, null).orEmpty()) }
            .getOrDefault(DateFormatChoice.DEFAULT),
    )
    val dateFormat: StateFlow<DateFormatChoice> = _dateFormat.asStateFlow()
    init { QuickEntry.numericDayFirst = _dateFormat.value.numericDayFirst() }

    private fun notifyChanged() {
        try { onChanged() }
        catch (e: Exception) { android.util.Log.w("Settings", "Settings saved; widget refresh failed", e) }
    }

    fun setDateFormat(choice: DateFormatChoice) {
        prefs.edit { putString(KEY_DATE_FORMAT, choice.name) }
        _dateFormat.value = choice
        QuickEntry.numericDayFirst = choice.numericDayFirst()
        notifyChanged()
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    fun setCalendarCollapsed(collapsed: Boolean) {
        prefs.edit { putBoolean(KEY_CALENDAR_COLLAPSED, collapsed) }
        _calendarCollapsed.value = collapsed
    }

    fun setTimeFormat(format: TimeFormat) {
        prefs.edit { putString(KEY_TIME_FORMAT, format.name) }
        _timeFormat.value = format
        notifyChanged()
    }

    fun snapshot() = SettingsSnapshot(
        _themeMode.value,
        _timeFormat.value,
        _calendarCollapsed.value,
        _hiddenCategories.value,
        _upcomingOnTop.value,
        _upcomingDays.value,
        _addButtonSeeThrough.value,
        _headingColor.value,
        _appFont.value,
        _textSizePercent.value,
        _dateFormat.value,
        _agendaRange.value,
        _appTheme.value,
        _savedSearches.value,
    )

    fun applySnapshot(settings: SettingsSnapshot) {
        setSavedSearches(settings.savedSearches)
        setAppTheme(settings.appTheme)
        setThemeMode(settings.themeMode)
        setTimeFormat(settings.timeFormat)
        setCalendarCollapsed(settings.calendarCollapsed)
        setHiddenCategories(settings.hiddenCategories)
        setUpcomingOnTop(settings.upcomingOnTop)
        setUpcomingDays(settings.upcomingDays)
        setAddButtonSeeThrough(settings.addButtonSeeThrough)
        setHeadingColor(settings.headingColor)
        setAppFont(settings.appFont)
        setTextSizePercent(settings.textSizePercent)
        setDateFormat(settings.dateFormat)
        setAgendaRange(settings.agendaRange)
    }

    private fun loadThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    private fun loadTimeFormat(): TimeFormat =
        runCatching { TimeFormat.valueOf(prefs.getString(KEY_TIME_FORMAT, null) ?: TimeFormat.SYSTEM.name) }
            .getOrDefault(TimeFormat.SYSTEM)

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_TIME_FORMAT = "time_format"
        const val KEY_CALENDAR_COLLAPSED = "calendar_collapsed"
        const val KEY_HIDDEN_CATEGORIES = "hidden_categories"
        const val KEY_UPCOMING_ON_TOP = "upcoming_on_top"
        const val KEY_UPCOMING_DAYS = "upcoming_days"
        const val KEY_ADD_BUTTON_SEE_THROUGH = "add_button_see_through"
        const val KEY_HEADING_COLOR = "heading_color"
        const val KEY_APP_FONT = "app_font"
        const val KEY_TEXT_SIZE = "text_size_percent"
        const val KEY_DATE_FORMAT = "date_format"
        const val OPAQUE = 0xFF000000.toInt()
    }
}
