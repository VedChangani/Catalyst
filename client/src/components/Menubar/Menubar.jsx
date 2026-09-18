import {assets} from "../../assets/assets.js";
import {Link, useLocation, useNavigate} from "react-router-dom";
import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";

const Menubar = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const {setAuthData, auth} = useContext(AppContext);
    const [open, setOpen] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);

    const logout = () => {
        localStorage.removeItem("token");
        localStorage.removeItem("role");
        setAuthData(null, null);
        navigate("/login");
    };

    const isActive = (path) => {
        return location.pathname === path;
    };

    const isAdmin = auth?.role === "ROLE_ADMIN";

    const linkClass = (path) =>
        `border-2 border-transparent px-3 py-1.5 text-sm font-extrabold uppercase tracking-wide transition-colors duration-150 ${
            isActive(path)
                ? "border-ink bg-primary text-white shadow-[2px_2px_0_#111827]"
                : "hover:border-ink hover:bg-primary/10"
        }`;

    return (
        <header className="sticky top-0 z-40 border-b-2 border-ink bg-surface">
            <nav className="relative mx-auto flex max-w-[1440px] items-center gap-3 px-4 py-3 sm:px-6 lg:px-8">
                <Link to="/explore" className="flex shrink-0 items-center gap-2">
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
                        <li>
                            <Link className={linkClass('/explore')} to="/explore" onClick={() => setMenuOpen(false)}>
                                Explore
                            </Link>
                        </li>
                        <li>
                            <Link className={linkClass('/orders')} to="/orders" onClick={() => setMenuOpen(false)}>
                                Order History
                            </Link>
                        </li>
                        {isAdmin && (
                            <>
                                <li>
                                    <Link className={linkClass('/dashboard')} to="/dashboard" onClick={() => setMenuOpen(false)}>
                                        Dashboard
                                    </Link>
                                </li>
                                <li>
                                    <Link className={linkClass('/items')} to="/items" onClick={() => setMenuOpen(false)}>
                                        Manage Items
                                    </Link>
                                </li>
                                <li>
                                    <Link className={linkClass('/category')} to="/category" onClick={() => setMenuOpen(false)}>
                                        Manage Categories
                                    </Link>
                                </li>
                                <li>
                                    <Link className={linkClass('/users')} to="/users" onClick={() => setMenuOpen(false)}>
                                        Manage Users
                                    </Link>
                                </li>
                            </>
                        )}
                    </ul>

                    <div className="relative lg:ml-auto">
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
                                    <a href="#!" className="block px-4 py-2 text-sm font-bold hover:bg-primary/10">
                                        Settings
                                    </a>
                                </li>
                                <li>
                                    <a href="#!" className="block px-4 py-2 text-sm font-bold hover:bg-primary/10">
                                        Activity log
                                    </a>
                                </li>
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
