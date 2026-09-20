import {assets} from "../../assets/assets.js";

// Login-only marketing panel. (Register keeps using components/AuthBrandPanel.)
// On lg+ it fills the left 68% of a fixed-height (100vh) login screen and every size is viewport-relative
// (vh/vw), so the whole composition fits without scrolling; the login card overlaps its right edge.
const FEATURES = [
    {icon: "bi-cart3", title: "Sell", text: "In-store or online"},
    {icon: "bi-box-seam", title: "Manage", text: "Inventory & orders"},
    {icon: "bi-credit-card-2-front", title: "Accept", text: "Multiple payments"},
    {icon: "bi-bar-chart", title: "Grow", text: "Insights & analytics"},
];

// Decorative data-matrix pattern (not a real QR code): deterministic pseudo-random cells plus corner finders.
const MATRIX_N = 13;
const MATRIX_CELLS = (() => {
    const cells = [];
    let seed = 7;
    for (let y = 0; y < MATRIX_N; y++) {
        for (let x = 0; x < MATRIX_N; x++) {
            seed = (seed * 1103515245 + 12345) & 0x7fffffff;
            const inFinder = (x < 4 && y < 4) || (x > MATRIX_N - 5 && y < 4) || (x < 4 && y > MATRIX_N - 5);
            if (!inFinder && seed % 100 < 46) cells.push([x, y]);
        }
    }
    return cells;
})();
const FINDERS = [[0, 0], [MATRIX_N - 3, 0], [0, MATRIX_N - 3]];

const DataMatrix = ({className = "", stroke = "rgba(96,130,255,0.55)"}) => (
    <svg viewBox={`0 0 ${MATRIX_N} ${MATRIX_N}`} className={className} fill={stroke} aria-hidden="true" shapeRendering="crispEdges">
        {MATRIX_CELLS.map(([x, y]) => <rect key={`${x}-${y}`} x={x + 0.1} y={y + 0.1} width="0.8" height="0.8" />)}
        {FINDERS.map(([x, y]) => (
            <g key={`f${x}-${y}`}>
                <rect x={x + 0.1} y={y + 0.1} width="2.8" height="2.8" fill="none" stroke={stroke} strokeWidth="0.35" />
                <rect x={x + 1} y={y + 1} width="1" height="1" />
            </g>
        ))}
    </svg>
);

// Decorative barcode-like bars of varied widths/heights.
const BARS = [2, 1, 3, 1, 1, 2, 4, 1, 2, 1, 1, 3, 2, 1, 4, 1, 2, 2, 1, 3, 1, 1, 2, 3, 1, 2];
const Barcode = ({className = "", fill = "rgba(255,255,255,0.5)"}) => {
    let x = 0;
    return (
        <svg viewBox="0 0 80 24" className={className} fill={fill} aria-hidden="true" shapeRendering="crispEdges" preserveAspectRatio="none">
            {BARS.map((w, i) => {
                const bar = <rect key={i} x={x} y={i % 5 === 0 ? 0 : 3} width={w} height={i % 5 === 0 ? 24 : 18} />;
                x += w + 1.4;
                return bar;
            })}
        </svg>
    );
};

// Very faint technical decoration for the cream side; absolutely positioned, behind the login card, desktop only.
export const CreamDecor = () => (
    <div className="pointer-events-none absolute inset-0 hidden lg:block" aria-hidden="true">
        {/* upper cluster, above the card */}
        <div className="absolute left-[75vw] top-[14vh] h-[7vh] w-[7vh] border border-primary/20" />
        <div className="absolute left-[calc(75vw+4vh)] top-[calc(14vh+4vh)] h-[7vh] w-[7vh] border border-ink/10" />
        <DataMatrix className="absolute left-[calc(75vw+11vh)] top-[13vh] h-[5vh] w-[5vh] opacity-30" stroke="rgba(37,99,235,0.6)" />
        {/* corner bracket, upper left of the cream side */}
        <div className="absolute left-[70vw] top-[9vh] h-[3vh] w-[3vh] border-l border-t border-ink/20" />
        {/* lower cluster, left of the bottom accent */}
        <div className="absolute bottom-[6vh] left-[74vw] h-px w-[8vw] bg-ink/10" />
        <div className="absolute bottom-[calc(6vh-1.5vh)] left-[74vw] h-[3vh] w-px bg-ink/10" />
        <div className="absolute bottom-[8vh] left-[78vw] h-[6vh] w-[6vh] border border-primary/15" />
    </div>
);

const LoginBrandPanel = () => {
    return (
        <aside className="relative overflow-hidden bg-ink px-6 py-8 sm:px-10 lg:absolute lg:inset-y-0 lg:left-0 lg:w-[68%] lg:px-[5.7vw] lg:py-0">
            {/* Subtle grid texture */}
            <div
                className="pointer-events-none absolute inset-0 opacity-[0.06]"
                style={{
                    backgroundImage:
                        'linear-gradient(rgba(255,255,255,0.4) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.4) 1px, transparent 1px)',
                    backgroundSize: '32px 32px',
                }}
            />

            {/* Faint technical linework (decorative, desktop only) */}
            <div className="pointer-events-none absolute right-[8%] top-0 hidden h-[10%] w-[10%] border-x border-b border-primary/30 lg:block" aria-hidden="true" />
            <div className="pointer-events-none absolute right-[6%] top-[9%] hidden h-[6%] w-[6%] border border-primary/25 lg:block" aria-hidden="true" />
            <div
                className="pointer-events-none absolute right-[22%] top-[11%] hidden h-[10%] w-[8%] opacity-40 lg:block"
                style={{backgroundImage: 'radial-gradient(circle, rgba(96,130,255,0.7) 1.5px, transparent 1.5px)', backgroundSize: '20px 20px'}}
                aria-hidden="true"
            />
            <div className="pointer-events-none absolute right-[3%] top-[43%] hidden h-[10%] w-[6%] border border-primary/25 lg:block" aria-hidden="true" />
            {/* Restored technical motifs, all in free space and behind the content */}
            <div className="pointer-events-none absolute left-[62%] top-[27%] hidden lg:block" aria-hidden="true">
                <DataMatrix className="h-[11vh] w-[11vh] opacity-25" />
            </div>
            <div className="pointer-events-none absolute left-[32%] top-[12%] hidden lg:block" aria-hidden="true">
                <Barcode className="h-[3.8vh] w-[7vw] opacity-[0.22]" />
            </div>
            <div className="pointer-events-none absolute right-[5%] top-[52%] hidden h-[14vh] w-[14vh] rounded-full border border-white/[0.06] lg:block" aria-hidden="true" />
            <div className="pointer-events-none absolute bottom-[9vh] right-[4%] hidden h-[4vh] w-[4vh] border-b border-r border-primary/35 lg:block" aria-hidden="true" />
            <div className="pointer-events-none absolute -left-16 -top-16 h-64 w-64 rounded-full bg-primary/15 blur-3xl" aria-hidden="true" />

            {/* Content, placed in vh so it sits where the reference has it: logo ~9%, eyebrow ~20%,
                headline 24-47%, description ~52%, features 63-74%. */}
            <div className="relative z-10 lg:pt-[6.5vh]">
                {/* Logo directly on the navy: the navy lettering is remapped to white and the blue mark
                    is kept, using an inline SVG colour-matrix filter (the asset is unchanged). The file
                    has wide transparent margins, so it is cropped to the artwork (x 147-2036, y 179-543
                    of 2172x724) with percentages, and sized by height. */}
                <svg width="0" height="0" className="absolute" aria-hidden="true" focusable="false">
                    <filter id="catalyst-logo-on-dark" colorInterpolationFilters="sRGB">
                        <feColorMatrix
                            type="matrix"
                            values="1 0 -1 0 1   0.6 0 -0.6 0 1   0 0 0 0 1   0 0 0 1 0"
                        />
                    </filter>
                </svg>
                <div
                    className="relative overflow-hidden"
                    style={{aspectRatio: '1889 / 364', height: 'clamp(30px, 3.9vh, 42px)'}}
                >
                    <img
                        src={assets.logo}
                        alt="Catalyst"
                        className="absolute max-w-none"
                        style={{
                            width: '114.98%',
                            left: '-7.78%',
                            top: '-49.18%',
                            filter: 'url(#catalyst-logo-on-dark)',
                        }}
                    />
                </div>

                <p className="mt-10 text-[11px] font-bold uppercase tracking-[0.4em] text-primary lg:mt-[8vh] lg:text-[clamp(11px,1.45vh,15px)]">
                    All your store operations
                </p>
                <h1
                    className="mt-4 text-5xl font-extrabold leading-[1.02] tracking-tight text-white sm:text-6xl lg:mt-[2.6vh] lg:text-[min(5.3vw,7.6vh)]"
                >
                    Manage Your<br />Business,<br />
                    <span className="text-primary">Smarter.</span>
                </h1>
                <p className="mt-6 max-w-[36rem] text-[15px] font-medium leading-relaxed text-white/70 lg:mt-[3vh] lg:max-w-[38vw] lg:text-[clamp(13px,2vh,18px)]">
                    A modern platform to manage products, create bills,{" "}
                    track orders, handle payments and grow your business{" "}
                    — all in one place.
                </p>

                <ul className="mt-8 grid grid-cols-2 gap-x-6 gap-y-6 sm:grid-cols-4 lg:mt-[4.2vh] lg:grid-cols-[repeat(4,12.6vw)] lg:gap-x-0 lg:gap-y-0">
                    {FEATURES.map((f) => (
                        <li key={f.title}>
                            <span className="grid h-11 w-11 place-items-center rounded-lg border border-white/15 bg-white/5 lg:h-[clamp(38px,5.4vh,56px)] lg:w-[clamp(38px,5.4vh,56px)]">
                                <i className={`bi ${f.icon} text-lg text-primary`} aria-hidden="true"></i>
                            </span>
                            <p className="mt-2.5 text-sm font-extrabold text-white lg:mt-[1.4vh] lg:text-[clamp(13px,1.9vh,17px)]">{f.title}</p>
                            <p className="text-xs font-medium leading-snug text-white/55 lg:text-[clamp(11px,1.6vh,14px)]">{f.text}</p>
                        </li>
                    ))}
                </ul>
            </div>

            {/* Decorative watermark: absolutely positioned near the bottom, so it never adds page height.
                Width (~4.8em) is kept clear of the card that overlaps from the right. */}
            <div className="pointer-events-none absolute bottom-[4.5vh] left-[5.7vw] hidden select-none lg:block" aria-hidden="true">
                <p
                    className="whitespace-nowrap font-extrabold leading-[0.8] tracking-tight"
                    style={{
                        color: 'transparent',
                        WebkitTextStroke: '1.5px rgba(255,255,255,0.12)',
                        fontSize: 'min(10.4vw, 15vh)',
                    }}
                >
                    CATALYST
                </p>
                <p className="mt-[2vh] text-[clamp(10px,1.3vh,13px)] font-bold uppercase tracking-[0.35em] text-white/30">
                    Build • Sell • Operate • Scale
                </p>
            </div>
        </aside>
    );
};

export default LoginBrandPanel;
