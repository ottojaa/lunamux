/**
 * Karma configuration fragment for the web module's browser tests: puts the page's own stylesheet
 * in the test page, so tests that lay elements out and watch them animate see the real rules.
 * The path comes from the `LUNAMUX_STYLES_CSS` variable that `web/build.gradle.kts` sets.
 */
config.files.push({ pattern: process.env.LUNAMUX_STYLES_CSS, included: true, served: true, watched: false });
