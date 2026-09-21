import {useEffect, useReducer, useRef, useState} from "react";
import {Link, useNavigate} from "react-router-dom";
import toast from "react-hot-toast";
import {requestPasswordReset, resetPassword} from "../../Service/AuthService.js";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import LoginLayout from "../../components/LoginBrandPanel/LoginLayout.jsx";
import {passwordRuleStatus} from "../../util/accountValidation.js";
import {
    buildResetPayload, FORGOT_MESSAGE, initialState, OTP_LENGTH, reducer, requestErrorMessage,
    secondsLeft, validateEmailStep, validateResetForm,
} from "../../util/passwordReset.js";

const fieldClass = "rounded-lg border-ink/20! bg-white! py-3 lg:py-[1.1vh]";
const headingClass = "text-3xl font-extrabold tracking-tight lg:text-[clamp(1.5rem,3.6vh,2.2rem)] lg:leading-tight";
const introClass = "mt-2 text-muted lg:mt-[0.8vh] lg:text-[clamp(0.85rem,1.8vh,1.05rem)]";
const backLink = (
    <p className="mt-5 text-center text-sm font-semibold text-muted lg:mt-[2vh]">
        <Link to="/login" className="font-extrabold text-primary underline-offset-2 hover:underline">
            <i className="bi bi-arrow-left" aria-hidden="true"></i> Back to sign in
        </Link>
    </p>
);

// Customer password recovery: email -> code + new password -> done. All state (including the code
// and passwords) is in-memory only and is cleared when the flow is left; no session is created.
const ForgotPassword = () => {
    const navigate = useNavigate();
    const [state, dispatch] = useReducer(reducer, initialState);
    const [errors, setErrors] = useState({});
    const [formError, setFormError] = useState("");
    const [busy, setBusy] = useState(false);
    const [now, setNow] = useState(() => Date.now());
    const inFlight = useRef(false); // blocks a second submit before React re-renders the disabled button

    const remaining = secondsLeft(state.resendAvailableAt, now);

    // Tick once a second only while the resend lock is active; the cleanup stops it on unmount.
    useEffect(() => {
        if (state.step !== "code" || state.resendAvailableAt == null || Date.now() >= state.resendAvailableAt) {
            return undefined;
        }
        setNow(Date.now());
        const timer = setInterval(() => {
            const current = Date.now();
            setNow(current);
            if (current >= state.resendAvailableAt) clearInterval(timer);
        }, 1000);
        return () => clearInterval(timer);
    }, [state.step, state.resendAvailableAt]);

    const run = async (task) => {
        if (inFlight.current) return;
        inFlight.current = true;
        setBusy(true);
        try {
            await task();
        } finally {
            inFlight.current = false;
            setBusy(false);
        }
    };

    const sendCode = (e) => {
        e.preventDefault();
        const emailProblem = validateEmailStep(state.email);
        setErrors(emailProblem ? {email: emailProblem} : {});
        setFormError("");
        if (emailProblem) return undefined;
        return run(async () => {
            try {
                await requestPasswordReset(state.email.trim());
                dispatch({type: "codeSent", now: Date.now()});
                setNow(Date.now());
                setErrors({});
            } catch (error) {
                setFormError(requestErrorMessage(error, "Unable to send the code. Please try again."));
            }
        });
    };

    const resend = () => {
        if (remaining > 0) return undefined;
        setFormError("");
        return run(async () => {
            try {
                await requestPasswordReset(state.email);
                dispatch({type: "resent", now: Date.now()});
                setNow(Date.now());
                toast.success(FORGOT_MESSAGE);
            } catch (error) {
                setFormError(requestErrorMessage(error, "Unable to resend the code. Please try again."));
            }
        });
    };

    const submitReset = (e) => {
        e.preventDefault();
        const problems = validateResetForm(state);
        setErrors(problems);
        setFormError("");
        if (Object.keys(problems).length > 0) return undefined;
        return run(async () => {
            try {
                await resetPassword(buildResetPayload(state.email, state));
                dispatch({type: "resetDone"});
                setErrors({});
            } catch (error) {
                // Kept as it is (e.g. "The code is invalid or has expired."); the typed fields stay.
                setFormError(requestErrorMessage(error, "Unable to reset your password. Please try again."));
            }
        });
    };

    const onField = (e) => {
        dispatch({type: "field", name: e.target.name, value: e.target.value});
        setErrors((current) => ({...current, [e.target.name]: undefined}));
    };

    const differentEmail = () => {
        dispatch({type: "differentEmail"});
        setErrors({});
        setFormError("");
    };

    const errorBox = formError && (
        <p role="alert" className="border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">{formError}</p>
    );

    if (state.step === "done") {
        return (
            <LoginLayout>
                <h2 className={headingClass}>Password updated</h2>
                <p className={introClass}>Your password has been updated. Please sign in with your new password.</p>
                <Button type="button" variant="primary" size="lg" className="mt-8 w-full rounded-lg border-primary! py-3.5 shadow-none! lg:mt-[4vh] lg:py-[1.6vh]"
                        onClick={() => navigate("/login")}>
                    Back to sign in
                </Button>
            </LoginLayout>
        );
    }

    if (state.step === "code") {
        return (
            <LoginLayout>
                <h2 className={headingClass}>Enter verification code</h2>
                <p className={introClass}>Enter the 6-digit code sent to your email.</p>
                <p className="mt-2 text-xs font-semibold text-muted lg:text-[clamp(0.7rem,1.5vh,0.85rem)]">{FORGOT_MESSAGE}</p>
                <p className="mt-1 break-all text-sm font-bold text-ink">{state.email}</p>

                <form className="mt-5 space-y-4 lg:mt-[2.4vh] lg:space-y-[1.8vh]" onSubmit={submitReset} noValidate>
                    <Input label="Verification code" id="otp" name="otp" type="text" inputMode="numeric"
                           autoComplete="one-time-code" maxLength={OTP_LENGTH} placeholder="123456"
                           className={fieldClass} value={state.otp} onChange={onField}
                           error={errors.otp} aria-invalid={Boolean(errors.otp)} />
                    <div>
                        <Input label="New password" id="newPassword" name="newPassword" type="password"
                               autoComplete="new-password" placeholder="**********"
                               className={fieldClass} value={state.newPassword} onChange={onField}
                               error={errors.newPassword} aria-invalid={Boolean(errors.newPassword)} />
                        <ul className="mt-1.5 grid grid-cols-2 gap-x-3 text-xs font-semibold" aria-label="Password requirements">
                            {passwordRuleStatus(state.newPassword).map((rule) => (
                                <li key={rule.key} className={rule.met ? "text-success" : "text-muted"}>
                                    <span aria-hidden="true">{rule.met ? "✓" : "○"}</span> {rule.label}
                                    <span className="sr-only">{rule.met ? " (met)" : " (not met)"}</span>
                                </li>
                            ))}
                        </ul>
                    </div>
                    <Input label="Confirm new password" id="confirmNewPassword" name="confirmNewPassword" type="password"
                           autoComplete="new-password" placeholder="**********"
                           className={fieldClass} value={state.confirmNewPassword} onChange={onField}
                           error={errors.confirmNewPassword} aria-invalid={Boolean(errors.confirmNewPassword)} />
                    {errorBox}
                    <Button type="submit" variant="primary" size="lg" className="w-full rounded-lg border-primary! py-3 shadow-none! lg:py-[1.4vh]" disabled={busy}>
                        {busy ? "Resetting..." : "Reset password"}
                    </Button>
                </form>

                <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm font-bold lg:mt-[2vh]">
                    <button type="button" className="text-primary underline-offset-2 hover:underline disabled:text-muted disabled:no-underline"
                            disabled={busy || remaining > 0} onClick={resend}>
                        {remaining > 0 ? `Resend code in ${remaining}s` : "Resend code"}
                    </button>
                    <button type="button" className="text-primary underline-offset-2 hover:underline" onClick={differentEmail}>
                        Use a different email
                    </button>
                </div>
                {backLink}
            </LoginLayout>
        );
    }

    return (
        <LoginLayout>
            <h2 className={headingClass}>Reset your password</h2>
            <p className={introClass}>Enter your email and we’ll send you a verification code.</p>
            <p className="mt-1 text-xs font-semibold text-muted lg:text-[clamp(0.7rem,1.5vh,0.85rem)]">
                Password recovery is available for customer accounts.
            </p>

            <form className="mt-8 space-y-6 lg:mt-[3.6vh] lg:space-y-[2.6vh]" onSubmit={sendCode} noValidate>
                <Input label="Email" id="email" name="email" type="email" autoComplete="email"
                       placeholder="yourname@example.com" className={fieldClass}
                       value={state.email} onChange={(e) => {
                           dispatch({type: "email", value: e.target.value});
                           setErrors({});
                       }}
                       error={errors.email} aria-invalid={Boolean(errors.email)} />
                {errorBox}
                <Button type="submit" variant="primary" size="lg" className="w-full rounded-lg border-primary! py-3.5 shadow-none! lg:py-[1.6vh]" disabled={busy}>
                    {busy ? "Sending..." : "Send code"}
                </Button>
            </form>
            {backLink}
        </LoginLayout>
    );
};

export default ForgotPassword;
