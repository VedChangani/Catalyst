import {useContext, useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import toast from "react-hot-toast";
import {AppContext} from "../../context/AppContext.jsx";
import {changeMyPassword, fetchMyAccount, updateMyAccount} from "../../Service/AccountService.js";
import {normalizeMobile, validatePasswordChange, validateProfile} from "../../util/accountValidation.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import Input from "../../ui/Input.jsx";
import {formatDate} from "../../util/orderFormat.js";

const ROLE_LABELS = {
    ROLE_USER: "Customer",
    ROLE_CASHIER: "Cashier",
    ROLE_ADMIN: "Administrator",
};

const EMPTY_PASSWORDS = {currentPassword: "", newPassword: "", confirmNewPassword: ""};

const tabClass = (active) =>
    `border-2 border-ink px-4 py-2 text-sm font-extrabold uppercase tracking-wide focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${
        active ? "bg-primary text-white shadow-[2px_2px_0_#111827]" : "bg-surface hover:bg-primary/10"
    }`;

// The signed-in user's own account (every role): Profile and Security. The backend derives the
// account from the login, so no user id is ever sent. Role and status are shown, never editable.
const Account = () => {
    const navigate = useNavigate();
    const {auth, setAuthData, clearCart} = useContext(AppContext);
    const [tab, setTab] = useState("profile");

    const [account, setAccount] = useState(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    const [profile, setProfile] = useState({name: "", email: "", mobile: ""});
    const [profileErrors, setProfileErrors] = useState({});
    const [savingProfile, setSavingProfile] = useState(false);

    const [passwords, setPasswords] = useState(EMPTY_PASSWORDS);
    const [passwordErrors, setPasswordErrors] = useState({});
    const [changingPassword, setChangingPassword] = useState(false);

    const applyAccount = (loaded) => {
        setAccount(loaded);
        setProfile({name: loaded.name || "", email: loaded.email || "", mobile: loaded.mobile || ""});
    };

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        fetchMyAccount(controller.signal)
            .then((response) => applyAccount(response.data))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") return;
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load your account");
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [reloadToken]);

    const onProfileChange = (e) => {
        const {name, value} = e.target;
        setProfile((current) => ({...current, [name]: value}));
        setProfileErrors((errors) => ({...errors, [name]: undefined}));
    };

    const onSaveProfile = async (e) => {
        e.preventDefault();
        if (savingProfile) return;
        const errors = validateProfile(profile, Boolean(account?.mobile));
        setProfileErrors(errors);
        if (Object.keys(errors).length > 0) return;
        setSavingProfile(true);
        try {
            const response = await updateMyAccount({
                name: profile.name.trim(),
                email: profile.email.trim().toLowerCase(),
                mobile: profile.mobile.trim() ? normalizeMobile(profile.mobile) : "",
            });
            applyAccount(response.data.account);
            // Changing the email replaces the login token (its subject is the email): store the
            // new one so the session carries on.
            if (response.data.token) {
                localStorage.setItem("token", response.data.token);
                setAuthData(response.data.token, auth.role);
            }
            toast.success("Profile updated");
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to update your profile");
        } finally {
            setSavingProfile(false);
        }
    };

    const onPasswordChange = (e) => {
        const {name, value} = e.target;
        setPasswords((current) => ({...current, [name]: value}));
        setPasswordErrors((errors) => ({...errors, [name]: undefined}));
    };

    const onChangePassword = async (e) => {
        e.preventDefault();
        if (changingPassword) return;
        const errors = validatePasswordChange(passwords);
        setPasswordErrors(errors);
        if (Object.keys(errors).length > 0) return;
        setChangingPassword(true);
        try {
            await changeMyPassword(passwords);
            setPasswords(EMPTY_PASSWORDS);
            // The backend ends every session for this account on a password change, so sign out
            // here too and ask for the new password.
            localStorage.removeItem("token");
            localStorage.removeItem("role");
            clearCart();
            setAuthData(null, null);
            toast.success("Password changed. Please sign in again with your new password.");
            navigate("/login");
        } catch (error) {
            console.error(error);
            setPasswords((current) => ({...current, currentPassword: ""}));
            toast.error(error.friendlyMessage || "Unable to change your password");
        } finally {
            setChangingPassword(false);
        }
    };

    if (loading) {
        return (
            <PageShell>
                <LoadingState label="Loading your account..." />
            </PageShell>
        );
    }

    if (loadError || !account) {
        return (
            <PageShell>
                <PageHeader kicker="Account" title="Account" />
                <EmptyState
                    title="Couldn't load your account"
                    description={loadError || "Please try again."}
                    action={<Button variant="dark" size="sm" onClick={() => setReloadToken((t) => t + 1)}>Try again</Button>}
                />
            </PageShell>
        );
    }

    return (
        <PageShell>
            <PageHeader kicker="Account" title="Your Account" description="Manage your details and password." />

            <div role="tablist" aria-label="Account sections" className="mb-5 flex gap-2">
                <button type="button" role="tab" id="tab-profile" aria-selected={tab === "profile"}
                        aria-controls="panel-profile" className={tabClass(tab === "profile")} onClick={() => setTab("profile")}>
                    Profile
                </button>
                <button type="button" role="tab" id="tab-security" aria-selected={tab === "security"}
                        aria-controls="panel-security" className={tabClass(tab === "security")} onClick={() => setTab("security")}>
                    Security
                </button>
            </div>

            {tab === "profile" && (
                <div id="panel-profile" role="tabpanel" aria-labelledby="tab-profile" className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(260px,0.5fr)]">
                    <section className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                        <h2 className="mb-4 text-lg font-extrabold">Profile</h2>
                        <form className="space-y-4" onSubmit={onSaveProfile} noValidate>
                            <Input label="Name" id="account-name" name="name" value={profile.name} onChange={onProfileChange}
                                   autoComplete="name" error={profileErrors.name} aria-invalid={Boolean(profileErrors.name)} />
                            <Input label="Email" id="account-email" name="email" type="email" value={profile.email}
                                   onChange={onProfileChange} autoComplete="email" error={profileErrors.email}
                                   aria-invalid={Boolean(profileErrors.email)} />
                            <Input label="Mobile" id="account-mobile" name="mobile" type="tel" inputMode="tel"
                                   placeholder="98765 43210" value={profile.mobile} onChange={onProfileChange}
                                   autoComplete="tel" error={profileErrors.mobile} aria-invalid={Boolean(profileErrors.mobile)} />
                            <p className="text-xs font-semibold text-muted">
                                You sign in with your email or mobile. Changing your email keeps you signed in on this device.
                                Past orders keep the name and mobile they were placed with.
                            </p>
                            <Button type="submit" variant="primary" disabled={savingProfile}>
                                {savingProfile ? "Saving..." : "Save profile"}
                            </Button>
                        </form>
                    </section>

                    <section aria-label="Account information" className="self-start border-2 border-ink bg-paper p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                        <h2 className="mb-4 text-lg font-extrabold">Account information</h2>
                        <dl className="space-y-3 text-sm">
                            <div>
                                <dt className="text-xs font-extrabold uppercase tracking-wide text-muted">Role</dt>
                                <dd className="mt-1 font-bold">{ROLE_LABELS[account.role] || account.role}</dd>
                            </div>
                            <div>
                                <dt className="text-xs font-extrabold uppercase tracking-wide text-muted">Status</dt>
                                <dd className="mt-1">
                                    <Badge tone={account.enabled ? "success" : "danger"}>{account.enabled ? "Active" : "Inactive"}</Badge>
                                </dd>
                            </div>
                            <div>
                                <dt className="text-xs font-extrabold uppercase tracking-wide text-muted">Member since</dt>
                                <dd className="mt-1 font-bold">{account.createdAt ? formatDate(account.createdAt) : "—"}</dd>
                            </div>
                        </dl>
                        <p className="mt-4 text-xs font-semibold text-muted">Role and status are managed by the store and can't be changed here.</p>
                        {account.role !== "ROLE_ADMIN" && (
                            <div className="mt-4 border-t-2 border-ink/10 pt-4">
                                <p className="text-xs font-extrabold uppercase tracking-wide text-muted">Activity</p>
                                <p className="mt-1 text-sm">See your sign-ins, account changes, orders and payments.</p>
                                <Button variant="dark" size="sm" className="mt-2" onClick={() => navigate("/activity")}>
                                    View activity log
                                </Button>
                            </div>
                        )}
                    </section>
                </div>
            )}

            {tab === "security" && (
                <div id="panel-security" role="tabpanel" aria-labelledby="tab-security">
                    <section className="max-w-xl border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                        <h2 className="mb-4 text-lg font-extrabold">Change password</h2>
                        <form className="space-y-4" onSubmit={onChangePassword} noValidate>
                            <Input label="Current password" id="current-password" name="currentPassword" type="password"
                                   value={passwords.currentPassword} onChange={onPasswordChange} autoComplete="current-password"
                                   error={passwordErrors.currentPassword} aria-invalid={Boolean(passwordErrors.currentPassword)} />
                            <Input label="New password" id="new-password" name="newPassword" type="password"
                                   placeholder="8+ characters with upper, lower, number, symbol"
                                   value={passwords.newPassword} onChange={onPasswordChange} autoComplete="new-password"
                                   error={passwordErrors.newPassword} aria-invalid={Boolean(passwordErrors.newPassword)} />
                            <Input label="Confirm new password" id="confirm-new-password" name="confirmNewPassword" type="password"
                                   value={passwords.confirmNewPassword} onChange={onPasswordChange} autoComplete="new-password"
                                   error={passwordErrors.confirmNewPassword} aria-invalid={Boolean(passwordErrors.confirmNewPassword)} />
                            <p className="text-xs font-semibold text-muted">
                                After changing your password you'll be signed out everywhere and asked to sign in again.
                            </p>
                            <Button type="submit" variant="primary" disabled={changingPassword}>
                                {changingPassword ? "Changing..." : "Change password"}
                            </Button>
                        </form>
                    </section>
                </div>
            )}
        </PageShell>
    );
};

export default Account;
