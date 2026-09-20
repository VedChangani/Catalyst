import {createContext, useEffect, useRef, useState} from "react";
import toast from "react-hot-toast";
import {fetchCategories} from "../Service/CategoryService.js";
import {fetchItems} from "../Service/ItemService.js";
import {cartQuantityTotal} from "../util/cart.js";
import {hasSession} from "../util/authSession.js";
import {createLatestOnly} from "../util/latestOnly.js";

export const AppContext = createContext(null);

export const AppContextProvider = (props) => {

    const [categories, setCategories] = useState([]);
    const [itemsData, setItemsData] = useState([]);
    // Read the stored session synchronously so a reload on a protected URL (e.g. /orders) is judged
    // against the real session on the first render instead of being bounced through /login.
    const [auth, setAuth] = useState(() => ({
        token: localStorage.getItem("token"),
        role: localStorage.getItem("role"),
    }));
    const [cartItems, setCartItems] = useState([]);
    const [isCatalogLoading, setIsCatalogLoading] = useState(true);

    const addToCart = (item) => {
        // Identity is itemId, not name (matches removeFromCart/updateQuantity below, and the
        // availability logic in Item.jsx/CartItems.jsx) - two distinct products that happen to
        // share a display name must stay as separate cart lines, never merged.
        const existingItem = cartItems.find(cartItem => cartItem.itemId === item.itemId);
        if (existingItem) {
            setCartItems(cartItems.map(cartItem => cartItem.itemId === item.itemId ? {...cartItem, quantity: cartItem.quantity + 1} : cartItem));
        } else {
            setCartItems([...cartItems, {...item, quantity: 1}]);
        }
    }

    const removeFromCart = (itemId) => {
        setCartItems(cartItems.filter(item => item.itemId !== itemId));
    }

    const updateQuantity = (itemId, newQuantity) => {
        setCartItems(cartItems.map(item => item.itemId === itemId ? {...item, quantity: newQuantity} : item));
    }

    // Overlapping catalog fetches: only the newest response may write state.
    const catalogRequests = useRef(createLatestOnly());

    const loadCatalog = async () => {
        const isCurrent = catalogRequests.current.begin();
        setIsCatalogLoading(true);
        try {
            const [categoryResponse, itemResponse] = await Promise.all([fetchCategories(), fetchItems()]);
            if (isCurrent()) {
                setCategories(categoryResponse.data);
                setItemsData(itemResponse.data);
            }
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to load the catalog");
        } finally {
            if (isCurrent()) {
                setIsCatalogLoading(false);
            }
        }
    }

    // Background re-sync with the server's authoritative stock/availability (after a sale, or a
    // rejected checkout). Unlike loadCatalog it never raises isCatalogLoading: pages such as the
    // POS replace their whole tree with a loading screen while that flag is set, which would
    // unmount them and discard the cart-side state (selected customer, billing details).
    // The items already on screen stay visible until the fresh data arrives.
    const refreshCatalog = async () => {
        const isCurrent = catalogRequests.current.begin();
        try {
            const [categoryResponse, itemResponse] = await Promise.all([fetchCategories(), fetchItems()]);
            if (isCurrent()) {
                setCategories(categoryResponse.data);
                setItemsData(itemResponse.data);
            }
        } catch (error) {
            console.error(error);
            toast.error("Stock levels could not be refreshed. Reload the page to see current stock.");
        }
    }

    useEffect(() => {
        const token = localStorage.getItem("token");
        const role = localStorage.getItem("role");
        // The catalog endpoints require a signed-in user, so there is nothing to fetch (and
        // nothing to show a loading/error state for) until a session exists.
        if (hasSession(token, role)) {
            setAuth({token, role});
            loadCatalog();
        } else {
            setIsCatalogLoading(false);
        }
    }, []);

    // Establishing a session loads the catalog. Clearing it (logout, password change) must NOT:
    // those protected requests would go out without a token and come back 401. The previous
    // session's catalog is dropped instead so the next sign-in starts fresh.
    const setAuthData = (token, role) => {
        setAuth({token, role});
        if (hasSession(token, role)) {
            loadCatalog();
        } else {
            catalogRequests.current.begin(); // a fetch still in flight must not refill the cleared catalog
            setCategories([]);
            setItemsData([]);
            setIsCatalogLoading(false);
        }
    }

    const clearCart = () => {
        setCartItems([]);
    }

    const contextValue = {
        categories,
        setCategories,
        auth,
        setAuthData,
        itemsData,
        setItemsData,
        isCatalogLoading,
        addToCart,
        cartItems,
        // derived from cartItems on every render - there is no second copy that could drift
        cartCount: cartQuantityTotal(cartItems),
        removeFromCart,
        updateQuantity,
        clearCart,
        // Silently re-fetches items/categories so a stale availableQuantity/active value (after a
        // completed sale, or a checkout rejected with a stock conflict) is replaced with the
        // server's current catalog state.
        refreshCatalog
    }

    return <AppContext.Provider value={contextValue}>
        {props.children}
    </AppContext.Provider>
}