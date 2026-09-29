export const createLatestOnly = () => {
    let generation = 0;
    return {
        begin() {
            const mine = ++generation;
            return () => mine === generation;
        },
    };
};
