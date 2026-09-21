import LoginBrandPanel, {CreamDecor} from "./LoginBrandPanel.jsx";

// Shared shell of the finalized login screen (also used by Forgot Password): brand panel, cream side
// with its decoration, and the auth card. Pages put their own content inside the card.
// lg+: a fixed-height (100vh) composition sized in vh/vw so it fits without scrolling; below lg the sections stack.
const LoginLayout = ({children}) => {
    return (
        <div className="relative flex flex-col lg:block lg:h-[max(100vh,34rem)]" style={{"--cw": "min(34.4vw, 36rem)"}}>
            <LoginBrandPanel />
            <CreamDecor />

            {/* Cream side: the page's own grid background shows through. Decorative text only. */}
            <p className="pointer-events-none absolute right-[5.4vw] top-[6.8vh] hidden text-[clamp(10px,1.2vh,12px)] font-bold uppercase tracking-[0.3em] text-muted/70 lg:block" aria-hidden="true">
                Shop • Bill • Manage • Grow
            </p>
            <div className="pointer-events-none absolute bottom-[5.6vh] right-[5vw] hidden lg:block" aria-hidden="true">
                <p className="border-y border-l border-primary/40 py-[1.2vh] pl-4 pr-6 text-left text-[clamp(9px,1.1vh,11px)] font-bold uppercase leading-relaxed tracking-[0.3em] text-muted/70">
                    Retail<br />Commerce<br />Simplified
                </p>
            </div>

            {/* Login card: starts inside the dark panel and extends over the cream side (lg+) */}
            <section className="relative z-20 flex justify-center px-5 py-10 sm:px-10 lg:absolute lg:left-[calc(68vw_-_var(--cw)*0.34)] lg:top-[calc(50%_-_16px)] lg:w-[var(--cw)] lg:-translate-y-1/2 lg:justify-start lg:p-0">
                <div className="w-full max-w-md rounded-2xl border border-ink/10 bg-surface p-8 shadow-lg shadow-ink/10 sm:p-10 lg:max-w-none lg:px-[12.5%] lg:py-[6vh]">
                    {children}
                </div>
            </section>
        </div>
    );
};

export default LoginLayout;
