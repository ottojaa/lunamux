/**
 * Mockup engine for the "recognise a session at a glance" directions (design only, not shipped).
 *
 * One file draws all three directions of the overview (⌘⇧A) from the exact titles, repos, lines
 * and ages of `AgentsOverviewSeed` (the seeded dev server of today's build), in both of the
 * owner's themes (Neon Cyan, Lunamux Light), collapsed and expanded, at 1280 px. The three
 * directions differ in kind, not in colour:
 *
 *  - `marks`   recognition by LOOK: a stable mark for every task or lone session.
 *  - `desk`    recognition by PLACE: a fixed slot per task, a board instead of a list.
 *  - `screens` recognition by CONTENT: a miniature of the session's last screen.
 *
 * The page for each direction (marks.html, desk.html, screens.html) loads this file and calls
 * {@link RD.render} with the direction; the query string picks `theme` (dark|light), `mode`
 * (collapsed|expanded, `surfaces` for marks only) and an optional `filter` (gray|blur) used for the
 * self-checks (recognition without colour, ◆ still the loudest mark).
 *
 * A page may set `window.RD_DATA` (`NOW`, `DAYS`, `WEEKS` and `more`, the count behind the "+ N more" line)
 * before this file loads to draw other data in the same shapes, and `window.RD_FONTS` to point at another
 * font folder. Every silhouette is a hand-assigned template, not read from a stored screen (the seeded
 * sessions have none); see {@link TEMPLATES} and {@link finishedShape}.
 *
 * The theme tokens are the toolkit's own (lunula-core `BuiltinThemes`: Neon Cyan and Lunamux
 * Light) and the `.ao-*` mapping is the one in `web/src/jsMain/resources/styles.css`.
 */
(function () {
  'use strict';

  /**
   * The two themes, as the overview's CSS variables resolve them in today's build.
   * Dark is Neon Cyan; light is Lunamux Light. `hueL` and `hueS` are the identity-colour lightness and
   * saturation tuned per theme so a mark stays quieter than the ◆.
   * @type {Object<string, Object<string, string>>}
   */
  const THEMES = {
    dark: {
      name: 'Neon Cyan', bg: '#001318', surface: '#010a0d', alt: '#04252e', line: '#0c454f',
      text: '#3df0ff', dim: '#1f8a9a', bright: '#c0fbff', accent: '#00e5ff', warn: '#ffd23f',
      error: '#ff4d8d', hueL: '66%', hueS: '52%', scrim: 'rgba(0,0,0,.45)', shadow: 'rgba(0,0,0,.55)',
      add: '#8affd6', del: '#ff4d8d',
    },
    light: {
      name: 'Lunamux Light', bg: '#eef5fb', surface: '#ffffff', alt: '#e2ecf6', line: '#ccdcec',
      text: '#29455c', dim: '#6b869e', bright: '#0d2436', accent: '#0e97c8', warn: '#c47a1e',
      error: '#d0453f', hueL: '38%', hueS: '58%', scrim: 'rgba(13,36,54,.28)', shadow: 'rgba(13,36,54,.28)',
      add: '#2a8a5a', del: '#d0453f',
    },
  };

  /**
   * Identity hues, in degrees. They leave out the three hues that already mean something in the
   * overview: amber/yellow (◆ needs you), red/pink (error) and cyan (the accent, "working").
   * Seven hues for about nine repositories, so a collision is certain and is shown, not hidden:
   * the monogram and the pattern are what separate two repositories that share a hue.
   * @type {number[]}
   */
  const HUES = [85, 125, 155, 225, 255, 285, 315];

  /** Minutes in each age unit the overview prints (m, h, d); read by {@link mins}. */
  const MIN = { m: 1, h: 60, d: 1440 };
  /**
   * Parse an age such as "25m", "5h" or "2d" into minutes.
   * @param {string} s the age as the overview prints it.
   * @returns {number} minutes.
   */
  function mins(s) { const n = parseFloat(s); return n * MIN[s.slice(-1)]; }

  /**
   * Build one row of the overview: a lone session, or a task with its member sessions.
   * `kind` names the shape of the session's last screen (see {@link TEMPLATES}); it stands in for
   * the lines of `lastScreen` that a real build would read.
   * @param {string} id stable id (a session id or a task id), the input of the mark.
   * @param {string} title the title the overview shows.
   * @param {string|null} repo repository folder name, or null/'~' outside a repository.
   * @param {string} age the age the overview prints.
   * @param {string} state needs | working | idle | done | stale.
   * @param {string} line the "now" line or last reply.
   * @param {string} kind silhouette template.
   * @param {Object} [o] extra: members, summary, links, label, slot.
   * @returns {Object} the unit.
   */
  function U(id, title, repo, age, state, line, kind, o) {
    return Object.assign({ id, title, repo, age, min: mins(age), state, line, kind }, o || {});
  }
  /**
   * Build one member session of a task.
   * @param {string} id member id.
   * @param {string} title title.
   * @param {string} age age.
   * @param {string} state state.
   * @param {string} line now line, may be empty.
   * @param {string} kind silhouette template.
   * @param {Object} [o] extra: label, links.
   * @returns {Object} the member.
   */
  function M(id, title, age, state, line, kind, o) {
    return Object.assign({ id, title, age, min: mins(age), state, line, kind }, o || {});
  }

  const MERGED = 'Merged; the checks pass.';

  /** The NOW band, in the order of today's build. */
  let NOW = [
    U('seed-access-control', 'Access control', 'work-app', '12m', 'needs', '', 'diff', {
      slot: 0, summary: '1 needs you · 1 working · 1 in review', live: [0, 1],
      members: [
        M('acc-role', 'Role model', '12m', 'needs', 'Should admins inherit viewer rights?', 'question', { links: ['ACC-12'] }),
        M('acc-audit', 'Login audit trail', '3m', 'working', 'Writing the audit log migration', 'diff'),
        M('acc-perm', 'Permissions page', '40m', 'idle', '', 'prose', { label: 'in review', links: ['#212'] }),
      ] }),
    U('seed-nightly-sync', 'Nightly sync', 'ops', '2h', 'stale', '', 'shell', {
      slot: 1, summary: '1 stale', live: [0],
      members: [M('sync-worker', 'Sync worker', '2h', 'stale', 'stale 25m · Copying the ledger', 'shell')] }),
    U('seed-billing-cleanup', 'Billing cleanup', 'billing', '2h', 'idle', '', 'prose', {
      slot: 2, summary: '1 in review · 1 queued', live: [],
      members: [
        M('bill-refund', 'Refund flow', '2h', 'idle', '', 'prose', { label: 'in review', links: ['#240'] }),
        M('bill-receipt', 'Receipt emails', '6h', 'idle', '', 'plan', { label: 'queued' }),
      ] }),
  ];

  /** The LAST FEW DAYS band, newest first, as in today's build. */
  let DAYS = [
    U('seed-01', 'Bump kotlinx-serialization', 'lunamux', '25m', 'done', MERGED, 'done', { slot: 24 }),
    U('seed-release-train', 'Release train', 'lunamux', '1h', 'done', '', 'done', {
      slot: 3, summary: '3 sessions', members: [
        M('rt-panel', 'Tasks panel', '1h', 'done', 'done · #231', 'done'),
        M('rt-search', 'Search by task', '3h', 'done', 'done · #232', 'done'),
        M('rt-seed', 'Seed script', '5h', 'done', 'done · #233', 'done'),
      ] }),
    U('seed-02', 'Sidebar flicker on resize', 'lunamux', '3h', 'done', MERGED, 'diff', { slot: 23 }),
    U('seed-home', 'Why did the nightly backup email nev…', '~', '3h', 'done', 'The mail relay rejected it; I resent it.', 'shell', { slot: 22 }),
    U('seed-markdown', 'Owner test follow-up', 'lunamux', '4h', 'done', '4. Tell me how it went, then decide whether the panel stays', 'prose', { slot: 21 }),
    U('seed-branch-only', 'Add the task rows to the overview and…', 'lunamux-agent-overview', '5h', 'done', 'Done; the rows keep their order.', 'diff', { slot: 18 }),
    U('seed-open-working', 'Rebuild the search index', 'billing', '5h', 'done', '❯ Rebuild the search index with the new analyser', 'test', { slot: 19 }),
    U('seed-bare-fence', 'Show the migration', 'billing', '6h', 'done', '❯ Show me the migration that adds the column', 'code', { slot: 20 }),
    U('seed-run-id-title', 'Fix the chip count when two sessions…', 'lunamux', '20h', 'done', 'The count now ignores a session that has ended.', 'diff', { slot: 17 }),
    U('seed-folder-title', 'Compare the two layout directions an…', 'lunamux-agent-overview', '1d', 'done', 'Direction B trades width for a clearer first column.', 'plan', { slot: 16 }),
    U('seed-task-rate-limiter', 'Rate limiter rewrite', 'gateway', '1d', 'done', '', 'diff', {
      slot: 4, summary: '3 sessions', members: [
        M('rl-bucket', 'Token bucket', '1d', 'done', MERGED, 'diff'),
        M('rl-tests', 'Limiter tests', '1d', 'done', MERGED, 'test'),
        M('rl-docs', 'Limiter docs', '2d', 'done', MERGED, 'prose'),
      ] }),
    U('seed-task-token', 'Auth token refresh', 'gateway', '1d', 'done', '', 'diff', {
      slot: 5, summary: '2 sessions', members: [
        M('tok-refresh', 'Refresh endpoint', '1d', 'done', MERGED, 'diff'),
        M('tok-docs', 'Refresh docs', '10d', 'done', MERGED, 'prose'),
      ] }),
    U('seed-03', 'Tidy the release notes script', 'release-tools', '2d', 'done', MERGED, 'shell', { slot: 14 }),
    U('seed-open-idle', 'Trace the slow login', 'gateway', '2d', 'done', 'A missing index on sessions.user_id; I added it.', 'read', { slot: 13 }),
    U('seed-release-42', 'Release 4.2', 'lunamux', '2d', 'done', '', 'done', {
      slot: 12, summary: '2 sessions', members: [
        M('r42-branch', 'Cut the branch', '2d', 'done', 'done · #201', 'done'),
        M('r42-notes', 'Write the notes', '2d', 'done', 'done', 'prose'),
      ] }),
  ];

  /** The LAST TWO WEEKS band. */
  let WEEKS = [
    U('seed-task-checkout', 'Flaky checkout tests', 'shop', '5d', 'done', '', 'test', {
      slot: 6, summary: '2 sessions', members: [
        M('chk-find', 'Find the flaky one', '5d', 'done', MERGED, 'test'),
        M('chk-race', 'Fix the race', '6d', 'done', MERGED, 'diff'),
      ] }),
    U('seed-04', 'Spike: websocket backpressure', 'lunamux', '6d', 'done', MERGED, 'test', { slot: 7 }),
    U('seed-05', 'Review of the permissions page', 'work-app', '6d', 'done', MERGED, 'prose', { slot: 9 }),
    U('seed-docs-sweep', 'Docs sweep', 'lunamux', '9d', 'done', '', 'plan', {
      slot: 10, summary: '2 sessions', members: [
        M('docs-links', 'Fix the broken links', '9d', 'done', 'done · #190', 'read'),
        M('docs-shots', 'Update screenshots', '9d', 'done', 'done', 'plan'),
      ] }),
    U('seed-task-emails', 'Onboarding emails', 'shop', '11d', 'done', '', 'plan', {
      slot: 11, summary: '3 sessions', members: [
        M('em-welcome', 'Welcome email', '11d', 'done', MERGED, 'prose'),
        M('em-remind', 'Reminder email', '12d', 'done', MERGED, 'prose'),
        M('em-unsub', 'Unsubscribe link', '13d', 'done', MERGED, 'diff'),
      ] }),
  ];

  /**
   * Deterministic 32-bit FNV-1a hash.
   * Called for every cue that must be the same on every opening of the overview: the mark's
   * pattern, a repository's hue, a silhouette's jitter. Always fed a stable id, never a list index.
   * @param {string} s the input (a session id, a task id or a repository name).
   * @returns {number} an unsigned 32-bit hash.
   */
  function hash32(s) {
    let h = 0x811c9dc5;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
    return h >>> 0;
  }

  /**
   * Seeded pseudo-random generator (mulberry32).
   * @param {number} seed 32-bit seed.
   * @returns {function(number=): number} `r(n)` gives an integer in [0, n), `r()` a float in [0, 1).
   */
  function prng(seed) {
    let a = seed >>> 0;
    return function (n) {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      const f = ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      return n ? Math.floor(f * n) : f;
    };
  }

  /**
   * The identity hue of a repository: a stable pick from {@link HUES}, or null outside a repository.
   * @param {string|null} repo repository folder name.
   * @returns {number|null} degrees, or null for none.
   */
  function hueFor(repo) {
    if (!repo || repo === '~') return null;
    return HUES[hash32(repo) % HUES.length];
  }
  /**
   * The two-letter monogram of a repository: the first letters of its first two words, else its
   * first two letters. It is the non-colour half of the repository cue.
   * @param {string|null} repo repository folder name.
   * @returns {string} two characters, or "~".
   */
  function monogram(repo) {
    if (!repo || repo === '~') return '~';
    const p = repo.split('-');
    return (p.length > 1 ? p[0][0] + p[1][0] : repo.slice(0, 2)).toLowerCase();
  }

  /**
   * A CSS colour for a hue in the active theme.
   * @param {number|null} h hue in degrees, null for neutral.
   * @param {Object} T the theme.
   * @param {number} [a] alpha 0..1.
   * @returns {string} a CSS colour.
   */
  function hueCss(h, T, a) {
    if (h == null) return a == null ? T.dim : `color-mix(in srgb, ${T.dim} ${Math.round(a * 100)}%, transparent)`;
    return `hsl(${h} ${T.hueS} ${T.hueL} / ${a == null ? 1 : a})`;
  }

  /**
   * The mark of a task or lone session: a mirrored 5×5 pattern drawn from its id, on a plate
   * tinted by its repository's hue. The pattern is the identity (about 23 000 distinct ones: 15 bits,
   * at least 7 set); the hue says "where" and repeats in the repository word; neither depends on list
   * order. Called by the marks direction for every row, and by its "surfaces" view for the sidebar,
   * the pane header, the chip and the search results. The id is the task's when the session belongs
   * to one and its own otherwise, so a session wears the same mark on every surface.
   * @param {string} id the task id, or the session id for a session outside any task.
   * @param {string|null} repo the repository, for the tint.
   * @param {number} px the size in pixels.
   * @param {Object} T the theme.
   * @param {Object} [o] `stack` draws two offset edges behind it (a task of several sessions);
   *   `fade` is the freshness opacity.
   * @returns {string} inline SVG.
   */
  function markSvg(id, repo, px, T, o) {
    o = o || {};
    let salt = 0, bits;
    do { bits = hash32(id + '#' + salt++) & 0x7fff; } while (popcount(bits) < 7);
    const h = hueFor(repo);
    const fg = hueCss(h, T, 1);
    const plate = hueCss(h, T, 0.16);
    const edge = hueCss(h, T, 0.42);
    let cells = '';
    for (let y = 0; y < 5; y++) for (let x = 0; x < 3; x++) {
      if (bits & (1 << (y * 3 + x))) {
        cells += `<rect x="${x + .5}" y="${y + .5}" width="1" height="1"/>`;
        if (x < 2) cells += `<rect x="${4 - x + .5}" y="${y + .5}" width="1" height="1"/>`;
      }
    }
    const stack = o.stack
      ? `<rect x="1.3" y="1.3" width="6.2" height="6.2" rx=".7" fill="none" stroke="${edge}" stroke-width=".28"/>` +
        `<rect x=".65" y=".65" width="6.2" height="6.2" rx=".7" fill="${plate}" stroke="${edge}" stroke-width=".28"/>`
      : '';
    return `<svg class="mk" width="${px}" height="${px}" viewBox="-.5 -.5 ${o.stack ? 8.2 : 6.4} ${o.stack ? 8.2 : 6.4}" style="opacity:${o.fade == null ? 1 : o.fade}" shape-rendering="crispEdges">` +
      stack + `<rect x="-.2" y="-.2" width="5.4" height="5.4" rx=".6" fill="${plate}" stroke="${edge}" stroke-width=".28"/>` +
      `<g fill="${fg}">${cells}</g></svg>`;
  }
  /**
   * Count the set bits of an integer.
   * @param {number} n the integer.
   * @returns {number} the number of 1 bits.
   */
  function popcount(n) { let c = 0; while (n) { c += n & 1; n >>>= 1; } return c; }

  /**
   * How fresh an age is, as 0..1, on a log scale: 1 for "now", about 0.1 for two weeks.
   * Time seen instead of read: the shared, free layer every direction may use (it needs only the
   * timestamps the overview already has). It fades marks and bars; the age text stays on the row.
   * @param {number} min age in minutes.
   * @returns {number} freshness 0.08..1.
   */
  function fresh(min) { return Math.max(0.08, Math.min(1, (100 - 28 * Math.log10(1 + min / 10)) / 100)); }
  /**
   * An opacity for a mark or silhouette from its freshness, never below legibility.
   * @param {number} min age in minutes.
   * @returns {number} opacity 0.4..1.
   */
  function fadeOf(min) { return 0.4 + 0.6 * fresh(min); }

  /**
   * The glyph for a state. The shape differs per state, so colour is never the only cue.
   * @param {string} s needs | working | idle | done | stale.
   * @returns {string} HTML for the glyph cell.
   */
  function glyph(s) {
    switch (s) {
      case 'needs': return '<span class="g-need">◆</span>';
      case 'working': return '<span class="g-work">⠶</span>';
      case 'stale': return '<span class="g-stale">⠶</span>';
      case 'done': return '<span class="g-dim">·</span>';
      default: return '<span class="g-dim">○</span>';
    }
  }

  /**
   * The state of a unit's most urgent member, for a task header's glyph.
   * @param {Object} u the unit.
   * @returns {string} a state.
   */
  function topState(u) {
    if (!u.members) return u.state;
    const order = ['needs', 'working', 'stale', 'idle', 'done'];
    return u.members.map(m => m.state).sort((a, b) => order.indexOf(a) - order.indexOf(b))[0];
  }

  /**
   * Silhouette templates: eight lines of a session's last screen as `[indent, length, kind]`,
   * in 40-column units. Every one is derivable from the plain text of a screen (line length,
   * indent, a leading + or −, box-drawing characters, the ❯ prompt and ⏺ tool bullets), which is
   * what `lastScreen` holds and what the peek already reads; colour is optional extra.
   * @type {Object<string, function(function(number=): number): Array<Array>>}
   */
  const TEMPLATES = {
    diff: r => [[0, 10 + r(8), 'tool'], [2, 16 + r(14), 'dim'], [2, 8 + r(22), 'add'], [2, 10 + r(22), 'add'], [2, 6 + r(20), 'del'], [2, 12 + r(18), 'add'], [0, 14 + r(12), 'prose'], [0, 40, 'box']],
    test: r => [[0, 12 + r(6), 'tool'], [2, 30 + r(8), 'dim'], [2, 26 + r(12), 'dim'], [2, 32 + r(6), 'dim'], [2, 22 + r(14), 'dim'], [0, 8 + r(6), 'ok'], [0, 18 + r(10), 'prose'], [0, 40, 'box']],
    prose: r => [[0, 30 + r(10), 'prose'], [0, 34 + r(6), 'prose'], [0, 26 + r(14), 'prose'], [0, 6 + r(10), 'prose'], [0, 32 + r(8), 'prose'], [0, 22 + r(16), 'prose'], [0, 10 + r(8), 'prose'], [0, 40, 'box']],
    plan: r => [[0, 14 + r(8), 'prose'], [2, 24 + r(12), 'bullet'], [2, 18 + r(16), 'bullet'], [2, 26 + r(10), 'bullet'], [2, 14 + r(18), 'bullet'], [0, 10 + r(8), 'prose'], [0, 6, 'gap'], [0, 40, 'box']],
    shell: r => [[0, 6 + r(6), 'prompt'], [0, 20 + r(18), 'dim'], [0, 12 + r(20), 'dim'], [0, 6 + r(6), 'prompt'], [0, 24 + r(14), 'dim'], [0, 8 + r(20), 'dim'], [0, 6 + r(8), 'prompt'], [0, 40, 'box']],
    read: r => [[0, 10 + r(6), 'tool'], [2, 20 + r(10), 'dim'], [0, 12 + r(6), 'tool'], [2, 24 + r(8), 'dim'], [0, 10 + r(8), 'tool'], [2, 18 + r(14), 'dim'], [0, 20 + r(14), 'prose'], [0, 40, 'box']],
    code: r => [[0, 12 + r(8), 'tool'], [2, 14 + r(8), 'code'], [4, 22 + r(10), 'code'], [4, 18 + r(12), 'code'], [4, 26 + r(8), 'code'], [2, 8 + r(6), 'code'], [0, 12 + r(10), 'prose'], [0, 40, 'box']],
    done: r => [[0, 18 + r(10), 'prose'], [0, 26 + r(8), 'prose'], [0, 4, 'gap'], [0, 8 + r(6), 'ok'], [0, 4, 'gap'], [0, 4, 'gap'], [0, 4, 'gap'], [0, 40, 'box']],
    // A question or permission dialog: prose above, a bordered box of options below.
    question: r => [[0, 28 + r(8), 'prose'], [0, 22 + r(10), 'prose'], [1, 36, 'frame'], [2, 24 + r(8), 'prose'], [3, 8 + r(6), 'opt'], [3, 12 + r(6), 'opt'], [1, 36, 'frame'], [0, 40, 'box']],
  };

  /**
   * The silhouette template of a finished session. A session that has ended leaves a screen that
   * is mostly its final message above the prompt box, whatever it did before, so every finished
   * session is drawn as `done` (a short message) or `prose` (a long one) and only a live session keeps
   * the template of what it is doing. This keeps the Screens direction from looking better than a real
   * `lastScreen` would make it.
   * @param {Object} u a unit or member.
   * @returns {string} a key of {@link TEMPLATES}.
   */
  function finishedShape(u) {
    if (u.state !== 'done') return u.kind;
    return u.kind === 'done' || (u.line && u.line.length < 40) ? 'done' : 'prose';
  }

  /**
   * A silhouette: the last screen of a session drawn as bars, no readable text.
   * Used by the screens direction. The structure (line lengths, indents, the dialog box, the diff
   * columns) carries the recognition; the muted colour only helps, and a grayscale render still
   * reads. It is seeded by the id, so a session that has not moved draws the same picture.
   * @param {string} id the session id (or a task's most urgent member).
   * @param {string} kind a key of {@link TEMPLATES}.
   * @param {Object} T the theme.
   * @param {Object} [o] `w` width px (default 120), `fade` opacity, `stack` for a task.
   * @returns {string} inline SVG.
   */
  function silhouette(id, kind, T, o) {
    o = o || {};
    const w = o.w || 120, h = 32, cw = w / 40;
    const r = prng(hash32(id));
    const lines = TEMPLATES[kind](r);
    const col = {
      prose: `color-mix(in srgb, ${T.text} 52%, transparent)`, dim: `color-mix(in srgb, ${T.text} 26%, transparent)`,
      tool: `color-mix(in srgb, ${T.accent} 62%, transparent)`, add: `color-mix(in srgb, ${T.add} 58%, transparent)`,
      del: `color-mix(in srgb, ${T.del} 48%, transparent)`, ok: `color-mix(in srgb, ${T.add} 62%, transparent)`,
      box: `color-mix(in srgb, ${T.text} 22%, transparent)`, bullet: `color-mix(in srgb, ${T.text} 42%, transparent)`,
      prompt: `color-mix(in srgb, ${T.accent} 70%, transparent)`, code: `color-mix(in srgb, ${T.text} 38%, transparent)`,
      frame: `color-mix(in srgb, ${T.text} 55%, transparent)`, opt: `color-mix(in srgb, ${T.text} 48%, transparent)`,
      gap: 'transparent',
    };
    let bars = '';
    lines.forEach(([ind, len, k], i) => {
      const y = i * 4 + 1;
      if (k === 'gap') return;
      if (k === 'frame') { bars += `<rect x="${ind * cw}" y="${y}" width="${len * cw}" height="1" fill="${col.frame}"/>`; return; }
      if (k === 'add' || k === 'del') bars += `<rect x="${(ind - 1.4) * cw}" y="${y - .5}" width="${.6 * cw}" height="3" fill="${col[k]}"/>`;
      if (k === 'bullet') bars += `<rect x="${(ind - 1.4) * cw}" y="${y}" width="${cw}" height="2" fill="${col.bullet}"/>`;
      if (k === 'prompt') bars += `<rect x="0" y="${y}" width="${1.4 * cw}" height="2" fill="${col.prompt}"/>`;
      const x = k === 'prompt' ? 2.2 * cw : ind * cw;
      bars += `<rect x="${x}" y="${y}" width="${Math.min(len * cw, w - x)}" height="${k === 'box' ? 1 : 2}" fill="${col[k] || col.dim}"/>`;
    });
    const stack = o.stack
      ? `<rect x="5" y="-3" width="${w}" height="${h}" fill="none" stroke="${T.line}" stroke-width="1" transform="translate(0,3)"/>` : '';
    return `<svg class="sil" width="${w + (o.stack ? 6 : 0)}" height="${h + (o.stack ? 3 : 0)}" viewBox="0 0 ${w + (o.stack ? 6 : 0)} ${h + (o.stack ? 3 : 0)}" shape-rendering="crispEdges" style="opacity:${o.fade == null ? 1 : o.fade}">` +
      stack + `<g transform="translate(0,${o.stack ? 3 : 0})"><rect width="${w}" height="${h}" fill="color-mix(in srgb, ${T.text} 5%, transparent)"/>${bars}</g></svg>`;
  }

  /**
   * Escape text for HTML.
   * @param {string} s text.
   * @returns {string} escaped text.
   */
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

  /**
   * The shared stylesheet: the `.ao-*` tokens of today's build plus the few classes the three
   * directions add. Called once per page by {@link frame}.
   * @param {Object} T the theme.
   * @returns {string} CSS text.
   */
  function css(T) {
    const FONTS = window.RD_FONTS || '../../web/src/jsMain/resources/fonts';
    return `
@font-face{font-family:'JetBrains Mono';src:url('${FONTS}/jetbrains-mono-400.woff2');font-weight:400}
@font-face{font-family:'JetBrains Mono';src:url('${FONTS}/jetbrains-mono-700.woff2');font-weight:700}
:root{--s:${T.surface};--t:${T.text};--muted:color-mix(in srgb,${T.text} 72%,${T.surface});--dim:${T.dim};
 --faint:color-mix(in srgb,${T.dim} 52%,${T.surface});--line:${T.line};--sel:color-mix(in srgb,${T.accent} 14%,${T.surface});
 --accent:${T.accent};--warn:${T.warn};--err:${T.error};--alt:${T.alt};--bright:${T.bright};
 --mono:'JetBrains Mono',ui-monospace,Menlo,monospace}
*{box-sizing:border-box}
html,body{margin:0;background:${T.bg};color:var(--t);font:13px/1.6 var(--mono)}
body{width:1280px;min-height:100vh;position:relative;overflow-x:hidden}
.page{position:relative;width:1280px}
.bar{height:44px;display:flex;align-items:center;gap:12px;padding:0 20px;color:var(--dim)}
.tabchip{border:1px solid var(--line);padding:3px 18px;border-radius:5px;background:var(--alt);color:var(--t);font-size:12px}
.chip{margin-left:auto;display:inline-flex;gap:1ch;align-items:center;height:24px;padding:0 10px;border:1px solid var(--line);border-radius:12px;font-size:12px;color:var(--dim);white-space:nowrap}
.chip .n{color:var(--warn);font-weight:700}.chip .sep{color:var(--faint)}
.ico{width:16px;height:16px;border:1.5px solid var(--faint);border-radius:4px;opacity:.7}
.app{position:absolute;left:0;right:0;top:44px;bottom:0;display:flex}
.side{width:300px;border-right:1px solid var(--line);padding:14px;color:var(--dim)}
.pane{flex:1;margin:14px;border:1.5px solid color-mix(in srgb,var(--accent) 55%,transparent);border-radius:4px}
.scrim{position:absolute;inset:0;background:${T.scrim}}
.panel{position:absolute;left:50px;top:56px;width:1180px;border:1px solid var(--line);border-radius:8px;background:var(--s);box-shadow:0 24px 64px ${T.shadow};overflow:hidden}
.search{display:flex;align-items:center;gap:1ch;height:44px;padding:0 2ch;border-bottom:1px solid var(--line);color:var(--dim)}
.search b{color:var(--accent)}
.list{padding:2px 1ch 10px}
.sec{display:flex;align-items:center;gap:1.5ch;height:30px;padding:0 1ch;font-size:11px;letter-spacing:.12em;color:var(--dim)}
.sec:after{content:"";flex:1;height:1px;background:var(--line)}
.foot{border-top:1px solid var(--line);padding:6px 2ch;color:var(--dim);font-size:11.5px;display:flex;gap:3ch}
.foot b{color:var(--t)}
.row{display:grid;align-items:center;padding:0 1ch;column-gap:1.5ch;position:relative}
.row>*{min-width:0}
.row.sel{background:var(--sel);box-shadow:inset 2px 0 0 var(--accent)}
.t,.w,.n{overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.t{color:var(--t)}.head .t{font-weight:700}
.w{color:var(--dim)}.n{color:var(--dim)}.a{text-align:right;color:var(--dim)}.x{text-align:right;color:var(--muted)}
.mem .t{color:var(--t)}
.head{margin-top:6px}.sec+.head{margin-top:0}
.past .t{color:var(--muted)}
.g-need{color:var(--warn);font-size:15px;font-weight:700}.g-work{color:var(--accent)}.g-stale{color:var(--faint)}.g-dim{color:var(--dim)}
.need .n{color:var(--warn);font-weight:700}.need .a{color:var(--t)}
.cn{color:var(--warn);font-weight:700}
.gut{color:var(--dim);text-align:center;user-select:none}
.mk{display:block}.sil{display:block}
.tree{color:var(--faint)}
.more{color:var(--dim);padding:0 1ch 0 11ch}
`;
  }

  /**
   * The window around every direction: the toolbar with its chip, the faint app behind, the scrim
   * and the panel, framed exactly like today's screenshots.
   * @param {Object} T the theme.
   * @param {string} body the panel's inner HTML.
   * @param {string} footer the footer line's HTML.
   * @param {Object} [o] `h` page height, `panelCss` extra style on the panel.
   * @returns {string} page HTML.
   */
  function frame(T, body, footer, o) {
    o = o || {};
    return `<div class="page" style="height:${o.h || 1300}px">
  <div class="bar"><span class="ico"></span><span class="ico"></span><span class="tabchip">Tab 1</span>
   <span class="chip"><span class="n">◆ 1 needs you</span><span class="sep">·</span><span>1 working</span></span>
   <span class="ico"></span><span class="ico"></span><span class="ico"></span></div>
  <div class="app"><div class="side">lunamux</div><div class="pane"></div></div>
  <div class="scrim"></div>
  <div class="panel" style="${o.panelCss || ''}">
   <div class="search"><b>❯</b><span>search every session, closed ones too</span></div>
   ${body}
   <div class="foot">${footer}</div>
  </div></div>`;
  }

  /**
   * The common footer: what ↵ does for the selected row plus the keys, as today.
   * @param {string} hint the first segment.
   * @returns {string} HTML.
   */
  const keys = hint => `<span>${hint}</span><span><b>←</b> back to the task</span><span><b>↑↓</b> move</span><span><b>esc</b> close</span>`;

  /**
   * Which rows each mode draws for a task: collapsed shows only live members (needs you, working,
   * stale), expanded shows every member, as in today's build.
   * @param {Object} u the task.
   * @param {boolean} open whether the task is expanded.
   * @returns {Object[]} the members to draw.
   */
  function shownMembers(u, open) {
    if (!u.members) return [];
    if (open) return u.members;
    return u.members.filter(m => ['needs', 'working', 'stale'].includes(m.state));
  }

  /**
   * Draw the list shared by the `marks` and `screens` directions: the NOW, LAST FEW DAYS and
   * LAST TWO WEEKS bands with today's order, header rows for tasks and their members.
   * Only the leading cell (mark or silhouette) and the row geometry differ per direction.
   * @param {Object} T the theme.
   * @param {string} mode collapsed | expanded.
   * @param {Object} d the direction: `cols` the grid template, `lead(u, member, parent)` the leading
   *   cell HTML, `rowH` the row height, `where(u)` the repository name and, optionally,
   *   `whereCell(u)` the whole repository cell.
   * @returns {string} the list's HTML.
   */
  function list(T, mode, d) {
    const expandedIds = mode === 'expanded' ? ['seed-access-control', 'seed-release-train', 'seed-task-rate-limiter'] : [];
    const selId = mode === 'expanded' ? 'rt-panel' : 'acc-role';
    const parts = [];
    /** The repository cell of a unit: the direction's own, else the plain name. */
    const wcell = u => d.whereCell ? d.whereCell(u) : `<span class="w">${esc(d.where(u))}</span>`;
    /**
     * Draw one band.
     * @param {string} label the band heading.
     * @param {Object[]} units the units in it.
     */
    const band = (label, units) => {
      parts.push(`<div class="sec">${label}</div>`);
      units.forEach(u => {
        const open = expandedIds.includes(u.id);
        if (u.members) {
          const live = u.state !== 'done' || topState(u) !== 'done';
          parts.push(`<div class="row head ${band.past ? 'past' : ''}" style="grid-template-columns:${d.cols};min-height:${d.rowH}px">
            <span class="gut">${open ? '▾' : '▸'}</span><span class="s">${glyph(live ? topState(u) : 'done')}</span>
            ${d.lead(u, null, null)}
            <span class="t">${esc(u.title)}</span>${wcell(u)}
            <span class="n">${u.summary.replace(/1 needs you/, '<span class="cn">1 needs you</span>')}</span>
            <span class="a">${u.age}</span><span class="x">${['seed-access-control', 'seed-release-train', 'seed-release-42', 'seed-docs-sweep', 'seed-billing-cleanup', 'seed-nightly-sync'].includes(u.id) ? 'open' : ''}</span></div>`);
          const ms = shownMembers(u, open);
          ms.forEach((m, i) => {
            const sel = m.id === selId;
            const last = i === ms.length - 1;
            parts.push(`<div class="row mem ${m.state === 'needs' ? 'need' : ''} ${sel ? 'sel' : ''}" style="grid-template-columns:${d.cols};min-height:${d.rowH}px">
              <span class="gut tree">${last ? '└' : '├'}</span><span class="s">${glyph(m.state)}</span>
              ${d.lead(m, m, u)}
              <span class="t">${esc(m.title)}</span><span class="w"></span>
              <span class="n">${esc(m.label ? [m.label].concat(m.links || []).join(' · ') : '')}${esc(m.line || '')}</span>
              <span class="a">${m.age}</span><span class="x"></span></div>`);
          });
        } else {
          parts.push(`<div class="row ${u.state === 'needs' ? 'need' : ''} past" style="grid-template-columns:${d.cols};min-height:${d.rowH}px">
            <span class="gut"></span><span class="s">${glyph(u.state)}</span>
            ${d.lead(u, null, null)}
            <span class="t">${esc(u.title)}</span>${wcell(u)}
            <span class="n">${esc(u.line)}</span><span class="a">${u.age}</span><span class="x"></span></div>`);
        }
      });
    };
    band.past = false;
    band('NOW', NOW);
    band.past = true;
    band('LAST FEW DAYS', DAYS);
    band('LAST TWO WEEKS', WEEKS);
    parts.push(`<div class="more">+ ${(window.RD_DATA && window.RD_DATA.more) || 2} more</div>`);
    return `<div class="list">${parts.join('')}</div>`;
  }

  /**
   * Direction A, "Marks": recognition by look.
   * Today's list with one added column: a stable mark per task or lone session (a mirrored pattern
   * from its id on a plate tinted by its repository's hue, 24 px), the repository word tinted the same
   * way, and freshness as the mark's opacity. A member session wears a small copy of its task's mark.
   * @param {Object} T the theme.
   * @param {string} mode collapsed | expanded | surfaces.
   * @returns {string} page HTML.
   */
  function renderMarks(T, mode) {
    if (mode === 'surfaces') return surfaces(T);
    const d = {
      cols: '2ch 2ch 24px 34ch 15ch minmax(0,1fr) 4ch 7ch', rowH: 34,
      /**
       * The leading cell: the unit's mark, or its task's small mark for a member.
       * @param {Object} u the unit or member.
       * @param {Object|null} m the member, if any.
       * @param {Object|null} parent the task, if a member.
       * @returns {string} HTML.
       */
      lead(u, m, parent) {
        if (m) return `<span style="display:flex;justify-content:center">${markSvg(parent.id, parent.repo, 16, T, { fade: fadeOf(m.min) })}</span>`;
        return `<span>${markSvg(u.id, u.repo, 24, T, { stack: !!u.members, fade: fadeOf(u.min) })}</span>`;
      },
      /**
       * The repository cell: the monogram and the name, tinted with the repository's hue.
       * @param {Object} u the unit.
       * @returns {string} text (the tint is applied through the `w` colour below).
       */
      where(u) { return u.repo || ''; },
      /**
       * The repository cell: monogram and name in the unit's tint (the word stays, the colour is the second cue).
       * @param {Object} u the unit.
       * @returns {string} HTML for the cell.
       */
      whereCell(u) {
        if (!u.repo) return '<span class="w"></span>';
        if (u.repo === '~') return '<span class="w">~ (home)</span>';
        return `<span class="w" style="color:${hueCss(hueFor(u.repo), T, 1)}"><b style="font-weight:700;opacity:.85">${monogram(u.repo)}</b> ${esc(u.repo)}</span>`;
      },
    };
    const html = list(T, mode, d);
    return frame(T, html, keys('<b>↵</b> jump to the pane'), { h: mode === 'expanded' ? 1620 : 1260 });
  }

  /**
   * The "surfaces" view of the marks direction: the same marks on the sidebar, on a pane header,
   * on the toolbar chip and on search results, to show the one cue that works outside the overview.
   * A session in a task wears its task's mark on every surface; a lone session wears its own.
   * @param {Object} T the theme.
   * @returns {string} page HTML.
   */
  function surfaces(T) {
    const mk = (id, repo, px) => markSvg(id, repo, px, T, {});
    const ACC = 'seed-access-control';
    // [mark id, repository, title, state]: a member session carries its task's id, a lone session its own.
    const side = [
      [ACC, 'work-app', 'Role model', 'needs'], [ACC, 'work-app', 'Login audit trail', 'working'],
      ['seed-02', 'lunamux', 'Sidebar flicker on resize', 'done'], ['seed-open-working', 'billing', 'Rebuild the search index', 'done'],
      ['seed-task-rate-limiter', 'gateway', 'Rate limiter rewrite', 'done'],
    ];
    const rows = side.map(([id, repo, title, st], i) => `<div style="display:flex;gap:10px;align-items:center;height:34px;padding:0 10px;${i === 0 ? 'background:var(--sel);box-shadow:inset 2px 0 0 var(--accent)' : ''}">${mk(id, repo, 20)}<span style="flex:1;color:var(--t);overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${title}</span>${st === 'needs' ? '<span class="g-need">◆</span>' : st === 'working' ? '<span class="g-work">⠶</span>' : ''}</div>`).join('');
    const hits = [['seed-task-invoice', 'billing', 'Duplicate header row', 'in task Invoice export migration', '30d'], ['seed-task-invoice', 'billing', 'Currency rounding', 'in task Invoice export migration', '31d']];
    const srch = hits.map(([id, repo, title, where, age]) => `<div style="display:flex;gap:10px;align-items:center;height:34px;padding:0 10px">${mk(id, repo, 20)}<span style="color:var(--t);width:260px">${title}</span><span style="color:var(--dim);flex:1">${where}</span><span style="color:var(--dim)">${age}</span></div>`).join('');
    return `<div class="page" style="height:760px">
  <div class="bar"><span class="ico"></span><span class="ico"></span><span class="tabchip">Tab 1</span>
   <span class="chip"><span style="display:inline-flex;gap:6px;align-items:center">${mk(ACC, 'work-app', 16)}<span class="n">◆ 1 needs you</span></span><span class="sep">·</span><span>1 working</span></span>
   <span class="ico"></span><span class="ico"></span></div>
  <div class="app"><div class="side" style="width:330px;padding:10px 0"><div style="padding:0 14px 8px;font-size:11px;letter-spacing:.12em">SESSIONS</div>${rows}</div>
   <div style="flex:1;margin:14px;display:flex;flex-direction:column;gap:14px">
    <div style="border:1.5px solid color-mix(in srgb,var(--accent) 55%,transparent);border-radius:4px;flex:1">
     <div style="display:flex;gap:10px;align-items:center;height:36px;padding:0 12px;border-bottom:1px solid var(--line)">${mk(ACC, 'work-app', 20)}<b style="color:var(--t)">Role model</b><span style="color:var(--dim)">work-app · Access control</span><span style="margin-left:auto" class="g-need">◆ asking</span></div>
     <div style="padding:14px 16px;color:var(--dim)">The pane's header wears the same mark as its row in the overview (the task's, as a member session) and as the sidebar row, so the pane you jump to is the one you picked. Sessions of one task look alike on purpose; their titles and glyphs tell them apart.</div></div>
    <div style="border:1px solid var(--line);border-radius:6px;background:var(--s)"><div style="padding:8px 14px;color:var(--dim);font-size:11px;letter-spacing:.12em">SEARCH "invoice": closed sessions of one task wear that task's mark</div>${srch}</div>
   </div></div></div>`;
  }

  /**
   * Direction B, "Desk": recognition by place (the radical one).
   * The overlay becomes a board of fixed slots. A task takes the first free slot when it first
   * appears and keeps it until it leaves the two-week window, so "billing is bottom left" holds
   * every time. Nothing moves when a state changes; urgency is carried by a banner and by the tile's
   * look, not by position. A task unfolds over its neighbours (they do not shift, they are covered).
   * It gives up today's bands (NOW, LAST FEW DAYS, LAST TWO WEEKS: age is a bar on the tile) and the
   * sorted order. No identity colour and no mark: place is the only added cue, on top of the title.
   * @param {Object} T the theme.
   * @param {string} mode collapsed | expanded.
   * @returns {string} page HTML.
   */
  function renderDesk(T, mode) {
    const all = [...NOW, ...DAYS, ...WEEKS];
    const bySlot = {};
    all.forEach(u => { bySlot[u.slot] = u; });
    const COLS = 6, ROWS = 5, W = (1180 - 32 - (COLS - 1) * 10) / COLS, H = 96;
    const sel = mode === 'expanded' ? 'seed-access-control' : null;
    const tiles = [];
    for (let s = 0; s < COLS * ROWS; s++) {
      const x = 16 + (s % COLS) * (W + 10), y = (Math.floor(s / COLS)) * (H + 10);
      const u = bySlot[s];
      if (!u) {
        if (s <= 25) tiles.push(`<div style="position:absolute;left:${x}px;top:${y}px;width:${W}px;height:${H}px;border:1px dashed color-mix(in srgb,var(--line) 70%,transparent);border-radius:6px"></div>`);
        continue;
      }
      const st = topState(u);
      const live = st === 'working';
      const dimTile = st === 'done' || st === 'idle';
      const f = fresh(u.min);
      const need = st === 'needs';
      const border = need ? `border:1.5px solid var(--warn);background:color-mix(in srgb,var(--warn) 10%,var(--s))`
        : live ? `border:1px solid color-mix(in srgb,var(--accent) 55%,var(--line));background:var(--sel)`
        : `border:1px solid var(--line);background:var(--s)`;
      const line = u.members ? (u.summary || '') : (u.line || '');
      const barColor = need ? 'var(--warn)' : live ? 'var(--accent)' : 'var(--dim)';
      tiles.push(`<div style="position:absolute;left:${x}px;top:${y}px;width:${W}px;height:${H}px;${border};border-radius:6px;padding:8px 10px;overflow:hidden;${u.id === sel ? 'outline:2px solid var(--accent);outline-offset:2px' : ''}">
        <div style="display:flex;gap:6px;align-items:flex-start"><span style="width:14px;text-align:center;padding-top:1px">${glyph(st)}</span>
        <span style="flex:1;font-weight:700;line-height:1.25;color:${dimTile ? 'var(--muted)' : 'var(--t)'};display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:33px">${esc(u.title)}</span>
        ${u.members ? `<span style="color:var(--dim);font-size:11px;border:1px solid var(--line);border-radius:3px;padding:0 4px">${u.members.length}</span>` : ''}</div>
        <div style="margin-top:4px;color:var(--dim);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(u.repo || '~')}</div>
        <div style="color:${need ? 'var(--warn)' : 'var(--dim)'};font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(line.replace(/^1 needs you · /, ''))}</div>
        <div style="position:absolute;left:0;bottom:0;height:3px;width:${Math.round(f * 100)}%;background:${barColor};opacity:${live ? 1 : .55}"></div></div>`);
    }
    let card = '';
    if (mode === 'expanded') {
      const u = NOW[0], x = 16, y = 0, cw = W * 3 + 20;
      const rows = u.members.map((m, i) => `<div class="row ${m.state === 'needs' ? 'need' : ''} ${i === 0 ? 'sel' : ''}" style="grid-template-columns:2ch 30ch minmax(0,1fr) 5ch;min-height:30px"><span class="s">${glyph(m.state)}</span><span class="t" style="font-weight:700">${esc(m.title)}</span><span class="n">${esc(m.label ? [m.label].concat(m.links || []).join(' · ') : m.line)}</span><span class="a">${m.age}</span></div>`).join('');
      card = `<div style="position:absolute;left:${x - 6}px;top:${y - 6}px;width:${cw + 12}px;background:var(--s);border:1.5px solid var(--warn);border-radius:8px;box-shadow:0 18px 44px ${T.shadow};padding:10px 6px 8px;z-index:5">
        <div style="display:flex;gap:1.5ch;padding:0 1ch 6px;align-items:baseline"><b style="color:var(--t)">Access control</b><span style="color:var(--dim)">work-app</span><span class="n" style="margin-left:auto;color:var(--dim)"><span class="cn">1 needs you</span> · 1 working · 1 in review</span></div>${rows}
        <div style="padding:6px 1ch 0;color:var(--dim);font-size:11.5px">opened over its neighbours (it hides three tiles and part of the next row while open): nothing on the board moved</div></div>`;
    }
    const banner = `<div style="margin:12px 16px 14px;border:1.5px solid var(--warn);border-radius:6px;background:color-mix(in srgb,var(--warn) 12%,var(--s));display:flex;gap:1.5ch;align-items:center;height:42px;padding:0 14px">
      <span class="g-need" style="font-size:20px">◆</span><b style="color:var(--t)">Role model</b><span style="color:var(--dim)">Access control · work-app</span>
      <b style="color:var(--warn)">Should admins inherit viewer rights?</b><span class="a" style="margin-left:auto">12m</span><span style="color:var(--t)">↵ answer</span></div>`;
    const boardH = ROWS * (H + 10);
    const body = `${banner}<div style="position:relative;height:${boardH}px">${tiles.join('')}${card}</div>
      <div style="display:flex;gap:2.5ch;padding:4px 18px 12px;color:var(--dim);font-size:11px;white-space:nowrap"><span>◆ asks you</span><span>amber edge: asks you · cyan edge: working</span><span>bar: freshness, full = just now</span><span>dashed: a free slot, reused by the next new task</span><span style="margin-left:auto">older than two weeks: search</span></div>`;
    return frame(T, body, keys('<b>↵</b> jump to the pane') + '<span><b>←↑↓→</b> move across the board</span>', { h: 900 });
  }

  /**
   * Direction C, "Screens": recognition by content.
   * Each row gets a 120×32 px silhouette of the session's last screen beside its text (a task shows a
   * stack of its most urgent member's), so the eye can tell "the long diff" from "the test run" from
   * "the question box" before it reads a word. Rows are 44 px high (30 px today), so the list is longer.
   * The silhouettes are synthetic: templates chosen by hand per session, not read from stored screens.
   * @param {Object} T the theme.
   * @param {string} mode collapsed | expanded.
   * @returns {string} page HTML.
   */
  function renderScreens(T, mode) {
    const lead = (u, m, parent) => {
      if (m) return silhouette(m.id, finishedShape(m), T, { w: 84, fade: fadeOf(m.min) });
      const src = u.members ? (u.members.find(x => x.state === 'needs') || u.members.find(x => x.state === 'working') || u.members[0]) : u;
      return silhouette(src.id, finishedShape(src), T, { w: 120, stack: !!u.members, fade: fadeOf(u.min) });
    };
    const d = { cols: '2ch 2ch 126px 36ch 15ch minmax(0,1fr) 4ch 7ch', rowH: 44, lead, where: u => u.repo || '' };
    const html = list(T, mode, d);
    return frame(T, html, keys('<b>↵</b> jump to the pane') + '<span><b>→</b> peek the real screen</span>', { h: mode === 'expanded' ? 1740 : 1490 });
  }

  /**
   * Render one direction into the page.
   * Called by the three HTML pages. Reads `theme`, `mode` and `filter` from the query string.
   * @param {string} dir marks | desk | screens.
   */
  function render(dir) {
    const q = new URLSearchParams(location.search);
    const T = THEMES[q.get('theme') === 'light' ? 'light' : 'dark'];
    const mode = q.get('mode') || 'collapsed';
    if (window.RD_DATA) { NOW = window.RD_DATA.NOW; DAYS = window.RD_DATA.DAYS; WEEKS = window.RD_DATA.WEEKS; }
    const style = document.createElement('style');
    style.textContent = css(T) + `
.row.head .s,.row .s{display:flex;justify-content:center}
.row.mem.sel .t{color:var(--bright)}`;
    document.head.appendChild(style);
    const body = { marks: renderMarks, desk: renderDesk, screens: renderScreens }[dir](T, mode);
    document.body.innerHTML = body;
    const f = q.get('filter');
    if (f === 'gray') document.body.style.filter = 'grayscale(1)';
    if (f === 'blur') document.body.style.filter = 'blur(2.5px)';
    document.title = `${dir} · ${T.name} · ${mode}`;
  }

  window.RD = { render, THEMES, HUES, hueFor, monogram, markSvg, hash32, NOW, DAYS, WEEKS };
})();
