import Menubar from "./components/Menubar/Menubar.jsx";
import {Navigate, Route, Routes, useLocation} from "react-router-dom";
import Dashboard from "./pages/Dashboard/Dashboard.jsx";
import ManageCategory from "./pages/ManageCategory/ManageCategory.jsx";
import ManageItems from "./pages/ManageItems/ManageItems.jsx";
import Explore from "./pages/Explore/Explore.jsx";
import {Toaster} from "react-hot-toast";
import Login from "./pages/Login/Login.jsx";
import Register from "./pages/Register/Register.jsx";
import OrderHistory from "./pages/OrderHistory/OrderHistory.jsx";
import CustomerOrderDetail from "./pages/OrderHistory/CustomerOrderDetail.jsx";
import PosBilling from "./pages/PosBilling/PosBilling.jsx";
import {useContext} from "react";
import {AppContext} from "./context/AppContext.jsx";
import NotFound from "./pages/NotFound/NotFound.jsx";
import Analytics from "./pages/Analytics/Analytics.jsx";
import MySales from "./pages/OrderHistory/MySales.jsx";
import Cart from "./pages/Cart/Cart.jsx";
import ManageCashiers from "./pages/ManageCashiers/ManageCashiers.jsx";
import Account from "./pages/Account/Account.jsx";
import Activity from "./pages/Activity/Activity.jsx";
import {homePathFor, rootPathFor, ROLE_ADMIN, ROLE_CASHIER, ROLE_USER} from "./util/roles.js";
import {hasSession} from "./util/authSession.js";


// Auth is read synchronously from storage (AppContext), so these decide on the first render -
// no page of the wrong role is shown, even briefly, on a refresh or direct URL.
//
// Both guards are declared at MODULE scope on purpose. Declared inside App they would be a new
// function - and therefore a new element type - on every App render, and React unmounts and
// remounts a subtree whose type changed. App re-renders on every AppContext change (adding a cart
// line, refreshing the catalog, signing in), so the routed page was being torn down and rebuilt
// mid-flow, silently resetting its local state: on the POS screen that wiped the explicitly
// selected registered customer and the billing name/mobile, turning the sale into a walk-in.
const LoginRoute = ({element}) => {
    const {auth} = useContext(AppContext);
    if (!hasSession(auth.token, auth.role)) {
        return element;
    }

    return <Navigate to={homePathFor(auth.role)} replace />;
}

const ProtectedRoute = ({element, allowedRoles}) => {
    const {auth} = useContext(AppContext);
    if (!hasSession(auth.token, auth.role)) {
        return <Navigate to="/login" replace />;
    }

    if (allowedRoles && !allowedRoles.includes(auth.role)) {
        return <Navigate to={homePathFor(auth.role)} replace />;
    }

    return element;
}

const App = () => {
    const location = useLocation();
    const {auth} = useContext(AppContext);

    const isAuthScreen = location.pathname === "/login" || location.pathname === "/register" || location.pathname === "/";

    return (
        <div className="app-shell">
            {!isAuthScreen && <Menubar />}
            <Toaster
                position="top-right"
                toastOptions={{
                    style: {
                        border: "2px solid #111827",
                        boxShadow: "3px 3px 0 #111827",
                        borderRadius: 0,
                        fontWeight: 700,
                        fontFamily: "Outfit, sans-serif",
                    },
                }}
            />
            <Routes>
                <Route
                    path="/dashboard"
                    element={
                        <ProtectedRoute
                            element={<Dashboard />}
                            allowedRoles={[ROLE_ADMIN]}
                        />
                    }
                />
                <Route
                    path="/analytics"
                    element={
                        <ProtectedRoute
                            element={<Analytics />}
                            allowedRoles={[ROLE_ADMIN]}
                        />
                    }
                />
                <Route
                    path="/explore"
                    element={
                        <ProtectedRoute
                            element={<Explore />}
                            allowedRoles={[ROLE_USER]}
                        />
                    }
                />
                <Route
                    path="/pos"
                    element={
                        <ProtectedRoute
                            element={<PosBilling />}
                            allowedRoles={[ROLE_CASHIER]}
                        />
                    }
                />
                {/* Every signed-in role: own Activity Log (customer/cashier) or System Activity (admin) */}
                <Route path="/activity" element={<ProtectedRoute element={<Activity />} allowedRoles={[ROLE_USER, ROLE_CASHIER, ROLE_ADMIN]} />} />
                {/* Every signed-in role: the caller's own profile and password */}
                <Route path="/account" element={<ProtectedRoute element={<Account />} allowedRoles={[ROLE_USER, ROLE_CASHIER, ROLE_ADMIN]} />} />
                {/* Customer only: the current purchase and its checkout */}
                <Route path="/cart" element={<ProtectedRoute element={<Cart />} allowedRoles={[ROLE_USER]} />} />
                {/* Cashier only: the cashier's own POS sales */}
                <Route path="/sales" element={<ProtectedRoute element={<MySales />} allowedRoles={[ROLE_CASHIER]} />} />
                <Route path="/sales/:orderId" element={<ProtectedRoute element={<CustomerOrderDetail backTo="/sales" backLabel="My Sales" />} allowedRoles={[ROLE_CASHIER]} />} />
                {/*Admin only routes*/}
                <Route path="/category" element={<ProtectedRoute element={<ManageCategory />} allowedRoles={[ROLE_ADMIN]} />} />
                <Route path="/cashiers" element={<ProtectedRoute element={<ManageCashiers />} allowedRoles={[ROLE_ADMIN]} />} />
                <Route path="/items" element={<ProtectedRoute element={<ManageItems />} allowedRoles={[ROLE_ADMIN]} /> } />

                <Route path="/login" element={<LoginRoute element={<Login />} />} />
                <Route path="/register" element={<LoginRoute element={<Register />} />} />
                <Route
                    path="/orders"
                    element={
                        <ProtectedRoute
                            element={<OrderHistory />}
                            allowedRoles={[ROLE_USER, ROLE_ADMIN]}
                        />
                    }
                />
                {/* Customer-only: one order from the caller's own purchase history */}
                <Route
                    path="/orders/:orderId"
                    element={
                        <ProtectedRoute
                            element={<CustomerOrderDetail />}
                            allowedRoles={[ROLE_USER]}
                        />
                    }
                />
                {/* "/" is not a page: signed out -> /login, signed in -> the role's home */}
                <Route path="/" element={<Navigate to={rootPathFor(auth.token, auth.role)} replace />} />
                <Route path="*" element={<NotFound />} />

            </Routes>
        </div>
    );
}

export default App;
