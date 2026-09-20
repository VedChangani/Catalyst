import {assets} from "../../assets/assets.js";
import {Link, useLocation, useNavigate} from "react-router-dom";
import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import {homePathFor, NAV_ITEMS, ROLE_ADMIN, ROLE_USER} from "../../util/roles.js";

const Menubar = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const {setAuthData, auth, cartCount, clearCart} = useContext(AppContext);
    const [open, setOpen] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);

    const logout = () => {
        localStorage.removeItem("token");
        localStorage.removeItem("role");
        clearCart(); // never carry one session's cart into the next
        setAuthData(null, null);
        navigate("/login");
    };

    const isActive = (path) => {
        // an open order detail (/orders/:id, /sales/:id) still belongs to its list's entry
        return location.pathname === path
            || ((path === "/orders" || path === "/sales") && location.pathname.startsWith(`${path}/`));
    };

    const isAdmin = auth?.role === ROLE_ADMIN;
    // the cart is a customer-only feature: staff never see the cart entry or its count
    const isCustomer = auth?.role === ROLE_USER;
    const navItems = NAV_ITEMS[auth?.role] ?? [];

    const linkClass = (path) =>
        `border-2 border-transparent px-3 py-1.5 text-sm font-extrabold uppercase tracking-wide transition-colors duration-150 ${
            isActive(path)
                ? "border-ink bg-primary text-white shadow-[2px_2px_0_#111827]"
                : "hover:border-ink hover:bg-primary/10"
        }`;

    return (
        <header className="sticky top-0 z-40 border-b-2 border-ink bg-surface">
            <nav className="relative mx-auto flex max-w-[1440px] items-center gap-3 px-4 py-3 sm:px-6 lg:px-8">
                <Link to={homePathFor(auth?.role)} className="flex shrink-0 items-center gap-2">
                    <img src={assets.logo} alt="Logo" className="h-10 w-auto border-2 border-ink bg-paper p-0.5" />
                    <span className="hidden text-sm font-extrabold uppercase tracking-[0.16em] sm:inline">Retail Billing</span>
                </Link>

                <button
                    className="ml-auto border-2 border-ink bg-primary px-3 py-2 font-extrabold text-white lg:hidden"
                    type="button"
                    aria-controls="navbarNav"
                    aria-expanded={menuOpen}
                    aria-label="Toggle navigation"
                    onClick={() => setMenuOpen((value) => !value)}
                >
                    <i className="bi bi-list text-xl"></i>
                </button>

                <div
                    id="navbarNav"
                    className={`${menuOpen ? "flex" : "hidden"} absolute left-0 top-full w-full flex-col gap-4 border-b-2 border-ink bg-surface p-4 lg:static lg:ml-6 lg:flex lg:w-auto lg:flex-1 lg:flex-row lg:items-center lg:border-0 lg:p-0`}
                >
                    <ul className="flex flex-1 flex-col gap-2 lg:flex-row lg:flex-wrap lg:items-center">
                        {navItems.map(({to, label}) => (
                            <li key={to}>
                                <Link className={linkClass(to)} to={to} onClick={() => setMenuOpen(false)}>
                                    {label}
                                </Link>
                            </li>
                        ))}
                    </ul>

                    {isCustomer && (
                        <Link
                            to="/cart"
                            onClick={() => setMenuOpen(false)}
                            aria-label={`Cart, ${cartCount} ${cartCount === 1 ? "item" : "items"}`}
                            className={`relative flex items-center gap-2 border-2 px-3 py-1.5 font-extrabold transition-colors duration-150 lg:ml-auto ${
                                location.pathname === "/cart"
                                    ? "border-ink bg-primary text-white shadow-[2px_2px_0_#111827]"
                                    : "border-ink bg-paper shadow-[2px_2px_0_#111827] hover:bg-primary/10"
                            }`}
                        >
                            <i className="bi bi-cart3 text-lg" aria-hidden="true"></i>
                            <span data-testid="cart-count" className="min-w-5 border-2 border-ink bg-coral px-1 text-center text-xs font-extrabold text-ink">
                                {cartCount}
                            </span>
                        </Link>
                    )}
                    <div className={`relative ${isCustomer ? "" : "lg:ml-auto"}`}>
                        <button
                            type="button"
                            className="flex items-center gap-2 border-2 border-ink bg-paper px-2 py-1 shadow-[2px_2px_0_#111827]"
                            id="navbarDropdown"
                            aria-expanded={open}
                            onClick={() => setOpen((value) => !value)}
                        >
                            <img src={assets.profile} alt="Account" height={32} width={32} className="h-8 w-8 object-cover" />
                            <i className="bi bi-chevron-down text-sm"></i>
                        </button>
                        {open && (
                            <ul
                                className="absolute right-0 z-50 mt-2 min-w-44 border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]"
                                aria-labelledby="navbarDropdown"
                            >
                                <li>
                                    <Link
                                        to="/account"
                                        className="block px-4 py-2 text-sm font-bold hover:bg-primary/10"
                                        onClick={() => {
                                            setOpen(false);
                                            setMenuOpen(false);
                                        }}
                                    >
                                        Account
                                    </Link>
                                </li>
                                {/* Customers and cashiers: their own log. Admins use the System Activity nav entry. */}
                                {!isAdmin && (
                                    <li>
                                        <Link
                                            to="/activity"
                                            className="block px-4 py-2 text-sm font-bold hover:bg-primary/10"
                                            onClick={() => {
                                                setOpen(false);
                                                setMenuOpen(false);
                                            }}
                                        >
                                            Activity log
                                        </Link>
                                    </li>
                                )}
                                <li className="border-t-2 border-ink">
                                    <a
                                        href="#!"
                                        className="block px-4 py-2 text-sm font-bold hover:bg-danger/15 hover:text-danger"
                                        onClick={logout}
                                    >
                                        Logout
                                    </a>
                                </li>
                            </ul>
                        )}
                    </div>
                </div>
            </nav>
        </header>
    );
};

export default Menubar;
