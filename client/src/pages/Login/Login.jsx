import {useContext, useState} from "react";
import toast from "react-hot-toast";
import {login} from "../../Service/AuthService.js";
import {Link, useNavigate} from "react-router-dom";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import LoginBrandPanel, {CreamDecor} from "../../components/LoginBrandPanel/LoginBrandPanel.jsx";
import {startSession} from "../../util/authSession.js";

const Login = () => {
    const {setAuthData} = useContext(AppContext);
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    // identifier = email address or mobile number; the backend resolves which account it is.
    const [data, setData] = useState({
        identifier: "",
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
            const response = await login({identifier: data.identifier.trim(), password: data.password});
            if (response.status === 200) {
                toast.success("Login successfull");
                startSession(response.data, setAuthData, navigate);
            }
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Email/mobile or password is incorrect");
        } finally {
            setLoading(false);
        }
    }

    // lg+: a fixed-height (100vh) composition sized in vh/vw so it fits without scrolling; below lg the sections stack.
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
                    <h2 className="text-3xl font-extrabold tracking-tight lg:text-[clamp(1.6rem,4vh,2.4rem)] lg:leading-tight">Welcome Back</h2>
                    <p className="mt-2 text-muted lg:mt-[1vh] lg:text-[clamp(0.9rem,2vh,1.15rem)]">Sign in to your Catalyst account</p>

                    <form className="mt-9 space-y-7 lg:mt-[4.2vh] lg:space-y-[3vh]" onSubmit={onSubmitHandler}>
                        <Input
                            label="Email or Mobile"
                            type="text"
                            name="identifier"
                            id="identifier"
                            autoComplete="username"
                            placeholder="yourname@example.com or 98765 43210"
                            className="rounded-lg border-ink/20! bg-white! py-3 lg:py-[1.2vh]"
                            onChange={onChangeHandler}
                            value={data.identifier}
                        />
                        <Input
                            label="Password"
                            type="password"
                            name="password"
                            id="password"
                            autoComplete="current-password"
                            placeholder="**********"
                            className="rounded-lg border-ink/20! bg-white! py-3 lg:py-[1.2vh]"
                            onChange={onChangeHandler}
                            value={data.password}
                        />
                        <Button type="submit" variant="primary" size="lg" className="w-full rounded-lg border-primary! py-3.5 shadow-none! lg:py-[1.6vh]" disabled={loading}>
                            {loading ? "Signing in..." : <>Sign in <i className="bi bi-arrow-right" aria-hidden="true"></i></>}
                        </Button>
                    </form>

                    <div className="mt-7 flex items-center gap-4 text-sm text-muted lg:mt-[3.6vh]" aria-hidden="true">
                        <div className="h-px flex-1 bg-ink/15" />
                        or
                        <div className="h-px flex-1 bg-ink/15" />
                    </div>

                    <p className="mt-5 text-center text-sm font-semibold text-muted lg:mt-[2.4vh]">
                        New customer?{" "}
                        <Link to="/register" className="font-extrabold text-primary hover:underline underline-offset-2">
                            Create an account
                        </Link>
                    </p>
                </div>
            </section>
        </div>
    )
}

export default Login;
