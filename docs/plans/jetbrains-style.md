# Plan: Apply the jetbrains.com visual style to the UI

Source: given by the user directly (no Trello card). Branch: `feature/jetbrains-style`. Plan file: `docs/plans/jetbrains-style.md`.

## Goal
Restyle the frontend in a dark-only, jetbrains.com-like look: near-black page, white text, purple accent, pill buttons, Inter for UI text and JetBrains Mono for amounts, both self-hosted with `@fontsource`. Only `frontend/src/index.css`, `frontend/src/App.css`, `frontend/package.json` and `frontend/package-lock.json` change. No `.tsx`/`.ts` file changes, and no className is needed.

## Acceptance criteria
- [ ] 1. Restyled: page tabs, primary and secondary buttons, text inputs, date inputs, select, checkboxes, tables, pager, error messages and hints.
- [ ] 2. A report row over its limit (`tr.over-limit`) is still clearly highlighted.
- [ ] 3. Keyboard focus is visible on every interactive element; text contrast meets WCAG AA.
- [ ] 4. The layout does not break at a viewport width of 375px.
- [ ] 5. No JetBrains logos or trademarks are used.
- [ ] 6. Markup and behaviour do not change: changes are in CSS (plus className where needed).
- [ ] 7. `npm test`, `npm run build` and `npm run lint` pass.

### User decisions (implemented as given)
- **D1. Dark only.** `color-scheme: dark`, no `prefers-color-scheme` branch and no light theme.
- **D2. Fonts.** Self-hosted through `@fontsource`: Inter for UI text and JetBrains Mono for amounts. No CDN, and no "JetBrains Sans" anywhere, not even as a name in the font stack.

### Decisions taken at plan approval (2026-10-02)
- **Q1 confirmed:** `@fontsource-variable/inter` and `@fontsource-variable/jetbrains-mono`, latest 5.x, all subsets through `index.css`.
- **Q2: CSS only.** Tables get `display: block; overflow-x: auto` at ≤1000px; no wrapper element.
- **Q5: "Set limit" is secondary inside the report table.** Only "Add expense" and "Save changes" are primary. See the `.report-table button[type='submit']` rows in section 3.
- **Q3, Q4, Q6:** the proposals are accepted as written.
- **Tabs:** all tabs use the same font weight (600), so switching tabs does not change their widths. The active tab is shown by white text and the accent bar.

### Changes after code review (2026-10-02, approved by the user)
- `App.css` uses `var(--weight-semibold)` instead of the literal `600`.
- Forced-colors mode (Windows High Contrast): `@media (forced-colors: active)` underlines the active tab, because forced colors drop `box-shadow`, and restores `appearance: auto` on `select`, because the gradient chevron is dropped there too.
- The header comment in `index.css` says that no jetbrains.com CSS or images are copied, and that Inter and JetBrains Mono (OFL-1.1) come from `@fontsource`.
- `.app { text-align: left }` is removed: it only undid the removed `#root { text-align: center }`.
- `overflow-wrap: anywhere` on `.quick-templates button` and `.filters fieldset label`, so a long unbroken template or category name cannot widen the page at 375px.
- Style nits: the long `--color-text-muted` comment is wrapped, and comments end with a period.

### How the criteria are interpreted
- **AC1:** every listed element gets a rule that uses the tokens below (see the mapping table in Changes, section 3).
- **AC2:** the existing `over-limit` class gets three cues:
  - a danger tint on the row;
  - a 4px danger bar on the row header cell;
  - white row text.
  The minus sign stays the cue that does not rely on colour.
- **AC3 focus:** one global `:focus-visible` ring that reaches every focusable element. No `outline: none` anywhere.
- **AC3 contrast:** text is held to 4.5:1 (WCAG 1.4.3). The focus ring, input borders, the active-tab bar and the over-limit bar are also held to 3:1 (1.4.11), although the criterion only names text.
- **AC4:** at 375px the document never scrolls sideways. Wide data tables scroll inside themselves (WCAG 1.4.10 allows this for data tables). Everything else wraps.
- **AC5:** no logo, icon or image is added, and nothing from jetbrains.com is copied except the token values given in the task. The font family name "JetBrains Mono" appears only in CSS (R4).
- **AC6:** no `.tsx`/`.ts` file changes. Every element can be targeted with existing classes, attributes or structure, so no className is added. The font import goes into CSS (`@import`), not `main.tsx`.

## Changes

### Existing state (verified in the code)
- **`src/index.css`** is pure Vite template:
  - `color-scheme: light dark` plus a `prefers-color-scheme: dark` block;
  - `#root` 1126px wide with `text-align: center` and side borders;
  - `#social .button-icon` and two `code` rules. There is no `<code>` and no `#social` in `src`;
  - `h1` 56px (36px under 1024px), `h2` 24px, and the custom properties `--text`, `--accent`, etc.

  A grep shows that none of its custom properties is used by `App.css` or any TSX.
- **`src/App.css`** hard-codes colours (`#d33`, `#333`, `rgba(221,51,51,.15)`), and `.hint` uses `opacity: 0.6`.
- **Load order:** `main.tsx` imports `index.css`, and then `App.tsx` imports `App.css`. So in the bundle `App.css` comes after `index.css`, and on equal specificity `App.css` wins.
- **Buttons:**
  - Only three are `type="submit"`: "Add expense" and "Save changes" (`ExpenseForm`), and "Set limit" (`LimitForm`).
  - All others are `type="button"`: the tabs, Cancel, Clear filters, Edit, Delete, the pager's Previous/Next, Previous month/Next month, Clear limit and the quick-template buttons.
- **Edit/Delete:** `ExpenseTable` renders the two buttons with no whitespace between them (JSX on separate lines), so today they touch.
- **The expense form:** its `<form>` has no className, but it is the only `<form>` that is a direct child of `main.app`, because `ExpensesPage` returns a fragment. The limit forms are inside table cells.
- **Controls:**
  - `input[type=text]`: Amount and the limit inputs (both `inputMode="decimal"`), and Note;
  - `input[type=date]`: Date, From and To;
  - one `select` (Category);
  - checkboxes in `.filters fieldset`.
- **Tables:**
  - `table.expense-table` has 5 columns, the last one Edit/Delete.
  - `table.report-table` has 6 columns, the last one a `form.limit-form` with an input of `width: 8rem` and two buttons.
  - Neither table fits 343px (375 minus the `.app` padding).
- **Vitest:** there is no `css` option, so CSS files are not processed in tests. No test imports `main.tsx`, and no test uses `toBeVisible`, `toHaveStyle` or `getComputedStyle` (grep).
- **TypeScript 6.0.3:**
  - `noUncheckedSideEffectImports` defaults to true (`typescript.js`: `compilerOptions.noUncheckedSideEffectImports !== false`).
  - `vite/client` only declares `'*.css'`.
  - So a bare `import '@fontsource-variable/inter'` in TS would fail `tsc -b` with TS2882. That is why the fonts are imported from CSS.
- **Unchanged:** `public/favicon.svg` is the Vite logo, not JetBrains, so it stays. `nginx.conf` already caches `/assets/` for one year, where Vite will emit the font files.

### 1. `frontend/package.json` and `frontend/package-lock.json` (Q1 confirmed by the user)
`cd frontend && npm view @fontsource-variable/inter version && npm view @fontsource-variable/jetbrains-mono version`, then `npm install @fontsource-variable/inter@^<latest 5.x> @fontsource-variable/jetbrains-mono@^<latest 5.x>`. Both go into `dependencies` next to react: they are runtime assets bundled into `dist`.

**`@fontsource-variable/inter`, `^5.2.8`**
- **Version:** 5.2.8 is the latest 5.x I know of. Use whatever newer 5.x `npm view` reports. A 6.x major needs a re-check of the family name and the file layout first.
- **What it contains:**
  - Inter v4 as a variable font (wght 100–900), family name `'Inter Variable'`;
  - `index.css` with one `@font-face` per subset: latin, latin-ext, cyrillic, cyrillic-ext, greek, greek-ext, vietnamese. Each has a `unicode-range` and `font-display: swap`;
  - licences: OFL-1.1 for the font, MIT for the package.
- **Weights used:** 400 for text, buttons and inputs. 600 for headings, labels, the legend, table headers, the active tab and `strong`.
- **Variable vs static:** variable needs one file per subset for both weights. Static `@fontsource/inter` would need `400.css` + `600.css`, so twice the requests, for about the same bytes. Fontsource recommends variable, and every target browser supports it.
- **Subsets:** all of them, through `index.css`. Category names and notes are user data and may be Cyrillic. The browser downloads only the subsets whose `unicode-range` matches characters on the page, so an English-only page fetches only the latin file.
- **Why it is needed:** D2.
- **Cost by hand:**
  - download the woff2 files from the rsms/inter release, commit binaries to the repo and write the `@font-face` rules;
  - without subsetting, every visit downloads the full file of about 300 KB or more;
  - with subsetting, fonttools/pyftsubset (Python) plus 7 `@font-face` rules with `unicode-range`;
  - updates and the licence file stay manual.

  That is about 1–2 hours plus binaries in git, against one versioned, maintained package that npm/Trivy can scan.

**`@fontsource-variable/jetbrains-mono`, `^5.2.8`**
- **Version:** same remark as for Inter.
- **What it contains:** wght 100–800, family name `'JetBrains Mono Variable'`. Subsets: latin, latin-ext, cyrillic, cyrillic-ext, greek, vietnamese. Licence OFL-1.1.
- **Weights used:** 400 for amount cells and amount inputs, 600 for the month total.
- **Variable vs static:** variable, for symmetry with Inter and one import. Amounts contain only latin characters (digits, `.`, `-`, `%`, `—`, ISO currency codes, "No limit"), so in practice only the latin file is fetched. A static `latin-400` + `latin-600` would save no bytes on the wire.
- **Why it is needed:** D2, mono for amounts.
- **Cost by hand:** the same as for Inter, with 6 subsets.

**Lockfile:**
- The `package-lock.json` diff must add only these two packages, which have no transitive dependencies. Any other lockfile change is reported, not committed.
- `node_modules` is a Docker volume: `npm install` writes into it, and the directory itself is never deleted.

### 2. `frontend/src/index.css`: full rewrite (tokens and element base styles)
**Removed:** all Vite leftovers:
- the old custom properties;
- the `prefers-color-scheme` block;
- `#social`, `code` and `#root`. Its 1126px width, `text-align: center`, border and flex column go too; `.app` already centres and left-aligns;
- the 56px/24px heading rules.

**First lines** (font import in CSS: Vite inlines it, and TS and Vitest never see it):
```css
@import '@fontsource-variable/inter/index.css';
@import '@fontsource-variable/jetbrains-mono/index.css';
```
Fallback, only if `npm run build` cannot resolve these: delete the two lines and add `import '@fontsource-variable/inter/index.css'` and `import '@fontsource-variable/jetbrains-mono/index.css'` at the top of `main.tsx`. The `.css` suffix is required so that the import matches `vite/client`'s `'*.css'` declaration.

**Tokens** (exact values). A short CSS comment states that only the Rescui token values are reused, and that no JetBrains CSS, fonts or images are copied. Each colour gets its contrast in a comment.
```css
:root {
  --color-bg: #000;                     /* page, input background */
  --color-surface: #19191c;             /* expense form card, table header row */
  --color-grey-90: #303033;             /* row separators, tab bar line, disabled button bg/border */
  --color-grey-60: #757577;             /* input and secondary button border */
  --color-grey-40: #a3a3a4;             /* hovered borders, disabled button label */
  --color-text-strong: #fff;            /* headings, input text, button labels, over-limit row text */
  --color-text: #ffffffe6;              /* t90: body text */
  --color-text-muted: #ffffffb3;        /* t70: hints, labels, legend, inactive tabs, table header, pager text */
  --color-text-placeholder: #ffffff80;  /* t50 */
  --color-hover: #ffffff1a;             /* t10: secondary button hover */
  --color-primary: #6b57ff;             /* primary button bg, checkbox accent-color */
  --color-primary-hover: #5a1fd0;       /* primary button hover (from the site gradient) */
  --color-accent: #8473ff;              /* focus ring, active tab bar, focused input border */
  --color-danger: #ff6553;
  --color-danger-bg: #ff655333;

  --font-sans: 'Inter Variable', Inter, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  --font-mono: 'JetBrains Mono Variable', 'JetBrains Mono', SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
  --weight-semibold: 600;
  --h1-size: 43px;  --h1-line: 49px;
  --h3-size: 20px;  --h3-line: 28px;    /* used for every h2, see Q4 */
  --text-1-size: 20px; --text-1-line: 28px;
  --text-2-size: 16px; --text-2-line: 24px; --text-2-spacing: 0.0015em;
  --text-3-size: 13px; --text-3-line: 20px; --text-3-spacing: 0.0045em;

  --radius-card: 8px;                   /* form card, inputs, select, error callout */
  --radius-button-m: 20px;
  --radius-button-s: 16px;

  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-6: 24px; --space-8: 32px; --space-12: 48px;

  color-scheme: dark;
  font-family: var(--font-sans);
  font-size: var(--text-2-size); line-height: var(--text-2-line); letter-spacing: var(--text-2-spacing);
  color: var(--color-text);
  background: var(--color-bg);
  font-synthesis: none; text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale;
}
@media (max-width: 640px) { :root { --h1-size: 35px; --h1-line: 39px; } }
```
Top-level `@media` is used, not nesting. Breakpoints are literals, because `var()` does not work in media queries: 640px for mobile and 1000px for table scrolling.

**Element base rules** (in this order, because equal specificity is resolved by order):
1. **Reset and text:**
   - `*, *::before, *::after { box-sizing: border-box }`;
   - `body { margin: 0; overflow-wrap: break-word }`;
   - `p { margin: 0 }`, which is kept.
2. **Headings and `strong`:**
   - `h1, h2`: `color: var(--color-text-strong)` and `font-weight: var(--weight-semibold)`;
   - `h1`: `--h1-size`/`--h1-line`, `margin: 0 0 var(--space-6)`;
   - `h2`: `--h3-size`/`--h3-line`, `margin: 0 0 var(--space-4)`;
   - `strong`: weight 600 and `--color-text-strong`.
3. **Focus:** `:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px }`. This is the only outline rule, and nothing removes outlines. Inputs add a border and shadow on `:focus-visible` on top of it (rule 5).
4. **Font inheritance:** `input, select, button { font: inherit; letter-spacing: inherit; color: inherit }`.
5. **Text inputs, date inputs and select** (`input[type='text'], input[type='date'], select`):
   - `padding: 7px` (the reference 8px minus a 1px border), `line-height: 24px`, so the height is 40px;
   - `background-color: var(--color-bg)`. Use `background-color`, not the `background` shorthand, so the select's chevron image survives;
   - `color: var(--color-text-strong)`, `border: 1px solid var(--color-grey-60)` and `border-radius: var(--radius-card)`;
   - `:hover`: `border-color: var(--color-grey-40)`;
   - `[aria-invalid='true']`: `border-color: var(--color-danger)`;
   - then `:focus-visible`: `border-color: var(--color-accent)` and `box-shadow: inset 0 0 0 1px var(--color-accent)`. That looks like the reference's 2px focused border with no layout shift, and it comes on top of the global ring.
   - Deliberate: a focused invalid field shows the accent border, not the danger border. The `.field-error` text below it still carries the error.
6. **Placeholder:** `::placeholder { color: var(--color-text-placeholder); opacity: 1 }`. Firefox dims placeholders by default.
7. **Amount inputs:** `input[inputmode='decimal'] { font-family: var(--font-mono) }`. This covers the expense Amount input and the limit inputs.
8. **Select:**
   - `appearance: none` and `padding-right: 32px`;
   - a chevron drawn in CSS only, with no image asset: `background-image: linear-gradient(45deg, transparent 50%, currentColor 50%), linear-gradient(135deg, currentColor 50%, transparent 50%)`, `background-position: calc(100% - 17px) 50%, calc(100% - 12px) 50%`, `background-size: 5px 5px`, `background-repeat: no-repeat`.
   - Keyboard and option behaviour stay native, and the option list is dark through `color-scheme`.
9. **Date inputs:** no `appearance` change, so the native picker stays. Its icon becomes light through `color-scheme: dark`.
10. **Checkboxes:** `input[type='checkbox'] { width: 18px; height: 18px; margin: 0; accent-color: var(--color-primary) }`.
11. **Buttons, secondary by default** (`button`):
    - `padding: 7px 23px` (size M, 40px high), `border: 1px solid var(--color-grey-60)`, `border-radius: var(--radius-button-m)`;
    - `background: transparent`, `color: var(--color-text-strong)`;
    - `--text-2` size/line and `cursor: pointer`.
12. **Secondary hover:** `button:hover:not(:disabled) { background: var(--color-hover); border-color: var(--color-grey-40) }`.
13. **Primary:** `button[type='submit'] { background: var(--color-primary); border-color: var(--color-primary) }`.
14. **Primary hover:** `button[type='submit']:hover:not(:disabled)` sets background and border to `var(--color-primary-hover)`. Its specificity (0,3,1) beats rule 12.
15. **Disabled:** `button:disabled { background: var(--color-grey-90); border-color: var(--color-grey-90); color: var(--color-grey-40); cursor: not-allowed }`. It must come after rule 13 because both have specificity (0,1,1).

Labels and legends use `--text-3`, weight 600 and `--color-text-muted`. Those rules go in `App.css` with `.field label`; see section 3.

### 3. `frontend/src/App.css`: rewrite of every rule to use the tokens (same selectors, plus new rules where listed)

| Element (AC1) | Selector | Values |
|---|---|---|
| Container | `.app` | `max-width: 60rem; margin: 0 auto; padding: var(--space-12) var(--space-4)`. At ≤640px: `padding: var(--space-8) var(--space-4)` |
| **Page tabs** | `.tabs` | `display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-8); border-bottom: 1px solid var(--color-grey-90)` |
| | `.tabs button` | `padding: var(--space-2) var(--space-4); border: 0; border-radius: 0; background: transparent; color: var(--color-text-muted); font-weight: var(--weight-semibold)`. Every tab has the same weight, so tab widths never change |
| | `.tabs button:hover` | `background: transparent; color: var(--color-text-strong)`. Same specificity as the base hover; wins because `App.css` loads later |
| | `.tabs button[aria-current='page']` | `color: var(--color-text-strong); box-shadow: inset 0 -2px 0 var(--color-accent)`. Replaces bold plus underline; the bar is the cue that does not rely on colour |
| **Primary buttons** | `button[type='submit']` (index.css) | "Add expense", "Save changes" |
| **Secondary buttons** | `button` (index.css) | All `type="button"` buttons except the tabs, plus "Set limit" (next two rows) |
| "Set limit" as secondary (Q5) | `.report-table button[type='submit']:not(:disabled)` | `background: transparent; border-color: var(--color-grey-60)`. Specificity (0,3,1) beats primary rule 13 (0,1,1) and ties with primary hover rule 14 (0,3,1); `App.css` loads later, so it wins. `:not(:disabled)` keeps the disabled style of rule 15 |
| | `.report-table button[type='submit']:hover:not(:disabled)` | `background: var(--color-hover); border-color: var(--color-grey-40)`, the secondary hover. Specificity (0,4,1) beats the row above |
| Size S buttons | `.expense-table button, .report-table button, .pager button, .quick-templates button` | `padding: 5px 15px; border-radius: var(--radius-button-s)`, `--text-3` size/line/spacing, 32px high |
| Edit/Delete gap | `.expense-table td > button + button` | `margin-left: var(--space-2)`. The actions cell gets `.expense-table td:last-child { white-space: nowrap }` |
| Form card | `.app > form` (the expense form only) | `background: var(--color-surface); border-radius: var(--radius-card); padding: var(--space-6); margin-bottom: var(--space-12)`. At ≤640px: `padding: var(--space-4)` |
| | `.app > form :is(input[type='text'], input[type='date'], select)` | `width: 100%; max-width: 24rem` |
| **Labels and legend** | `.field label, .filters legend` | `display: block; margin-bottom: var(--space-1)`, `--text-3`, `font-weight: var(--weight-semibold); color: var(--color-text-muted)`. The legend gets `padding: 0` |
| Field spacing | `.field` | `margin-bottom: var(--space-4)` |
| | `.form-actions` | `display: flex; flex-wrap: wrap; gap: var(--space-2)`. Adds `flex-wrap` |
| **Hints** | `.hint` | `color: var(--color-text-muted)`, `--text-3`. Replaces the opacity |
| **Error messages** | `.error` (page, form and row alerts) | Callout: `margin-block: var(--space-2); padding: var(--space-2) var(--space-3); border: 1px solid var(--color-danger); border-radius: var(--radius-card); background: var(--color-danger-bg); color: var(--color-text-strong)` |
| | `.field-error` | `margin-top: var(--space-1); color: var(--color-danger)`, `--text-3` |
| Status | `p[role='status']:empty` | Kept as is |
| **Filters and checkboxes** | `.filters` | Keeps `flex-wrap`; `gap: var(--space-4); margin-bottom: var(--space-4)` |
| | `.filters fieldset` | `border: none; padding: 0; margin: 0; min-width: 0`. The `min-width: 0` stops the fieldset's min-content overflow |
| | `.filters fieldset label` | `display: inline-flex; align-items: center; gap: var(--space-2); margin-right: var(--space-4)` |
| Quick templates | `.quick-templates` | Unchanged, apart from tokens for the gap and margin |
| **Tables** | `.expense-table, .report-table` | `width: 100%; border-collapse: collapse; margin-block: var(--space-4)` |
| | the existing `th`/`td` rule for both tables | `padding: var(--space-2) var(--space-3); border-bottom: 1px solid var(--color-grey-90); text-align: left; vertical-align: middle` |
| | `.expense-table thead th, .report-table thead th` | `background: var(--color-surface); color: var(--color-text-muted)`, `--text-3`, weight `var(--weight-semibold)` |
| | `.report-table tbody th` | `color: var(--color-text-strong); font-weight: var(--weight-semibold)` |
| Amounts | `td.amount` | Keeps right alignment, `tabular-nums` and `nowrap`; adds `font-family: var(--font-mono)` |
| | `.month-total` / `.month-total strong` | `--text-1` size/line, `margin-block: var(--space-4)` / `font-family: var(--font-mono)` |
| **Over-limit (AC2)** | `.report-table tr.over-limit th, .report-table tr.over-limit td` | `background: var(--color-danger-bg); color: var(--color-text-strong)` |
| | `.report-table tr.over-limit th` | `box-shadow: inset 4px 0 0 var(--color-danger)`, a bar on the row header |
| **Pager** | `.pager` | `display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); color: var(--color-text-muted)`, `--text-3`. Buttons are size S |
| Month switcher | `.month-switcher` | Adds `flex-wrap: wrap`; `gap: var(--space-3); margin-block: var(--space-4)`. `.month-switcher h2 { margin: 0 }` is kept |
| Limit form | `.limit-form` | `display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2)` |
| | `.limit-form input` / `.limit-form p` | `width: 8rem; padding-block: 3px` (32px, the height of an S button) / `flex-basis: 100%` (messages on their own line) |
| **375px (AC4)** | `@media (max-width: 1000px) { .expense-table, .report-table { display: block; overflow-x: auto } }` | See below |

**How the 375px requirement is met:**
- **Tables:**
  - A `<table>` in `display: table` ignores `overflow`. With `display: block`, the table element becomes a scroll container, and its rows sit in an anonymous table box inside it.
  - `border-collapse` is inherited, so it still applies.
  - **This is CSS only, so no wrapper element is needed.** The trade-off is in Q2 and R1.
  - Below 1000px only: at full desktop width the tables keep `display: table; width: 100%`, as today. Between 641px and 1000px, a table whose content is narrow shrinks to its content width.
- **Focus ring inside the scroll box:** the ring needs 4px (2px offset plus 2px width), and the cell padding is 8px vertical and 12px horizontal, so the ring is not clipped. Keyboard users reach every column, because tabbing to a button or input in the last column scrolls it into view.
- **Everything else:**
  - `flex-wrap` on `.tabs`, `.form-actions`, `.pager` and `.month-switcher`. Today the month switcher alone needs about 384px;
  - form fields at `width: 100%` of the card;
  - `min-width: 0` on the fieldset;
  - `overflow-wrap: break-word` on body;
  - the h1 at 35px.

### 4. Not changed
- **Code:**
  - every `.tsx`/`.ts` file, including `main.tsx`, unless the fallback in section 2 is needed;
  - all tests, `setup.ts` and `fetchMock.ts`.
- **Files and config:**
  - `index.html` and `public/favicon.svg`;
  - `vite.config.ts`, tsconfig and the oxlint config;
  - `nginx.conf`, `Dockerfile`, CI, `README.md` and `CLAUDE.md`.
- **Backend:** all of it.

### Implementation order
1. Q1 is confirmed. Run `npm view` and `npm install` as in section 1, and check that the lockfile diff contains only the two packages.
2. Rewrite `index.css` (section 2), then `App.css` (section 3).
3. Run `cd frontend && npm test && npm run lint && npm run build`. All must be green.
4. Checks on the build output (see Tests).

If any existing test fails, stop and report it; do not adapt the test.

**Forbidden for subagents:**
- `./gradlew bootRun` and any connection to `postgres:5432`;
- deleting `frontend/node_modules`;
- any dependency change other than the two packages;
- editing `.tsx`/`.ts` files or tests (the only exception is the `main.tsx` fallback);
- changes under `backend/`;
- git operations.

## Tests
**No new automated test. The reasons:**
- Vitest does not process CSS: there is no `css` option, so CSS imports become empty modules.
- jsdom has no layout engine and does not resolve `var()`.
- So a unit test cannot see widths, overflow, rendered colours or focus rings. A test that repeats the hex pairs from this plan would only test itself.
- Visual or browser tests (Playwright plus API mocks) would need new dependencies and browser binaries, so they are out of scope.

**Existing tests:** `App.test.tsx`, the four `ExpensesPage.*.test.tsx` files, `MonthReportPage.report/limits.test.tsx`, `expenseFilters.test.ts`, `localDate.test.ts`, `yearMonth.test.ts`, `client.test.ts` and `fetchMock.test.ts` stay unchanged and must stay green. Because no TSX changes, they also prove that markup and behaviour are unchanged (AC6). For example, MR7, MR9 and LM3 still prove that the `over-limit` class is set.

**Checks a subagent can run** (no backend, no browser). Run them from `/workspace`, exactly as written:

- **AC7:** `npm test`, `npm run lint` and `npm run build` in `frontend/` are all green.
- **D2 / no CDN:**
  ```sh
  ls frontend/dist/assets/*.woff2            # lists the Inter and JetBrains Mono files
  grep -Ei -e 'googleapis' -e 'gstatic' -e 'https?://' frontend/dist/assets/*.css   # must print nothing
  ```
- **AC5:**
  ```sh
  grep -rli jetbrains frontend/src frontend/public frontend/index.html
  ```
  Must list only `frontend/src/index.css` (the two `@import` paths, the font-family name and the token comment), plus `frontend/src/main.tsx` only if the fallback in section 2 was used. No new image or SVG files.
- **AC3 (focus):**
  ```sh
  grep -nE -e 'outline:[[:space:]]*none' -e 'outline:[[:space:]]*0' frontend/src/*.css   # must print nothing
  ```
- **AC6:** the implementer reports the changed files: only `index.css`, `App.css`, `package.json` and `package-lock.json`. The user confirms this with `git status`.

**Contrast (AC3).** The ratios below are computed with the WCAG 2.x relative-luminance formula. Alpha colours are blended over the stated background, and a reviewer can recompute any pair. All text is at least 4.5:1, and all listed non-text pairs are at least 3:1.

| Use | Foreground | Background (effective) | Ratio |
|---|---|---|---|
| Body text, status, checkbox labels, table cells | t90 `#ffffffe6` (→ `#e6e6e6`) | `#000` | 16.83 |
| Body text in the form card | t90 | `#19191c` | 14.38 |
| Headings, input text | `#fff` | `#000` / `#19191c` | 21.00 / 17.54 |
| Hints, labels, legend, pager text, inactive tabs | t70 `#ffffffb3` (→ `#b3b3b3`) | `#000` | 10.02 |
| Labels in the form card, table header | t70 | `#19191c` | 9.09 |
| Placeholder | t50 `#ffffff80` | input bg `#000` | 5.32 |
| Field errors | `#ff6553` | `#000` / `#19191c` / over-limit row (≈`#331411`) | 7.24 / 6.05 / 5.80 |
| Error callout text | `#fff` | `#ff655333` over `#000` / over `#19191c` / over an over-limit row | 16.81 / 13.12 / 12.17 |
| Primary button label | `#fff` | `#6b57ff` / hover `#5a1fd0` | 4.74 / 8.26 |
| Secondary button label | `#fff` | transparent over `#000` / `#19191c` / over-limit row; hover t10 over `#000` | 21.00 / 17.54 / 16.81 / 17.40 |
| Disabled button label (exempt under 1.4.3, passes anyway) | `#a3a3a4` | `#303033` | 5.22 |
| Active or hovered tab | `#fff` | `#000` | 21.00 |
| Over-limit row text | `#fff` | ≈`#331411` | 16.81 |
| **Non-text:** focus ring | `#8473ff` | `#000` / `#19191c` / over-limit row | 5.88 / 4.91 / 4.71 |
| **Non-text:** active-tab bar | `#8473ff` | `#000` | 5.88 |
| **Non-text:** input and secondary button border | `#757577` | `#000` / `#19191c` / over-limit row | 4.57 / 3.82 / 3.66 |
| **Non-text:** over-limit bar, invalid border | `#ff6553` | ≈`#331411` / `#000` | 5.80 / 7.24 |
| **Non-text:** checked checkbox (`accent-color`) | `#6b57ff` | `#000` | 4.43 |

Why not `#8473ff` as the button colour: the dark-theme primary `#8473ff` with white text is only 3.57:1 and fails AA, so primary buttons use the default primary `#6b57ff` (Q3).

**Manual check for the user** (with the backend running: `./gradlew bootRun` + `npm run dev`; DevTools device toolbar at 375×667 and at desktop width):
- **AC1:** check every element in the section 3 table on both tabs, with categories, templates, more than 50 expenses (for the pager) and a validation error. Without a backend, `npm run dev` alone shows only the tabs, the form card, inputs, select, date inputs, buttons and error callouts. Tables, checkboxes, the pager and over-limit rows need data.
- **AC2:** on the Month tab, set a limit below a category's amount. The row is tinted, has the red bar, and shows a negative remaining.
- **AC3:**
  - Tab through tabs → templates → form → filters (dates, checkboxes, Clear) → Edit/Delete → pager → month buttons → limit input/Set/Clear. Every stop shows the purple ring.
  - Chrome DevTools > CSS Overview > Contrast issues should list nothing, apart from browser-drawn native parts.
- **AC4:** at 375px, on both tabs with data, `document.documentElement.scrollWidth === innerWidth` in the console. Tables scroll inside themselves.

## Risks and open questions

### Open questions
No criterion is blocked. Each question has the proposal that the plan implements.
1. **Q1. New dependencies:** `@fontsource-variable/inter` `^5.2.8` and `@fontsource-variable/jetbrains-mono` `^5.2.8`, or the latest 5.x reported by `npm view`. Justification and by-hand cost are in section 1. D2 already names @fontsource; **the user needs to confirm the exact packages (variable, all subsets) before `npm install`.**
   - Alternative: static `@fontsource/inter` and `@fontsource/jetbrains-mono` with per-weight imports (`400.css`, `600.css`).
2. **Q2. Table overflow at 375px.** **Proposal: CSS only.** `display: block; overflow-x: auto` on the two tables at ≤1000px. No wrapper is needed, so AC6 holds.
   - Alternative: a wrapper `<div className="table-scroll" role="region" aria-label=… tabIndex={0}>` around each table. That is about 4 lines in each of `ExpenseTable.tsx` and `ReportTable.tsx`, and it keeps `display: table`, so no semantics risk (R1). **But it is a markup change beyond className, so it needs the user's explicit exception to AC6.**
3. **Q3. Primary colour.** **Proposal: `#6b57ff` with a white label (4.74:1), hover `#5a1fd0`.**
   - Alternative: `#8473ff` with a black label (5.88:1). It is closer to the dark-theme token but does not look like jetbrains.com.
4. **Q4. Heading scale.** **Proposal: h1 uses the h1 token (43/49, 35/39 on mobile), and every h2 uses the h3 token (20/28).** The app's h2 are form, section and month labels.
   - Alternative: the h2 token (35/42, 28/32 on mobile), which looks very large for "New expense" or "2026-10".
5. **Q5. Which buttons are primary.** **Decided: "Set limit" is secondary inside the report table**; only Add expense and Save changes are primary. The original proposal (every `type="submit"` button primary, so a purple "Set limit" in every report row) was rejected.
   - Implemented with the two `.report-table button[type='submit']` rules in section 3.
   - Delete gets no danger style. It could be targeted with `.expense-table td > button:last-child`, but that is not asked for.
6. **Q6. Expense form as a card** (`.app > form`, surface `#19191c`). **Proposal: yes.** The selector depends on `ExpensesPage` returning a fragment.
   - Alternative: no card, in which case the form sits directly on black.

### Risks
1. **R1. `display: block` on `<table>`.** Older Safari/VoiceOver versions dropped table semantics (row and column announcements) when a table's `display` was changed. Current Chromium and Firefox keep them. This affects only viewports ≤1000px, and tests cannot see it (jsdom roles do not depend on CSS). If the user wants no risk, use the wrapper from Q2.
2. **R2. No automated visual coverage.** Styling regressions are found only by the manual check.
3. **R3. CSS `@import` of the package path** relies on Vite's CSS resolver. If the build cannot resolve it, use the `main.tsx` fallback with the `.css` suffix. A bare specifier there fails `tsc -b` with TS2882, because `noUncheckedSideEffectImports` is on by default in TS 6.
4. **R4. The family name "JetBrains Mono" contains the JetBrains name.** It is an OFL font chosen by the user (D2) and is never shown in the UI. AC5 is read as "no logos or trademarks in the UI or assets". "JetBrains Sans" is deliberately left out of the stack.
5. **R5. Contrast holds only on the listed backgrounds.** The alpha tokens (t90, t70, t50, t10, danger-bg) blend with what is under them, so a future background needs the pairs recomputed. The token comments say so.
6. **R6. Browser-drawn parts** (the unchecked checkbox, the date picker popup, the select's option list) follow `color-scheme: dark`, but their exact colours are not ours.
7. **R7. Font payload.** `dist/assets` gains about 13 woff2 files. A visit downloads only the subsets it needs. nginx caches `/assets/` for one year. With `font-display: swap`, the system font shows briefly on the first load.
8. **R8. Pixel font sizes** follow the reference. Browser zoom works (WCAG 1.4.4), but the user's default font-size setting is not followed.
9. **R9. Dev-only flash.** In `npm run dev`, the CSS is injected by JS, so there can be a brief white flash. The production build links the CSS in `<head>`.

## Out of scope
- A light theme, or following `prefers-color-scheme`.
- Any markup or behaviour change:
  - the wrapper element (unless the user chooses it in Q2) and new classNames;
  - a danger style for Delete;
  - the full ARIA tabs pattern.
- JetBrains logos, icons, illustrations, decorative gradients and backgrounds, and the "JetBrains Sans" font.
- `index.html` (title, `meta color-scheme`/`theme-color`) and the favicon.
- Visual regression or browser tests (Playwright, Storybook), and any CSS tooling (Sass, CSS modules, Tailwind).
- The backend, README, CI, Docker and nginx.
