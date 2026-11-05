// The spinner test watches the page for a little over two seconds; mocha's default limit is two.
config.set({
    client: {
        mocha: {
            timeout: 15000
        }
    }
});
