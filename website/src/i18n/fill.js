// {name} placeholders, on the server (src/i18n/index.js's translate) and in the browser, where a page's
// scripts get their strings already translated (a data-t attribute) and fill them in. Imports nothing, so a
// browser script that needs it doesn't pull every language's table in with it.
export function fill(text, vars) {
  return String(text).replace(/\{(\w+)\}/g, (m, k) => (vars && k in vars ? String(vars[k]) : m));
}
