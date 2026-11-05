/**
 * Karma configuration fragment for the web module's browser tests: raises mocha's per-test limit
 * from two seconds to fifteen. The working-glyph redraw test watches the page for a little over
 * two seconds, which the default limit would cut off.
 *
 * Picked up by the Kotlin/JS Gradle plugin from `karma.config.d` when `:web:jsBrowserTest` runs.
 */
config.set({
    client: {
        mocha: {
            timeout: 15000
        }
    }
});
