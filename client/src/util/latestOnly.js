// "Latest request wins" guard for overlapping fetches of the same data (the catalog).
//
// Every call to begin() starts a new generation and returns a function that says whether that
// call is still the newest one. A slow, older response that finishes after a newer one must not
// overwrite it - otherwise the stock display could jump back to a stale value.
export const createLatestOnly = () => {
    let generation = 0;
    return {
        begin() {
            const mine = ++generation;
            return () => mine === generation;
        },
    };
};
