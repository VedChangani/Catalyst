import {useContext, useState} from "react";
import toast from "react-hot-toast";
import {login} from "../../Service/AuthService.js";
import {Link, useNavigate} from "react-router-dom";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import AuthBrandPanel from "../../components/AuthBrandPanel/AuthBrandPanel.jsx";
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

    return (
        <div className="grid min-h-screen lg:grid-cols-[45fr_55fr]">
            <AuthBrandPanel />

            {/* ── LOGIN FORM PANEL ── warm ivory with global grid visible */}
            <section className="flex items-center justify-center px-6 py-10 sm:px-10">
                <div className="w-full max-w-md border-[3px] border-ink bg-surface p-8 shadow-[4px_4px_0_#111827]">
                    <p className="text-xs font-extrabold uppercase tracking-[0.2em] text-muted">Welcome back</p>
                    <h2 className="mt-2 text-3xl font-extrabold tracking-tight">Sign in</h2>
                    <p className="mt-2 text-muted">Sign in to continue to Retail Billing.</p>

                    <form className="mt-8 space-y-5" onSubmit={onSubmitHandler}>
                        <Input
                            label="Email or Mobile"
                            type="text"
                            name="identifier"
                            id="identifier"
                            autoComplete="username"
                            placeholder="yourname@example.com or 98765 43210"
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
                            onChange={onChangeHandler}
                            value={data.password}
                        />
                        <Button type="submit" variant="primary" size="lg" className="w-full" disabled={loading}>
                            {loading ? "Signing in..." : "Sign in"}
                        </Button>
                    </form>

                    <p className="mt-6 text-sm font-semibold text-muted">
                        New customer?{" "}
                        <Link to="/register" className="font-extrabold text-primary underline underline-offset-2">
                            Create an account
                        </Link>
                    </p>

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
