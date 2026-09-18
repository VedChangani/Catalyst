import Menubar from "./components/Menubar/Menubar.jsx";
import {Navigate, Route, Routes, useLocation} from "react-router-dom";
import Dashboard from "./pages/Dashboard/Dashboard.jsx";
import ManageCategory from "./pages/ManageCategory/ManageCategory.jsx";
import ManageUsers from "./pages/ManageUsers/ManageUsers.jsx";
import ManageItems from "./pages/ManageItems/ManageItems.jsx";
import Explore from "./pages/Explore/Explore.jsx";
import {Toaster} from "react-hot-toast";
import Login from "./pages/Login/Login.jsx";
import OrderHistory from "./pages/OrderHistory/OrderHistory.jsx";
import CustomerOrderDetail from "./pages/OrderHistory/CustomerOrderDetail.jsx";
import PosBilling from "./pages/PosBilling/PosBilling.jsx";
import {useContext} from "react";
import {AppContext} from "./context/AppContext.jsx";
import NotFound from "./pages/NotFound/NotFound.jsx";

// Where each role lands after login and when it hits a route it may not use. A cashier's home
// must be a route the cashier is allowed on, otherwise the redirect would loop.
const homePathFor = (role) => {
    if (role === "ROLE_ADMIN") return "/dashboard";
    if (role === "ROLE_CASHIER") return "/pos";
    return "/explore";
};

const App = () => {
    const location = useLocation();
    const {auth} = useContext(AppContext);

    const LoginRoute = ({element}) => {
        if (!auth.token) {
            return element;
        }

        return <Navigate to={homePathFor(auth.role)} replace />;
    }

    const ProtectedRoute = ({element, allowedRoles}) => {
        if (!auth.token) {
            return <Navigate to="/login" replace />;
        }

        if (allowedRoles && !allowedRoles.includes(auth.role)) {
            return <Navigate to={homePathFor(auth.role)} replace />;
        }

        return element;
    }

    const isAuthScreen = location.pathname === "/login" || location.pathname === "/";

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
                            allowedRoles={["ROLE_ADMIN"]}
                        />
                    }
                />
                <Route
                    path="/explore"
                    element={
                        <ProtectedRoute
                            element={<Explore />}
                            allowedRoles={["ROLE_USER", "ROLE_ADMIN"]}
                        />
                    }
                />
                <Route
                    path="/pos"
                    element={
                        <ProtectedRoute
                            element={<PosBilling />}
                            allowedRoles={["ROLE_CASHIER", "ROLE_ADMIN"]}
                        />
                    }
                />
                {/*Admin only routes*/}
                <Route path="/category" element={<ProtectedRoute element={<ManageCategory />} allowedRoles={['ROLE_ADMIN']} />} />
                <Route path="/users" element={<ProtectedRoute element={<ManageUsers />} allowedRoles={["ROLE_ADMIN"]} />} />
                <Route path="/items" element={<ProtectedRoute element={<ManageItems />} allowedRoles={["ROLE_ADMIN"]} /> } />

                <Route path="/login" element={<LoginRoute element={<Login />} />} />
                <Route
                    path="/orders"
                    element={
                        <ProtectedRoute
                            element={<OrderHistory />}
                            allowedRoles={["ROLE_USER", "ROLE_ADMIN"]}
                        />
                    }
                />
                {/* Customer-only: one order from the caller's own purchase history */}
                <Route
                    path="/orders/:orderId"
                    element={
                        <ProtectedRoute
                            element={<CustomerOrderDetail />}
                            allowedRoles={["ROLE_USER"]}
                        />
                    }
                />
                <Route path="/" element={<Login />} />
                <Route path="*" element={<NotFound />} />

            </Routes>
        </div>
    );
}

export default App;
