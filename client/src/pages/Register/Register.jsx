import {useContext, useState} from "react";
import toast from "react-hot-toast";
import {Link, useNavigate} from "react-router-dom";
import {login, register} from "../../Service/AuthService.js";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import AuthBrandPanel from "../../components/AuthBrandPanel/AuthBrandPanel.jsx";
import {startSession} from "../../util/authSession.js";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Mirrors the backend's ContactNormalizer: optional +91 / 91 / 0 prefix, common separators,
// then a 10-digit Indian mobile number starting with 6-9. The backend re-validates regardless.
const normalizeMobile = (value) => {
    let digits = value.trim().replace(/[\s\-().]/g, "");
    if (digits.startsWith("+91")) {
        digits = digits.slice(3);
    } else if (digits.length === 12 && digits.startsWith("91")) {
        digits = digits.slice(2);
    } else if (digits.length === 11 && digits.startsWith("0")) {
        digits = digits.slice(1);
    }
    return /^[6-9][0-9]{9}$/.test(digits) ? digits : null;
};

const validate = (data) => {
    const errors = {};
    const name = data.name.trim();
    if (!name) {
        errors.name = "Name is required";
    } else if (name.length > 100) {
        errors.name = "Name must be at most 100 characters";
    }
    if (!data.email.trim()) {
        errors.email = "Email is required";
    } else if (!EMAIL_PATTERN.test(data.email.trim())) {
        errors.email = "Enter a valid email address";
    }
    if (!data.mobile.trim()) {
        errors.mobile = "Mobile is required";
    } else if (!normalizeMobile(data.mobile)) {
        errors.mobile = "Enter a valid 10-digit Indian mobile number";
    }
    if (!data.password) {
        errors.password = "Password is required";
    } else if (data.password.length < 8 || data.password.length > 72) {
        errors.password = "Password must be between 8 and 72 characters";
    } else if (!/[A-Za-z]/.test(data.password) || !/[0-9]/.test(data.password)) {
        errors.password = "Password must contain at least one letter and one number";
    }
    if (data.confirmPassword !== data.password) {
        errors.confirmPassword = "Passwords do not match";
    }
    return errors;
};

const Register = () => {
    const {setAuthData} = useContext(AppContext);
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [errors, setErrors] = useState({});
    const [data, setData] = useState({
        name: "",
        email: "",
        mobile: "",
        password: "",
        confirmPassword: "",
    });

    const onChangeHandler = (e) => {
        const {name, value} = e.target;
        setData((data) => ({...data, [name]: value}));
        setErrors((errors) => ({...errors, [name]: undefined}));
    };

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        if (loading) return;
        const validationErrors = validate(data);
        setErrors(validationErrors);
        if (Object.keys(validationErrors).length > 0) return;

        setLoading(true);
        // No role is ever sent: the backend decides the account type (always a customer here).
        const payload = {
            name: data.name.trim(),
            email: data.email.trim().toLowerCase(),
            mobile: normalizeMobile(data.mobile),
            password: data.password,
        };
        try {
            await register(payload);
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to create your account");
            setLoading(false);
            return;
        }

        // Sign straight in through the normal login endpoint so the session is issued exactly the
        // same way as for every other account.
        try {
            const response = await login({identifier: payload.email, password: payload.password});
            toast.success("Account created");
            startSession(response.data, setAuthData, navigate);
        } catch (error) {
            console.error(error);
            toast.success("Account created. Please sign in.");
            navigate("/login");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="grid min-h-screen lg:grid-cols-[45fr_55fr]">
            <AuthBrandPanel />

            {/* ── REGISTER FORM PANEL ── same card as the login form */}
            <section className="flex items-center justify-center px-6 py-10 sm:px-10">
                <div className="w-full max-w-md border-[3px] border-ink bg-surface p-8 shadow-[4px_4px_0_#111827]">
                    <p className="text-xs font-extrabold uppercase tracking-[0.2em] text-muted">New customer</p>
                    <h2 className="mt-2 text-3xl font-extrabold tracking-tight">Create account</h2>
                    <p className="mt-2 text-muted">Register to shop and track your orders.</p>

                    <form className="mt-8 space-y-5" onSubmit={onSubmitHandler} noValidate>
                        <Input
                            label="Name"
                            type="text"
                            name="name"
                            id="name"
                            autoComplete="name"
                            placeholder="Your full name"
                            onChange={onChangeHandler}
                            value={data.name}
                            error={errors.name}
                            aria-invalid={Boolean(errors.name)}
                        />
                        <Input
                            label="Email address"
                            type="email"
                            name="email"
                            id="email"
                            autoComplete="email"
                            placeholder="yourname@example.com"
                            onChange={onChangeHandler}
                            value={data.email}
                            error={errors.email}
                            aria-invalid={Boolean(errors.email)}
                        />
                        <Input
                            label="Mobile"
                            type="tel"
                            name="mobile"
                            id="mobile"
                            autoComplete="tel"
                            inputMode="tel"
                            placeholder="98765 43210"
                            onChange={onChangeHandler}
                            value={data.mobile}
                            error={errors.mobile}
                            aria-invalid={Boolean(errors.mobile)}
                        />
                        <Input
                            label="Password"
                            type="password"
                            name="password"
                            id="password"
                            autoComplete="new-password"
                            placeholder="At least 8 characters, a letter and a number"
                            onChange={onChangeHandler}
                            value={data.password}
                            error={errors.password}
                            aria-invalid={Boolean(errors.password)}
                        />
                        <Input
                            label="Confirm password"
                            type="password"
                            name="confirmPassword"
                            id="confirmPassword"
                            autoComplete="new-password"
                            placeholder="**********"
                            onChange={onChangeHandler}
                            value={data.confirmPassword}
                            error={errors.confirmPassword}
                            aria-invalid={Boolean(errors.confirmPassword)}
                        />
                        <Button type="submit" variant="primary" size="lg" className="w-full" disabled={loading}>
                            {loading ? "Creating account..." : "Create account"}
                        </Button>
                    </form>

                    <p className="mt-6 text-sm font-semibold text-muted">
                        Already have an account?{" "}
                        <Link to="/login" className="font-extrabold text-primary underline underline-offset-2">
                            Sign in
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
    );
};

export default Register;
