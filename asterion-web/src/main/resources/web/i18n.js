'use strict';

// The languages of the page. Its texts are messages, which messages-<language>.js define by key: the page is in
// the language which the user chose, or the first one of the browser which it has, or English. The elements of the
// page name their messages with data-i18n (their text), data-i18n-title, data-i18n-aria-label, and data-help (a
// "?" which explains a parameter, with the message help.<name>).

/** The languages of the page, by their codes, the first one being the default. */
const LANGUAGES = {en: 'English', fr: 'Français'};
const MESSAGES = {};
const LANGUAGE_STORAGE_KEY = 'asterion.language';

/** Defines the messages of a language. */
function messages(code, table) {
    MESSAGES[code] = table;
}

/** The language the user chose on this browser, or the first one of the browser which the page has. */
function initialLanguage() {
    try {
        const chosen = localStorage.getItem(LANGUAGE_STORAGE_KEY);
        if (chosen in LANGUAGES) return chosen;
    } catch {
        // storage is disabled: the browser decides
    }
    for (const tag of navigator.languages || [navigator.language]) {
        const code = String(tag).toLowerCase().split('-')[0];
        if (code in LANGUAGES) return code;
    }
    return Object.keys(LANGUAGES)[0];
}

let language = initialLanguage();
const languageListeners = [];

/**
 * A message, in the language of the page, or in English when it has no translation: {name} are replaced by the
 * values of the same names.
 */
function t(key, values = {}) {
    const text = MESSAGES[language]?.[key] ?? MESSAGES.en[key];
    if (text === undefined) {
        console.warn(`No message ${key}`);
        return key;
    }
    return text.replace(/\{(\w+)\}/g, (placeholder, name) => name in values ? values[name] : placeholder);
}

/** A message which depends on a count, such as "1 object", "2 objects": the keys <key>.one and <key>.other. */
function tn(key, count, values = {}) {
    const form = new Intl.PluralRules(language).select(count) === 'one' ? 'one' : 'other';
    return t(`${key}.${form}`, {count: formatNumber(count), ...values});
}

/** A number, as the language writes it: 1,234.5 in English, 1 234,5 in French. */
function formatNumber(value, fractionDigits = 0, options = {}) {
    return new Intl.NumberFormat(language, {minimumFractionDigits: fractionDigits, maximumFractionDigits: fractionDigits, ...options})
        .format(value);
}

/** The type of a deep sky object, which the server gives in English, such as "open cluster". */
function objectType(type) {
    return MESSAGES[language]?.[`type.${type}`] ?? MESSAGES.en[`type.${type}`] ?? type;
}

/** Puts the messages of the language in the elements which name them, under an element. */
function localize(root = document) {
    for (const e of root.querySelectorAll('[data-i18n]')) {
        // {0}, {1}... are the elements of the message, such as links, which are kept
        e.i18nSlots ??= [...e.children];
        const parts = t(e.dataset.i18n).split(/\{(\d)\}/);
        e.replaceChildren(...parts.map((part, i) => i % 2 ? e.i18nSlots[Number(part)] : part));
    }
    for (const e of root.querySelectorAll('[data-i18n-title]')) {
        e.title = t(e.dataset.i18nTitle);
    }
    for (const e of root.querySelectorAll('[data-i18n-aria-label]')) {
        e.setAttribute('aria-label', t(e.dataset.i18nAriaLabel));
    }
    for (const e of root.querySelectorAll('[data-help]')) {
        helpMarker(e, t(`help.${e.dataset.help}`));
    }
    document.documentElement.lang = language;
}

/**
 * A "?" which explains a parameter: its text shows when the pointer is over it, or when it's focused, which a tap
 * does on a touch screen. It's the description of the field of its label, for screen readers.
 */
function helpMarker(marker, text) {
    if (!marker.classList.contains('help')) {
        marker.classList.add('help');
        marker.tabIndex = 0;
        const tooltip = document.createElement('span');
        tooltip.className = 'help-text';
        tooltip.id = `help-${marker.dataset.help}`;
        tooltip.setAttribute('role', 'tooltip');
        marker.append('?', tooltip);
        // in a label, a click would also check its checkbox, or focus its field
        marker.addEventListener('click', event => event.preventDefault());
        const field = marker.closest('label')?.querySelector('input, select');
        field?.setAttribute('aria-describedby', tooltip.id);
    }
    marker.setAttribute('aria-label', t('help.label'));
    marker.lastChild.textContent = text;
}

/** Changes the language of the page, which this browser remembers, and of what it shows. */
function setLanguage(code) {
    language = code;
    try {
        localStorage.setItem(LANGUAGE_STORAGE_KEY, code);
    } catch {
        // storage is disabled: the language is the one of the browser at the next visit
    }
    localize();
    languageListeners.forEach(listener => listener());
}

/** Calls a function when the language changes, to show again what the page made with messages. */
function onLanguageChange(listener) {
    languageListeners.push(listener);
}

/** The menu of the languages. */
function languageMenu(select) {
    select.replaceChildren(...Object.entries(LANGUAGES).map(([code, name]) => {
        const option = document.createElement('option');
        option.textContent = name;
        option.value = code;
        option.lang = code;
        return option;
    }));
    select.value = language;
    select.addEventListener('change', () => setLanguage(select.value));
}
