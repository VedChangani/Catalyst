import {useContext, useState} from "react";
import toast from "react-hot-toast";
import {login} from "../../Service/AuthService.js";
import {useNavigate} from "react-router-dom";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import {assets} from "../../assets/assets.js";

const Login = () => {
    const {setAuthData} = useContext(AppContext);
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState({
        email: "",
        password: "",
    });

    const onChangeHandler = (e) => {
        const name = e.target.name;
        const value = e.target.value;
        setData((data) => ({...data, [name]: value}));
    }

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        if (loading) return;
        setLoading(true);
        try {
            const response = await login(data);
            if (response.status === 200) {
                toast.success("Login successfull");

                localStorage.setItem("token", response.data.token);
                localStorage.setItem("role", response.data.role);

                setAuthData(response.data.token, response.data.role);

                if (response.data.role === "ROLE_ADMIN") {
                    navigate("/dashboard");
                } else if (response.data.role === "ROLE_CASHIER") {
                    navigate("/pos");
                } else {
                    navigate("/explore");
                }
            }
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Email/Password Invalid");
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="grid min-h-screen lg:grid-cols-[45fr_55fr]">
            {/* ── BRAND PANEL ── Retail Billing Workspace composition */}
            <aside className="relative overflow-hidden border-b-2 border-ink bg-ink px-8 py-8 lg:border-b-0 lg:border-r-[3px] lg:px-12 lg:py-10">
                {/* ── Layer 0: Subtle grid texture ── */}
                <div
                    className="pointer-events-none absolute inset-0 opacity-[0.06]"
                    style={{
                        backgroundImage:
                            'linear-gradient(rgba(255,255,255,0.4) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.4) 1px, transparent 1px)',
                        backgroundSize: '32px 32px',
                    }}
                />

                {/* ── Layer 1: Decorative billing line-art background ──
                     Abstract geometric shapes inspired by retail/billing/invoicing.
                     All pointer-events-none, very low opacity, behind hero content. */}

                {/* 1 ▸ Large invoice/receipt outline — upper-right, partially cropped */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '8%', right: '-28px', zIndex: 1 }}
                >
                    <svg width="160" height="210" viewBox="0 0 160 210" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <rect x="1" y="1" width="158" height="208" rx="2" stroke="rgba(220,225,235,0.13)" strokeWidth="1.5" />
                        {/* Header line */}
                        <line x1="16" y1="30" x2="100" y2="30" stroke="rgba(220,225,235,0.11)" strokeWidth="1" />
                        {/* Item lines */}
                        <line x1="16" y1="55" x2="130" y2="55" stroke="rgba(220,225,235,0.09)" strokeWidth="1" />
                        <line x1="16" y1="72" x2="115" y2="72" stroke="rgba(220,225,235,0.09)" strokeWidth="1" />
                        <line x1="16" y1="89" x2="125" y2="89" stroke="rgba(220,225,235,0.09)" strokeWidth="1" />
                        {/* Dashed separator */}
                        <line x1="16" y1="115" x2="140" y2="115" stroke="rgba(220,225,235,0.10)" strokeWidth="1" strokeDasharray="4 3" />
                        {/* Total line */}
                        <line x1="16" y1="145" x2="140" y2="145" stroke="rgba(220,225,235,0.14)" strokeWidth="1.5" />
                    </svg>
                </div>

                {/* 2 ▸ Barcode-inspired vertical lines — far left edge */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '30%', left: '12px', zIndex: 1 }}
                >
                    <svg width="48" height="70" viewBox="0 0 48 70" fill="none" xmlns="http://www.w3.org/2000/svg">
                        {[0, 4, 6, 11, 14, 16, 18, 23, 25, 28, 30, 33, 36, 38, 41, 44, 46].map((x, i) => (
                            <rect
                                key={i}
                                x={x}
                                y="0"
                                width={i % 3 === 0 ? 2 : 1}
                                height="70"
                                fill={`rgba(220,225,235,${0.14 + (i % 4) * 0.015})`}
                            />
                        ))}
                    </svg>
                </div>

                {/* 3 ▸ Payment/credit-card outline — lower-left, partially cropped */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ bottom: '22%', left: '-20px', zIndex: 1 }}
                >
                    <svg width="140" height="90" viewBox="0 0 140 90" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <rect x="1" y="1" width="138" height="88" rx="6" stroke="rgba(220,225,235,0.09)" strokeWidth="1.2" />
                        {/* Mag stripe */}
                        <rect x="1" y="18" width="138" height="14" fill="rgba(220,225,235,0.04)" />
                        {/* Chip */}
                        <rect x="18" y="42" width="22" height="16" rx="2" stroke="rgba(220,225,235,0.07)" strokeWidth="1" />
                        {/* Number dots */}
                        {[0, 1, 2, 3].map(g => (
                            <g key={g}>
                                {[0, 1, 2, 3].map(d => (
                                    <circle
                                        key={d}
                                        cx={18 + g * 30 + d * 6}
                                        cy="72"
                                        r="1.5"
                                        fill={`rgba(220,225,235,0.06)`}
                                    />
                                ))}
                            </g>
                        ))}
                    </svg>
                </div>

                {/* 4 ▸ QR-code-inspired abstract grid — upper-center-right area */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '6%', left: '55%', zIndex: 1 }}
                >
                    <svg width="44" height="44" viewBox="0 0 44 44" fill="none" xmlns="http://www.w3.org/2000/svg">
                        {/* QR corner brackets */}
                        <rect x="0" y="0" width="14" height="14" stroke="rgba(255,255,255,0.14)" strokeWidth="1.5" fill="none" />
                        <rect x="3" y="3" width="8" height="8" fill="rgba(255,255,255,0.10)" />
                        <rect x="30" y="0" width="14" height="14" stroke="rgba(255,255,255,0.14)" strokeWidth="1.5" fill="none" />
                        <rect x="33" y="3" width="8" height="8" fill="rgba(255,255,255,0.10)" />
                        <rect x="0" y="30" width="14" height="14" stroke="rgba(255,255,255,0.14)" strokeWidth="1.5" fill="none" />
                        <rect x="3" y="33" width="8" height="8" fill="rgba(255,255,255,0.10)" />
                        {/* Scattered data cells */}
                        <rect x="18" y="4" width="4" height="4" fill="rgba(255,255,255,0.10)" />
                        <rect x="24" y="10" width="4" height="4" fill="rgba(255,255,255,0.08)" />
                        <rect x="18" y="18" width="4" height="4" fill="rgba(255,255,255,0.12)" />
                        <rect x="34" y="20" width="4" height="4" fill="rgba(255,255,255,0.08)" />
                        <rect x="20" y="34" width="4" height="4" fill="rgba(255,255,255,0.10)" />
                        <rect x="30" y="36" width="4" height="4" fill="rgba(255,255,255,0.08)" />
                        <rect x="38" y="32" width="4" height="4" fill="rgba(255,255,255,0.10)" />
                    </svg>
                </div>

                {/* 5 ▸ Transaction / horizontal lines — behind receipt area */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ bottom: '10%', right: '15%', zIndex: 1 }}
                >
                    <svg width="180" height="60" viewBox="0 0 180 60" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <line x1="0" y1="6" x2="140" y2="6" stroke="rgba(220,225,235,0.06)" strokeWidth="1" />
                        <line x1="0" y1="18" x2="110" y2="18" stroke="rgba(220,225,235,0.05)" strokeWidth="1" />
                        <line x1="0" y1="30" x2="160" y2="30" stroke="rgba(220,225,235,0.07)" strokeWidth="1" />
                        <line x1="0" y1="42" x2="90" y2="42" stroke="rgba(220,225,235,0.05)" strokeWidth="1" />
                        <line x1="0" y1="54" x2="130" y2="54" stroke="rgba(220,225,235,0.06)" strokeWidth="1" />
                    </svg>
                </div>

                {/* 6 ▸ Dot cluster — upper-right corner */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '5%', right: '20%', zIndex: 1 }}
                >
                    <svg width="36" height="36" viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
                        {[[4,4],[12,4],[20,4],[28,4],
                          [4,12],[12,12],[20,12],[28,12],
                          [4,20],[12,20],[20,20],
                          [4,28],[12,28]].map(([cx,cy], i) => (
                            <circle key={i} cx={cx} cy={cy} r="1.5" fill={`rgba(255,255,255,${0.06 + (i % 3) * 0.02})`} />
                        ))}
                    </svg>
                </div>

                {/* 7 ▸ Abstract rounded rectangle — lower-right, partially cropped */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ bottom: '-10px', right: '40%', zIndex: 1 }}
                >
                    <svg width="100" height="60" viewBox="0 0 100 60" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <rect x="1" y="1" width="98" height="58" rx="8" stroke="rgba(220,225,235,0.08)" strokeWidth="1.2" />
                    </svg>
                </div>

                {/* 8 ▸ Shopping cart outline — very subtle, mid-left */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '55%', left: '8%', zIndex: 1, opacity: 0.18 }}
                >
                    <svg width="40" height="40" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg" stroke="rgba(220,225,235,1)" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M2 2h5l3 18h20l4-12H12" />
                        <circle cx="15" cy="34" r="3" />
                        <circle cx="28" cy="34" r="3" />
                    </svg>
                </div>

                {/* 9 ▸ Tiny colored accent marks — cobalt line + coral dot */}
                {/* Cobalt accent — thin horizontal mark near upper area */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '25%', right: '8%', zIndex: 1 }}
                >
                    <div style={{ width: '24px', height: '2px', backgroundColor: 'rgba(37,99,235,0.25)' }} />
                </div>
                {/* Coral accent — tiny dot near barcode area */}
                <div
                    className="pointer-events-none absolute hidden lg:block"
                    style={{ top: '33%', left: '70px', zIndex: 1 }}
                >
                    <div style={{ width: '5px', height: '5px', borderRadius: '50%', backgroundColor: 'rgba(255,107,87,0.20)' }} />
                </div>

                {/* 10 ▸ Faint typographic fragments — oversized, cropped */}
                <div
                    className="pointer-events-none absolute hidden lg:block select-none"
                    style={{
                        bottom: '5%',
                        left: '-8px',
                        zIndex: 1,
                        fontSize: '72px',
                        fontWeight: 900,
                        lineHeight: 1,
                        color: 'rgba(220,225,235,0.04)',
                        letterSpacing: '0.05em',
                    }}
                >
                    INVOICE
                </div>
                <div
                    className="pointer-events-none absolute hidden lg:block select-none"
                    style={{
                        top: '3%',
                        right: '5%',
                        zIndex: 1,
                        fontSize: '48px',
                        fontWeight: 800,
                        lineHeight: 1,
                        color: 'rgba(255,255,255,0.03)',
                        letterSpacing: '0.08em',
                    }}
                >
                    ₹
                </div>

                {/* ── Tablet-only: reduced decorative set ── */}
                {/* Second barcode cluster, visible only on md screens */}
                <div
                    className="pointer-events-none absolute hidden md:block lg:hidden"
                    style={{ top: '15%', right: '10%', zIndex: 1, opacity: 0.6 }}
                >
                    <svg width="32" height="45" viewBox="0 0 32 45" fill="none" xmlns="http://www.w3.org/2000/svg">
                        {[0, 4, 7, 10, 14, 17, 20, 24, 28].map((x, i) => (
                            <rect key={i} x={x} y="0" width={i % 2 === 0 ? 2 : 1} height="45" fill={`rgba(220,225,235,0.08)`} />
                        ))}
                    </svg>
                </div>
                {/* Tablet dot cluster */}
                <div
                    className="pointer-events-none absolute hidden md:block lg:hidden"
                    style={{ bottom: '20%', left: '5%', zIndex: 1 }}
                >
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        {[[4,4],[12,4],[20,4],[4,12],[12,12],[4,20]].map(([cx,cy], i) => (
                            <circle key={i} cx={cx} cy={cy} r="1.5" fill="rgba(255,255,255,0.07)" />
                        ))}
                    </svg>
                </div>

                <div className="relative z-10 flex h-full flex-col">
                    {/* ─ Top: Application branding ─ */}
                    <div className="flex items-center gap-3">
                        <img src={assets.logo} alt="Retail billing logo" className="h-10 w-auto border-2 border-white/20 bg-white/10 p-1" />
                        <span className="text-sm font-extrabold uppercase tracking-[0.2em] text-white/80">Retail Billing</span>
                    </div>

                    {/* ─ Center: Hero editorial content ─ */}
                    <div className="mt-auto mb-6 lg:mb-0">
                        <p className="text-[11px] font-bold uppercase tracking-[0.22em] text-primary">Billing / Operations</p>
                        <h1 className="mt-3 max-w-md text-5xl font-extrabold leading-[0.92] tracking-tight text-white sm:text-6xl lg:text-[4.25rem]">
                            RETAIL<br />BILLING
                        </h1>
                        <p className="mt-5 max-w-[320px] text-[15px] font-medium leading-relaxed text-white/55">
                            Build bills, track orders, and manage payments from one workspace.
                        </p>
                    </div>

                    {/* ─ Lower area: Billing workspace composition ─ */}
                    <div className="relative mt-auto hidden lg:block">
                        {/* Geometric accent 1 — thin outlined rectangle, upper-right */}
                        <div className="pointer-events-none absolute -right-4 -top-14 h-24 w-36 border-2 border-white/[0.08]" />

                        {/* Geometric accent 2 — coral block behind receipt */}
                        <div className="pointer-events-none absolute -bottom-2 right-12 h-10 w-10 border-2 border-coral/30 bg-coral/20" />

                        {/* Geometric accent 3 — cobalt line marker */}
                        <div className="pointer-events-none absolute -left-2 bottom-16 h-[3px] w-14 bg-primary/40" />

                        {/* ── Decorative Receipt / Invoice Card ── */}
                        <div
                            className="relative ml-auto w-[280px] border-2 border-ink bg-[#FFFDF8] p-5"
                            style={{ boxShadow: '3px 3px 0 #111827' }}
                        >
                            {/* Receipt header */}
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-ink/40">Invoice</p>
                                    <p className="mt-0.5 text-sm font-extrabold text-ink">Sale Receipt</p>
                                </div>
                                <div className="h-2 w-2 rounded-full bg-primary" />
                            </div>

                            {/* Divider */}
                            <div className="my-3 h-[1.5px] bg-ink/10" />

                            {/* Line items — abstract placeholders */}
                            <div className="space-y-2 text-[12px] text-ink/70">
                                <div className="flex items-center justify-between">
                                    <span className="font-medium">Product A</span>
                                    <div className="ml-4 h-[6px] w-12 rounded-sm bg-ink/10" />
                                </div>
                                <div className="flex items-center justify-between">
                                    <span className="font-medium">Product B</span>
                                    <div className="ml-4 h-[6px] w-14 rounded-sm bg-ink/10" />
                                </div>
                                <div className="flex items-center justify-between">
                                    <span className="font-medium">Product C</span>
                                    <div className="ml-4 h-[6px] w-10 rounded-sm bg-ink/10" />
                                </div>
                            </div>

                            {/* Subtotal / Tax separator */}
                            <div className="my-3 border-t border-dashed border-ink/15" />

                            <div className="space-y-1 text-[11px] text-ink/50">
                                <div className="flex items-center justify-between">
                                    <span>Subtotal</span>
                                    <div className="h-[5px] w-10 rounded-sm bg-ink/8" />
                                </div>
                                <div className="flex items-center justify-between">
                                    <span>Tax</span>
                                    <div className="h-[5px] w-6 rounded-sm bg-ink/8" />
                                </div>
                            </div>

                            {/* Total */}
                            <div className="mt-3 flex items-center justify-between border-t-2 border-ink/10 pt-3">
                                <span className="text-xs font-extrabold uppercase tracking-wider text-ink">Total</span>
                                <div className="h-[7px] w-16 rounded-sm bg-primary/25" />
                            </div>

                            {/* Paid badge */}
                            <div className="mt-3 flex justify-end">
                                <div className="flex items-center gap-1.5 border border-ink/10 px-2 py-0.5">
                                    <div className="h-[6px] w-[6px] rounded-full bg-coral" />
                                    <span className="text-[10px] font-bold uppercase tracking-wider text-ink/50">Paid</span>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* ─ Mobile: simplified billing artifact ─ */}
                    <div className="mt-8 block lg:hidden">
                        <div
                            className="w-full max-w-[260px] border-2 border-ink bg-[#FFFDF8] p-4"
                            style={{ boxShadow: '3px 3px 0 #111827' }}
                        >
                            <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-ink/40">Invoice</p>
                            <p className="mt-0.5 text-sm font-extrabold text-ink">Sale Receipt</p>
                            <div className="my-2.5 h-[1.5px] bg-ink/10" />
                            <div className="space-y-1.5 text-[12px] text-ink/60">
                                <div className="flex justify-between">
                                    <span>Product A</span>
                                    <div className="h-[5px] w-10 rounded-sm bg-ink/10" />
                                </div>
                                <div className="flex justify-between">
                                    <span>Product B</span>
                                    <div className="h-[5px] w-12 rounded-sm bg-ink/10" />
                                </div>
                            </div>
                            <div className="mt-2.5 flex items-center justify-between border-t-2 border-ink/10 pt-2.5">
                                <span className="text-[10px] font-extrabold uppercase tracking-wider text-ink">Total</span>
                                <div className="h-[6px] w-14 rounded-sm bg-primary/25" />
                            </div>
                        </div>
                    </div>
                </div>

                {/* Background decorative — diagonal receipt outline */}
                <div className="pointer-events-none absolute -right-6 top-[15%] h-28 w-20 rotate-6 border-2 border-white/[0.05]" />
            </aside>

            {/* ── LOGIN FORM PANEL ── warm ivory with global grid visible */}
            <section className="flex items-center justify-center px-6 py-10 sm:px-10">
                <div className="w-full max-w-md border-[3px] border-ink bg-surface p-8 shadow-[4px_4px_0_#111827]">
                    <p className="text-xs font-extrabold uppercase tracking-[0.2em] text-muted">Welcome back</p>
                    <h2 className="mt-2 text-3xl font-extrabold tracking-tight">Sign in</h2>
                    <p className="mt-2 text-muted">Sign in to continue to Retail Billing.</p>

                    <form className="mt-8 space-y-5" onSubmit={onSubmitHandler}>
                        <Input
                            label="Email address"
                            type="text"
                            name="email"
                            id="email"
                            placeholder="yourname@example.com"
                            onChange={onChangeHandler}
                            value={data.email}
                        />
                        <Input
                            label="Password"
                            type="password"
                            name="password"
                            id="password"
                            placeholder="**********"
                            onChange={onChangeHandler}
                            value={data.password}
                        />
                        <Button type="submit" variant="primary" size="lg" className="w-full" disabled={loading}>
                            {loading ? "Signing in..." : "Sign in"}
                        </Button>
                    </form>

                    {/* Small coral accent line */}
                    <div className="mt-6 flex items-center gap-2">
                        <div className="h-0.5 w-8 bg-coral" />
                        <span className="text-xs font-bold text-muted">Secure access</span>
                    </div>
                </div>
            </section>
        </div>
    )
}

export default Login;
